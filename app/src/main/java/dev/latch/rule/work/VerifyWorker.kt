package dev.latch.rule.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.latch.rule.LatchApplication
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.privilege.PrivilegeStatus

/** Verifies all enabled rules and notifies when drift is detected. */
class VerifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LatchApplication).container
        if (container.privilegeBroker.status() != PrivilegeStatus.READY) return Result.success()

        val snapshot = container.verifyService.verifyAll()
        val settings = container.settingsStore.load()
        if (settings.notifyOnDrift) {
            val drifted = snapshot.results.values.count { it.state == RuleState.DRIFTED }
            val failed = snapshot.results.values.count { it.state == RuleState.FAILED }
            container.driftNotifier.notifyDrift(drifted, failed)
        }
        return Result.success()
    }
}
