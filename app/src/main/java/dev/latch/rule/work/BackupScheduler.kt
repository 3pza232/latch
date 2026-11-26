package dev.latch.rule.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes so the first run inside each clock hour can capture the
 * hourly boundary, and the first run after midnight captures the daily one.
 * WorkManager may delay a run, which is fine: a missed hour is skipped rather
 * than backfilled.
 */
object BackupScheduler {

    private const val BACKUP_WORK = "latch-backup-boundary"

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BACKUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
