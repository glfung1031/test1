package com.example.stocktracker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object Runner {
    /** Checks every enabled item once; fires notification/email on new deals. */
    fun runOnce(ctx: Context) {
        val settings = Store.settings(ctx)
        for (item in Store.items(ctx).filter { it.enabled }) {
            val r = Checker.check(item.url)
            val deal = r.inStock == true && r.price != null && r.price <= item.maxPrice
            val shouldAlert = deal && !item.alerted
            Store.mutate(ctx) { list ->
                list.map {
                    if (it.id != item.id) it else it.copy(
                        lastPrice = r.price ?: it.lastPrice,
                        inStock = r.inStock, lastChecked = System.currentTimeMillis(),
                        note = r.error ?: "", alerted = if (r.error != null) it.alerted else deal)
                }
            }
            if (shouldAlert) {
                Alerts.notify(ctx, item, r.price)
                if (settings.emailOn && settings.smtpUser.isNotBlank()) try {
                    Alerts.email(settings, "In stock: ${item.name} for \$${"%.2f".format(r.price)}",
                        "${item.name}\nPrice: \$${"%.2f".format(r.price)} (your max: \$${"%.2f".format(item.maxPrice)})\n" +
                        "${Checker.retailer(item.url)}\n${item.url}")
                } catch (_: Exception) {}
            }
            Thread.sleep(700)
        }
    }
}

class CheckWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        val jitterSec = Store.settings(ctx).jitterSec
        if (jitterSec > 0) try { Thread.sleep((0 until jitterSec).random() * 1000L) } catch (_: InterruptedException) {}
        Runner.runOnce(ctx)
        return androidx.work.ListenableWorker.Result.success()
    }

    companion object {
        fun schedule(c: Context, minutes: Int) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(maxOf(15, minutes).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun runNow(c: Context) = WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<CheckWorker>().build())
    }
}
