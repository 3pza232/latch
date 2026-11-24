package dev.latch.rule.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BackupSlotsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 24)
    private val now = at(today, 18, 43)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun meta(id: String, timestamp: Long, reason: BackupReason) = BackupMeta(
        id = id,
        createdAt = timestamp,
        reason = reason,
        groups = 1,
        rules = 1,
        docHash = id,
    )

    @Test
    fun `the hourly slot uses a snapshot captured in the current hour`() {
        val metas = listOf(
            meta("current", at(today, 18, 5), BackupReason.HOURLY),
            meta("previous", at(today, 17, 5), BackupReason.HOURLY),
        )

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("current", points.hourly?.id)
    }

    @Test
    fun `the hourly slot falls back to the newest earlier hourly snapshot`() {
        val metas = listOf(
            meta("earlier", at(today, 15, 5), BackupReason.HOURLY),
            meta("day", at(today, 0, 2), BackupReason.DAILY),
        )

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("earlier", points.hourly?.id)
    }

    @Test
    fun `daily slots map to the start of their day`() {
        val metas = listOf(today, today.minusDays(3), today.minusDays(7), today.minusDays(30))
            .map { meta("day-$it", at(it, 0, 5), BackupReason.DAILY) }

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("day-$today", points.oneDay?.id)
        assertEquals("day-${today.minusDays(3)}", points.threeDays?.id)
        assertEquals("day-${today.minusDays(7)}", points.sevenDays?.id)
        assertEquals("day-${today.minusDays(30)}", points.thirtyDays?.id)
    }

    @Test
    fun `a missing day keeps the previous daily snapshot in the slot`() {
        val metas = listOf(
            meta("yesterday", at(today.minusDays(1), 0, 5), BackupReason.DAILY),
            meta("older", at(today.minusDays(2), 0, 5), BackupReason.DAILY),
        )

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("yesterday", points.oneDay?.id)
        // Neither snapshot is old enough for the 3 day slot.
        assertNull(points.threeDays?.id)
    }

    @Test
    fun `an older snapshot is held until a newer day replaces it`() {
        val metas = listOf(meta("held", at(today.minusDays(40), 0, 5), BackupReason.DAILY))

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("held", points.thirtyDays?.id)
    }

    @Test
    fun `an existing snapshot is used when no boundary snapshot exists`() {
        val metas = listOf(meta("change", at(today, 18, 20), BackupReason.AUTO_CHANGE))

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("change", points.hourly?.id)
        assertEquals("change", points.oneDay?.id)
        assertNull(points.threeDays)
        assertNull(points.sevenDays)
        assertNull(points.thirtyDays)
    }

    @Test
    fun `a daily slot falls back to an older day when the target day has no daily`() {
        val metas = listOf(meta("change", at(today.minusDays(4), 12, 0), BackupReason.AUTO_CHANGE))

        val points = BackupSlots.select(metas, now, zone)

        assertEquals("change", points.threeDays?.id)
    }
}
