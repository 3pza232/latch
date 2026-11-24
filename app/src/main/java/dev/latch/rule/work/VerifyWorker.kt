package dev.latch.rule.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.latch.rule.LatchApplication
import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.privilege.PrivilegeStatus

/** Verifies all rules, repairs drift, and notifies only when repair fails. */
class VerifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LatchApplication).container
        container.backupService.ensureBoundaries()
        if (container.privilegeBroker.status() != PrivilegeStatus.READY) {
            container.eventLog.record(
                LatchEvent(
                    level = EventLevel.WARN,
                    source = EventSource.PERIODIC,
                    action = EventAction.SKIP,
                    outcome = "skipped",
                    detail = "Shizuku is not ready",
                ),
            )
            return Result.success()
        }

        val report = container.ruleCoordinator.verifyAndRepair(EventSource.PERIODIC)
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
