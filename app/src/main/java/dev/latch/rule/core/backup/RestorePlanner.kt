package dev.latch.rule.core.backup

import dev.latch.rule.core.model.RuleGroup

/**
 * Computes the document a restore would produce and what it changes.
 *
 * Restore is configuration-only: it never writes system settings. A full restore
 * replaces the whole document; a scoped restore replaces only the selected groups
 * and leaves every other group untouched.
 */
object RestorePlanner {

    fun plan(
        current: List<RuleGroup>,
        snapshot: BackupSnapshot,
        scope: RestoreScope,
    ): RestorePlan {
        val currentById = current.associateBy { it.id }
        val targetById = snapshot.groups.associateBy { it.id }

        return when (scope) {
            RestoreScope.Full -> fullPlan(current, currentById, snapshot, targetById)
            is RestoreScope.Selected -> selectedPlan(current, currentById, snapshot, scope.groupIds, targetById)
        }
    }

    private fun fullPlan(
        current: List<RuleGroup>,
        currentById: Map<String, RuleGroup>,
        snapshot: BackupSnapshot,
        targetById: Map<String, RuleGroup>,
    ): RestorePlan {
        val changes = buildList {
            snapshot.groups.forEach { group ->
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
            current.filter { it.id !in targetById }.forEach { group ->
                add(GroupChange(group.id, group.name, GroupChangeKind.REMOVED, group.rules.size))
            }
        }
        return RestorePlan(
            snapshotId = snapshot.meta.id,
            snapshotCreatedAt = snapshot.meta.createdAt,
            scope = RestoreScope.Full,
            changes = changes,
            groups = snapshot.groups,
        )
    }

    private fun selectedPlan(
        current: List<RuleGroup>,
        currentById: Map<String, RuleGroup>,
        snapshot: BackupSnapshot,
        selectedIds: Set<String>,
        targetById: Map<String, RuleGroup>,
    ): RestorePlan {
        val changes = mutableListOf<GroupChange>()
        val restored = current.map { group ->
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
        snapshot.groups
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
            groups = restored,
        )
    }
}
