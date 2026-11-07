package dev.latch.rule.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules background verification. Zero minutes disables the periodic job. */
object VerifyScheduler {

    private const val PERIODIC_WORK = "latch-periodic-verify"
    private const val ONCE_WORK = "latch-once-verify"

    fun applyPeriodic(context: Context, minutes: Int) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK)
        if (minutes <= 0) return
        val request = PeriodicWorkRequestBuilder<VerifyWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }

    fun verifyOnce(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<VerifyWorker>().build(),
        )
    }
}
