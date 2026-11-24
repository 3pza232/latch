package dev.latch.rule.core.backup

import dev.latch.rule.core.model.RuleGroup

/** Why a snapshot exists. Manual and pre-restore snapshots are never pruned automatically. */
enum class BackupReason(val wire: String) {
    MANUAL("manual"),
    AUTO_CHANGE("auto_change"),
    PRE_RESTORE("pre_restore"),
    PRE_MIGRATION("pre_migration"),
}

/** Header of a snapshot, cheap to list without touching the rule document. */
data class BackupMeta(
    val id: String,
    val createdAt: Long,
    val reason: BackupReason,
    val groups: Int,
    val rules: Int,
    val docHash: String,
)

data class BackupSnapshot(
    val meta: BackupMeta,
    val groups: List<RuleGroup>,
)

/** Which part of a snapshot to reinstate. */
sealed interface RestoreScope {
    data object Full : RestoreScope
    data class Selected(val groupIds: Set<String>) : RestoreScope
}

enum class GroupChangeKind { ADDED, REPLACED, UNCHANGED, REMOVED }

data class GroupChange(
    val groupId: String,
    val groupName: String,
    val kind: GroupChangeKind,
    val ruleCount: Int,
)

/** Result of comparing a snapshot with the current document. */
data class RestorePlan(
    val snapshotId: String,
    val snapshotCreatedAt: Long,
    val scope: RestoreScope,
    val changes: List<GroupChange>,
    /** The document that restore would persist. */
    val groups: List<RuleGroup>,
) {
    val added: Int get() = changes.count { it.kind == GroupChangeKind.ADDED }
    val replaced: Int get() = changes.count { it.kind == GroupChangeKind.REPLACED }
    val removed: Int get() = changes.count { it.kind == GroupChangeKind.REMOVED }
    val unchanged: Int get() = changes.count { it.kind == GroupChangeKind.UNCHANGED }
    val ruleCount: Int get() = groups.sumOf { it.rules.size }

    /** Groups that will be written and should therefore be re-verified. */
    val affectedGroupIds: Set<String>
        get() = changes
            .filter { it.kind != GroupChangeKind.UNCHANGED }
            .map { it.groupId }
            .toSet()
}
