package dev.latch.rule.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `snapshots from the last hour are all kept`() {
        val metas = listOf(
            meta("a", now - 10 * minute),
            meta("b", now - 40 * minute),
        )

        assertTrue(RetentionPolicy.toDelete(metas, now).isEmpty())
    }

    @Test
    fun `older snapshots are thinned to one per hour`() {
        val metas = listOf(
            meta("a", now - 3 * hour),
            meta("b", now - 3 * hour + 10 * minute),
            meta("c", now - 2 * hour),
            meta("latest", now - minute),
        )

        val doomed = RetentionPolicy.toDelete(metas, now)

        assertEquals(setOf("b"), doomed)
    }

    @Test
    fun `manual and migration snapshots are never deleted`() {
        val metas = listOf(
            meta("manual", now - 400 * day, BackupReason.MANUAL),
            meta("migration", now - 400 * day, BackupReason.PRE_MIGRATION),
            meta("auto", now - 450 * day),
        )

        val doomed = RetentionPolicy.toDelete(metas, now)

        assertTrue("manual" !in doomed)
        assertTrue("migration" !in doomed)
        assertTrue("auto" in doomed)
    }

    @Test
    fun `only the latest pre-restore snapshots are kept`() {
        val metas = (1..25).map {
            meta("pre-$it", now - it * day, BackupReason.PRE_RESTORE)
        }

        val doomed = RetentionPolicy.toDelete(metas, now)

        assertEquals(5, doomed.size)
        assertTrue("pre-25" in doomed)
        assertTrue("pre-1" !in doomed)
    }

    @Test
    fun `the newest snapshot is kept even when old`() {
        val metas = listOf(meta("only", now - 500 * day))

        assertTrue(RetentionPolicy.toDelete(metas, now).isEmpty())
    }

    @Test
    fun `the total is capped`() {
        val metas = (1..300).map { index ->
            meta("old-$index", now - index * 3 * hour)
        } + meta("latest", now - minute)

        val doomed = RetentionPolicy.toDelete(metas, now)

        assertTrue(doomed.size >= 300 - RetentionPolicy.MAX_SNAPSHOTS)
    }

    private fun meta(
        id: String,
        createdAt: Long,
        reason: BackupReason = BackupReason.AUTO_CHANGE,
    ) = BackupMeta(
        id = id,
        createdAt = createdAt,
        reason = reason,
        groups = 1,
        rules = 1,
        docHash = id,
    )
}
