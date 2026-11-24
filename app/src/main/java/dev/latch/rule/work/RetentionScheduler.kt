package dev.latch.rule.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Keeps log files and snapshots pruned even when the app is not reopened. */
object RetentionScheduler {

    private const val RETENTION_WORK = "latch-retention"

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RETENTION_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
