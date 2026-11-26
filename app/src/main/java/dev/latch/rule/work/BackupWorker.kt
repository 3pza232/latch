package dev.latch.rule.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.latch.rule.LatchApplication

/**
 * Captures the current clock hour and day boundaries while the user is not
 * opening the app. This is deliberately independent of periodic verification:
 * it only reads the rule document and writes snapshots, it never checks or
 * repairs system state.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LatchApplication).container
        container.backupService.ensureBoundaries()
        return Result.success()
    }
}
