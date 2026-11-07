package dev.latch.rule.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.latch.rule.LatchApplication
import dev.latch.rule.privilege.PrivilegeStatus

/** Verifies all rules, repairs drift, and notifies only when repair fails. */
class VerifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LatchApplication).container
        if (container.privilegeBroker.status() != PrivilegeStatus.READY) return Result.success()

        val report = container.ruleCoordinator.verifyAndRepair()
        val settings = container.settingsStore.load()
        if (settings.notifyOnDrift) {
            container.driftNotifier.notifyUnrepaired(
                drifted = report.remainingDrifted,
                failed = report.remainingFailed,
            )
        }
        return Result.success()
    }
}
