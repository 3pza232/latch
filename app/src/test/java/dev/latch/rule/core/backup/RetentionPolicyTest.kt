package dev.latch.rule.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {

    private fun meta(id: String, createdAt: Long, reason: BackupReason) = BackupMeta(
        id = id,
        createdAt = createdAt,
        reason = reason,
        groups = 1,
        rules = 1,
        docHash = id,
    )

    @Test
    fun `only the newest change snapshots are kept`() {
        val metas = (1..120).map { meta("change-$it", it.toLong(), BackupReason.AUTO_CHANGE) }

        val doomed = RetentionPolicy.toDelete(metas)

        assertEquals(20, doomed.size)
        assertTrue("change-1" in doomed)
        assertTrue("change-21" !in doomed)
    }

    @Test
    fun `only the newest hourly snapshot is kept`() {
        val metas = (1..3).map { meta("hour-$it", it.toLong(), BackupReason.HOURLY) }

        val doomed = RetentionPolicy.toDelete(metas)

        assertEquals(setOf("hour-1", "hour-2"), doomed)
    }

    @Test
    fun `the newest 31 daily snapshots are kept`() {
        val metas = (1..35).map { meta("day-$it", it.toLong(), BackupReason.DAILY) }

        val doomed = RetentionPolicy.toDelete(metas)

        assertEquals(4, doomed.size)
        assertTrue("day-4" in doomed)
        assertTrue("day-5" !in doomed)
    }

    @Test
    fun `manual and migration snapshots are never deleted`() {
        val metas = listOf(
            meta("manual", 1, BackupReason.MANUAL),
            meta("migration", 2, BackupReason.PRE_MIGRATION),
        )

        assertTrue(RetentionPolicy.toDelete(metas).isEmpty())
    }

    @Test
    fun `only the latest pre-restore snapshots are kept`() {
        val metas = (1..25).map { meta("pre-$it", it.toLong(), BackupReason.PRE_RESTORE) }

        val doomed = RetentionPolicy.toDelete(metas)

        assertEquals(5, doomed.size)
        assertTrue("pre-1" in doomed)
        assertTrue("pre-21" !in doomed)
    }

    @Test
    fun `the newest snapshot is kept whatever its reason`() {
        val metas = listOf(meta("only", 1, BackupReason.AUTO_CHANGE))

        assertTrue(RetentionPolicy.toDelete(metas).isEmpty())
    }
}
