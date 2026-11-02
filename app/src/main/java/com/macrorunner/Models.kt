package com.macrorunner

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Collections
import java.util.UUID

enum class StepType { TAP, SWIPE, WAIT, LOOP }
enum class LoopMode { COUNT, DURATION }

/** One node in a macro. LOOP steps hold their own [children], so loops can nest. */
data class Step(
    val type: StepType,
    val x: Int = 540,
    val y: Int = 1200,
    val x2: Int = 540,
    val y2: Int = 600,
    val durationMs: Long = 50,        // tap hold time / swipe duration
    val waitMs: Long = 1000,          // WAIT
    val loopMode: LoopMode = LoopMode.COUNT,
    val loopCount: Int = 3,
    val loopDurationMs: Long = 10_000,
    val children: List<Step> = emptyList()
)

data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = false,
    val days: Set<Int> = emptySet(),  // ISO: Monday=1 .. Sunday=7. Empty = manual only
    val hour: Int = 9,
    val minute: Int = 0,
    val steps: List<Step> = emptyList()
) {
    fun timeLabel(): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}

fun List<Step>.totalSteps(): Int = sumOf { 1 + it.children.totalSteps() }

fun fmtMs(ms: Long): String = when {
    ms % 1000L == 0L -> "${ms / 1000} s"
    ms > 1000L -> "${ms / 1000.0} s"
    else -> "$ms ms"
}

// ---------- tree editing helpers ----------
typealias StepPath = List<Int>

data class FlatStep(val path: StepPath, val step: Step, val depth: Int, val index: Int, val siblings: Int)

fun List<Step>.flatten(prefix: StepPath = emptyList(), depth: Int = 0): List<FlatStep> =
    flatMapIndexed { i, s ->
        val p = prefix + i
        listOf(FlatStep(p, s, depth, i, size)) + s.children.flatten(p, depth + 1)
    }

fun List<Step>.editList(parent: StepPath, f: (MutableList<Step>) -> Unit): List<Step> {
    if (parent.isEmpty()) return toMutableList().also(f)
    val i = parent[0]
    return toMutableList().also {
        it[i] = it[i].copy(children = it[i].children.editList(parent.drop(1), f))
    }
}

fun List<Step>.addAt(parent: StepPath, s: Step) = editList(parent) { it.add(s) }
fun List<Step>.removeAt(path: StepPath) = editList(path.dropLast(1)) { it.removeAt(path.last()) }
fun List<Step>.replaceAt(path: StepPath, s: Step) = editList(path.dropLast(1)) { it[path.last()] = s }
fun List<Step>.moveAt(path: StepPath, dir: Int) = editList(path.dropLast(1)) {
    val i = path.last(); val j = i + dir
    if (j in it.indices) Collections.swap(it, i, j)
}

// ---------- persistence ----------
object MacroStorage {
    private val gson = Gson()
    private val listType = object : TypeToken<List<Macro>>() {}.type
    private fun file(ctx: Context) = File(ctx.filesDir, "macros.json")

    @Synchronized
    fun load(ctx: Context): List<Macro> = runCatching {
        gson.fromJson<List<Macro>>(file(ctx).readText(), listType) ?: emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(ctx: Context, macros: List<Macro>) {
        file(ctx).writeText(gson.toJson(macros))
    }
}
