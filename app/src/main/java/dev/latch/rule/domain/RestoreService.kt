package dev.latch.rule.domain

import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.RestorePlan
import dev.latch.rule.core.backup.RestorePlanner
import dev.latch.rule.core.backup.RestoreScope
import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent

/**
 * Restores a snapshot, optionally limited to some groups.
 *
 * Restoring only rewrites configuration. System settings are not touched unless the
 * caller explicitly asks to apply afterwards, and the current state is snapshotted
 * first so the restore itself can be undone.
 */
class RestoreService(
    private val backupService: BackupService,
    private val coordinator: RuleCoordinator,
    private val eventLog: EventLog = EventLog.Noop,
) {

    /** Compares a snapshot with the current document without writing anything. */
    suspend fun preview(snapshotId: String, scope: RestoreScope): RestorePlan? {
        val snapshot = backupService.load(snapshotId) ?: return null
        return RestorePlanner.plan(coordinator.snapshot.value.document, snapshot, scope)
    }

    suspend fun restore(
        snapshotId: String,
        scope: RestoreScope,
        apply: Boolean = false,
        source: EventSource = EventSource.RESTORE,
    ): RestorePlan? {
        val snapshot = backupService.load(snapshotId) ?: return null
        val plan = RestorePlanner.plan(coordinator.snapshot.value.document, snapshot, scope)
        if (plan.affectedGroupIds.isEmpty()) return plan

        backupService.snapshotNow(BackupReason.PRE_RESTORE)
        coordinator.restore(plan.document)
        if (apply) {
            plan.affectedGroupIds.forEach { groupId -> coordinator.applyGroup(groupId, source) }
        }
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.RESTORE,
                outcome = "restored",
                detail = buildString {
                    append("snapshot=${plan.snapshotId} ")
                    append("scope=${if (scope is RestoreScope.Full) "full" else "groups"} ")
                    append("added=${plan.added} replaced=${plan.replaced} removed=${plan.removed} ")
                    append("applied=$apply")
                },
            ),
        )
        return plan
    }
}
