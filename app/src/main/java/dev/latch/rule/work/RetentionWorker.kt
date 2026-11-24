package dev.latch.rule.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.latch.rule.LatchApplication

/**
 * Folds the log into statistics before deleting expired log files, and thins old
 * snapshots. Running daily keeps both bounded even when the app stays resident.
 */
class RetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LatchApplication).container
        container.statsService.refresh()
        container.fileEventLog.prune(container.settingsStore.load().logRetentionDays)
        container.backupService.prune()
        return Result.success()
    }
}
