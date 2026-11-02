package com.macrorunner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

object Scheduler {

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    /** Next time this macro should fire after [from], or null if manual-only. */
    fun nextTrigger(m: Macro, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (m.days.isEmpty()) return null
        for (offset in 0..7) {
            val date = from.toLocalDate().plusDays(offset.toLong())
            if (date.dayOfWeek.value !in m.days) continue
            val dt = date.atTime(m.hour, m.minute).atZone(from.zone)
            if (dt.isAfter(from)) return dt
        }
        return null
    }

    private fun pending(ctx: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, MacroAlarmReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun cancel(ctx: Context, id: String) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id))
    }

    fun schedule(ctx: Context, m: Macro, from: ZonedDateTime = ZonedDateTime.now()) {
        cancel(ctx, m.id)
        if (!m.enabled) return
        val next = nextTrigger(m, from) ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = next.toInstant().toEpochMilli()
        val pi = pending(ctx, m.id)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // inexact fallback
    }

    fun rescheduleAll(ctx: Context) = MacroStorage.load(ctx).forEach { schedule(ctx, it) }
}

class MacroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val macro = MacroStorage.load(ctx).firstOrNull { it.id == id } ?: return
        // Queue the next occurrence first (margin avoids double-firing on early alarms)
        Scheduler.schedule(ctx, macro, ZonedDateTime.now().plusMinutes(1))
        if (macro.enabled) MacroEngine.start(macro, wake = true)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.rescheduleAll(ctx)
}
