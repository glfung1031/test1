package com.macrorunner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * The accessibility service is what lets us inject taps without root
 * (AccessibilityService.dispatchGesture). It reads no screen content.
 */
class MacroAccessibilityService : AccessibilityService() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        instance = this
        connected.value = true
        Scheduler.rescheduleAll(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        connected.value = false
        MacroEngine.stopAll()
        scope.cancel()
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: MacroAccessibilityService? = null
        val connected = MutableStateFlow(false)
    }
}

/** Remembers when each macro last started. Kept apart from the macro list so edits never overwrite it. */
object RunLog {
    private const val PREFS = "run_log"
    val lastRun = MutableStateFlow<Map<String, Long>>(emptyMap())
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        lastRun.value = prefs(ctx).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        loaded = true
    }

    @Synchronized fun record(ctx: Context, id: String, at: Long = System.currentTimeMillis()) {
        load(ctx)
        prefs(ctx).edit().putLong(id, at).apply()
        lastRun.update { it + (id to at) }
    }

    @Synchronized fun clear(ctx: Context, id: String) {
        load(ctx)
        prefs(ctx).edit().remove(id).apply()
        lastRun.update { it - id }
    }
}

object MacroEngine {
    val running = MutableStateFlow<Set<String>>(emptySet())
    private val jobs = ConcurrentHashMap<String, Job>()

    // Several macros may run at once, but only one gesture is injected at a time
    // so they never cancel each other's taps.
    private val gestureLock = Mutex()

    fun start(macro: Macro, wake: Boolean = false): Boolean {
        val svc = MacroAccessibilityService.instance ?: return false
        if (jobs.containsKey(macro.id)) return true
        val pm = svc.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wake) wakeScreen(pm)

        val job = svc.scope.launch(start = CoroutineStart.LAZY) {
            val cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "macrorunner:run")
            cpu.acquire(60 * 60 * 1000L)
            RunLog.record(svc, macro.id)
            try {
                runSteps(svc, macro.steps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MacroEngine", "Macro failed", e)
            } finally {
                if (cpu.isHeld) cpu.release()
                jobs.remove(macro.id)
                running.update { it - macro.id }
            }
        }
        jobs[macro.id] = job
        running.update { it + macro.id }
        job.start()
        return true
    }

    fun stop(id: String) { jobs[id]?.cancel() }
    fun stopAll() { jobs.values.forEach { it.cancel() } }

    @Suppress("DEPRECATION")
    private fun wakeScreen(pm: PowerManager) {
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "macrorunner:wake"
        ).acquire(10_000)
    }

    private suspend fun runSteps(svc: AccessibilityService, steps: List<Step>) {
        for (s in steps) {
            currentCoroutineContext().ensureActive()
            when (s.type) {
                StepType.TAP -> gesture(svc, Path().apply { moveTo(s.x.toFloat(), s.y.toFloat()) }, s.durationMs)
                StepType.SWIPE -> gesture(svc, Path().apply {
                    moveTo(s.x.toFloat(), s.y.toFloat()); lineTo(s.x2.toFloat(), s.y2.toFloat())
                }, s.durationMs)
                StepType.WAIT -> delay(s.waitMs)
                StepType.LOOP -> {
                    if (s.children.isEmpty()) continue
                    when (s.loopMode) {
                        LoopMode.COUNT -> repeat(s.loopCount.coerceAtLeast(1)) {
                            runSteps(svc, s.children)
                            delay(1)
                        }
                        LoopMode.DURATION -> withTimeoutOrNull(s.loopDurationMs) {
                            while (true) {
                                runSteps(svc, s.children)
                                delay(1) // guards against a busy loop with zero-length steps
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun gesture(svc: AccessibilityService, path: Path, durationMs: Long) =
        gestureLock.withLock {
            suspendCancellableCoroutine<Unit> { cont ->
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000)))
                    .build()
                val cb = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(Unit) }
                    override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(Unit) }
                }
                if (!svc.dispatchGesture(g, cb, null) && cont.isActive) cont.resume(Unit)
            }
        }
}
