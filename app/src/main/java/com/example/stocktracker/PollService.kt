package com.example.stocktracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager

/** Fast polling (1-14 min). Runs as a foreground service with a persistent notification. */
class PollService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("poll", "Background monitoring", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "poll")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Stock Tracker is watching your items")
            .setContentText("Fast polling active").setOngoing(true).setContentIntent(open).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (!running) {
            running = true
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stocktracker:poll").apply { acquire() }
            thread = Thread {
                while (running) {
                    val start = System.currentTimeMillis()
                    val s = Store.settings(applicationContext)
                    // Randomize the actual check time by up to jitterSec, so requests
                    // don't land on a perfectly predictable cadence.
                    val jitterMs = if (s.jitterSec > 0) (0 until s.jitterSec).random() * 1000L else 0L
                    try { Thread.sleep(jitterMs) } catch (_: InterruptedException) { break }
                    try { Runner.runOnce(applicationContext) } catch (_: InterruptedException) { break } catch (_: Exception) {}
                    // Re-read the interval every 2s so slider changes apply immediately
                    while (running && System.currentTimeMillis() - start <
                        Store.settings(applicationContext).intervalSec * 1000L) {
                        try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    }
                }
            }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; thread?.interrupt()
        try { wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

object Scheduler {
    /** <15 min -> foreground service; >=15 min -> WorkManager. */
    fun apply(c: Context, intervalSec: Int) {
        val svc = Intent(c, PollService::class.java)
        if (intervalSec < 15 * 60) {
            WorkManager.getInstance(c).cancelUniqueWork("check")
            ContextCompat.startForegroundService(c, svc)
        } else {
            c.stopService(svc)
            CheckWorker.schedule(c, (intervalSec / 60).coerceAtLeast(15))
        }
    }
}
