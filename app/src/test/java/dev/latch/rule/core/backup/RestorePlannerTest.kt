package dev.latch.rule.core.backup

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePlannerTest {

    private val groupA = group("a", "A", 1)
    private val groupARestored = group("a", "A", 2)
    private val groupB = group("b", "B", 1)
    private val groupC = group("c", "C", 3)

    @Test
    fun `full restore replaces the document and reports every change`() {
        val plan = plan(
            current = listOf(groupA, groupB),
            snapshotGroups = listOf(groupARestored, groupC),
            scope = RestoreScope.Full,
        )

        assertEquals(listOf(groupARestored, groupC), plan.groups)
        assertEquals(1, plan.added)
        assertEquals(1, plan.replaced)
        assertEquals(1, plan.removed)
        assertEquals(setOf("a", "c", "b"), plan.affectedGroupIds)
    }

    @Test
    fun `full restore of identical content changes nothing`() {
        val plan = plan(listOf(groupA, groupB), listOf(groupA, groupB), RestoreScope.Full)

        assertEquals(0, plan.added)
        assertEquals(0, plan.replaced)
        assertEquals(0, plan.removed)
        assertEquals(2, plan.unchanged)
        assertTrue(plan.affectedGroupIds.isEmpty())
    }

    @Test
    fun `selected restore only touches the chosen groups`() {
        val plan = plan(
            current = listOf(groupA, groupB),
            snapshotGroups = listOf(groupARestored, groupC),
            scope = RestoreScope.Selected(setOf("a")),
        )

        assertEquals(listOf(groupARestored, groupB), plan.groups)
        assertEquals(1, plan.replaced)
        assertEquals(0, plan.added)
        assertEquals(0, plan.removed)
        assertEquals(setOf("a"), plan.affectedGroupIds)
    }

    @Test
    fun `selected restore adds a group that no longer exists`() {
        val plan = plan(
            current = listOf(groupB),
            snapshotGroups = listOf(groupA, groupC),
            scope = RestoreScope.Selected(setOf("a", "c")),
        )

        assertEquals(listOf(groupB, groupA, groupC), plan.groups)
        assertEquals(2, plan.added)
        assertEquals(0, plan.removed)
    }

    @Test
    fun `selected restore ignores a group that is absent from the snapshot`() {
        val plan = plan(
            current = listOf(groupA, groupB),
            snapshotGroups = listOf(groupARestored),
            scope = RestoreScope.Selected(setOf("a", "b")),
        )

        assertEquals(listOf(groupARestored, groupB), plan.groups)
        assertEquals(1, plan.replaced)
    }

    private fun plan(
        current: List<RuleGroup>,
        snapshotGroups: List<RuleGroup>,
        scope: RestoreScope,
    ): RestorePlan = RestorePlanner.plan(
        current = RuleDocument(groups = current),
        snapshot = BackupSnapshot(
            meta(snapshotGroups),
            RuleDocument(groups = snapshotGroups),
        ),
        scope = scope,
    )

    private fun meta(groups: List<RuleGroup>) = BackupMeta(
        id = "b-1",
        createdAt = 1_000L,
        reason = BackupReason.MANUAL,
        groups = groups.size,
        rules = groups.sumOf { it.rules.size },
        docHash = "hash",
    )

    private fun group(id: String, name: String, ruleCount: Int) = RuleGroup(
        id = id,
        name = name,
        description = "",
        rules = (1..ruleCount).map { index ->
            Rule(
                id = "rule-$id-$index",
                name = "rule",
                targetPackage = "com.example.app",
                capability = Capability.AppOp("CAMERA", OpMode.IGNORE),
            )
        },
    )
}
