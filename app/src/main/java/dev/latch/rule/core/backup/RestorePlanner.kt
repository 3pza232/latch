package dev.latch.rule.core.backup

import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride

/**
 * Computes the document a restore would produce and what it changes.
 *
 * Restore is configuration-only: it never writes system settings. A full restore
 * replaces the whole document; a scoped restore replaces only the selected groups
 * and leaves every other group untouched. Override decisions follow their rules.
 */
object RestorePlanner {

    fun plan(
        current: RuleDocument,
        snapshot: BackupSnapshot,
        scope: RestoreScope,
    ): RestorePlan {
        val currentById = current.groups.associateBy { it.id }
        val targetById = snapshot.document.groups.associateBy { it.id }

        return when (scope) {
            RestoreScope.Full -> fullPlan(current, currentById, snapshot, targetById)
            is RestoreScope.Selected -> selectedPlan(
                current,
                currentById,
                snapshot,
                scope.groupIds,
                targetById,
            )
        }
    }

    private fun fullPlan(
        current: RuleDocument,
        currentById: Map<String, RuleGroup>,
        snapshot: BackupSnapshot,
        targetById: Map<String, RuleGroup>,
    ): RestorePlan {
        val changes = buildList {
            snapshot.document.groups.forEach { group ->
                val existing = currentById[group.id]
                add(
                    GroupChange(
                        groupId = group.id,
                        groupName = group.name,
                        kind = when {
                            existing == null -> GroupChangeKind.ADDED
                            existing == group -> GroupChangeKind.UNCHANGED
                            else -> GroupChangeKind.REPLACED
                        },
                        ruleCount = group.rules.size,
                    ),
                )
            }
            current.groups.filter { it.id !in targetById }.forEach { group ->
                add(GroupChange(group.id, group.name, GroupChangeKind.REMOVED, group.rules.size))
            }
        }
        return RestorePlan(
            snapshotId = snapshot.meta.id,
            snapshotCreatedAt = snapshot.meta.createdAt,
            scope = RestoreScope.Full,
            changes = changes,
            document = RuleDocument(
                groups = snapshot.document.groups,
                overrides = snapshot.document.overrides,
                revision = current.revision,
            ),
        )
    }

    private fun selectedPlan(
        current: RuleDocument,
        currentById: Map<String, RuleGroup>,
        snapshot: BackupSnapshot,
        selectedIds: Set<String>,
        targetById: Map<String, RuleGroup>,
    ): RestorePlan {
        val changes = mutableListOf<GroupChange>()
        val restored = current.groups.map { group ->
            if (group.id !in selectedIds) return@map group
            val target = targetById[group.id] ?: return@map group
            changes += GroupChange(
                groupId = group.id,
                groupName = target.name,
                kind = if (group == target) GroupChangeKind.UNCHANGED else GroupChangeKind.REPLACED,
                ruleCount = target.rules.size,
            )
            target
        }.toMutableList()

        // Selected groups that no longer exist take their snapshot position at the end.
        snapshot.document.groups
            .filter { it.id in selectedIds && it.id !in currentById }
            .forEach { group ->
                restored += group
                changes += GroupChange(group.id, group.name, GroupChangeKind.ADDED, group.rules.size)
            }

        return RestorePlan(
            snapshotId = snapshot.meta.id,
            snapshotCreatedAt = snapshot.meta.createdAt,
            scope = RestoreScope.Selected(selectedIds),
            changes = changes,
            document = RuleDocument(
                groups = restored,
                overrides = mergeOverrides(current.overrides, snapshot.document.overrides, restored, selectedIds),
                revision = current.revision,
            ),
        )
    }

    /** Overrides survive only when both rules still exist after the restore. */
    private fun mergeOverrides(
        current: List<RuleOverride>,
        restored: List<RuleOverride>,
        groups: List<RuleGroup>,
        selectedIds: Set<String>,
    ): List<RuleOverride> {
        val ruleIds = groups.flatMap { it.rules }.map { it.id }.toSet()
        val selectedRuleIds = groups
            .filter { it.id in selectedIds }
            .flatMap { it.rules }
            .map { it.id }
            .toSet()

        val kept = current.filterNot {
            it.loserRuleId in selectedRuleIds || it.winnerRuleId in selectedRuleIds
        }
        val fromSnapshot = restored.filter {
            it.loserRuleId in selectedRuleIds && it.winnerRuleId in selectedRuleIds
        }
        return (kept + fromSnapshot)
            .filter { it.loserRuleId in ruleIds && it.winnerRuleId in ruleIds }
            .distinctBy { it.loserRuleId }
    }
}
