package dev.latch.rule.core.stats

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsAggregatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 24)

    @Test
    fun `batch events count as runs and rule events count outcomes`() {
        val events = listOf(
            event(today, 9, EventAction.APPLY),
            event(today, 9, EventAction.APPLY, ruleId = "r1", outcome = "APPLIED"),
            event(today, 9, EventAction.APPLY, ruleId = "r2", outcome = "FAILED"),
            event(today, 9, EventAction.VERIFY, ruleId = "r3", outcome = "DRIFTED"),
            event(today, 9, EventAction.VERIFY, ruleId = "r4", outcome = "APPLIED"),
            event(today.minusDays(1), 8, EventAction.VERIFY),
        )

        val days = StatsAggregator.byDay(events, zone)

        val day = days[today.toString()]!!
        assertEquals(1, day.applyRuns)
        assertEquals(0, day.verifyRuns)
        assertEquals(2, day.applied)
        assertEquals(1, day.failed)
        assertEquals(1, day.drifted)
        assertEquals(1, days[today.minusDays(1).toString()]!!.verifyRuns)
    }

    @Test
    fun `repair outcomes are counted separately`() {
        val events = listOf(
            event(today, 10, EventAction.REPAIR),
            event(today, 10, EventAction.REPAIR, ruleId = "r1", outcome = "APPLIED"),
            event(today, 10, EventAction.REPAIR, ruleId = "r2", outcome = "FAILED"),
        )

        val day = StatsAggregator.byDay(events, zone)[today.toString()]!!

        assertEquals(1, day.repairRuns)
        assertEquals(1, day.repaired)
        assertEquals(1, day.failed)
    }

    @Test
    fun `hours mark activity and issues`() {
        val events = listOf(
            event(today, 9, EventAction.APPLY),
            event(today, 10, EventAction.VERIFY, ruleId = "r1", outcome = "APPLIED"),
            event(today, 11, EventAction.VERIFY, ruleId = "r2", outcome = "DRIFTED"),
            event(today, 12, EventAction.APPLY, ruleId = "r3", outcome = "FAILED"),
        )

        val day = StatsAggregator.byDay(events, zone)[today.toString()]!!

        assertEquals(setOf(9, 10, 11, 12), day.activeHours)
        assertEquals(setOf(11, 12), day.badHours)
    }

    @Test
    fun `summary counts active days, streak and the seven day window`() {
        val document = StatsDocument(
            firstEventAt = at(today.minusDays(3), 8),
            days = mapOf(
                today.toString() to DayStats(today.toString(), verifyRuns = 1),
                today.minusDays(1).toString() to DayStats(today.minusDays(1).toString(), applyRuns = 1),
                today.minusDays(2).toString() to DayStats(today.minusDays(2).toString(), repairRuns = 1, repaired = 3),
            ),
        )

        val snapshot = StatsAggregator.summarize(document, today, zone)

        assertEquals(3, snapshot.activeDays)
        assertEquals(3, snapshot.streakDays)
        assertEquals(4, snapshot.totalDays)
        assertEquals(7, snapshot.recentDays.size)
        assertEquals(today.toString(), snapshot.recentDays.last().date)
        assertEquals(3, snapshot.repaired)
    }

    @Test
    fun `a quiet today does not break the streak`() {
        val document = StatsDocument(
            days = mapOf(
                today.minusDays(1).toString() to DayStats(today.minusDays(1).toString(), verifyRuns = 1),
                today.minusDays(2).toString() to DayStats(today.minusDays(2).toString(), verifyRuns = 1),
            ),
        )

        val snapshot = StatsAggregator.summarize(document, today, zone)

        assertEquals(2, snapshot.streakDays)
        assertEquals(0, snapshot.recentDays.last().runs)
    }

    @Test
    fun `repair success rate ignores attempts of zero`() {
        val empty = StatsAggregator.summarize(StatsDocument(), today, zone)
        assertEquals(null, empty.repairSuccessRate)

        val withAttempts = StatsAggregator.summarize(
            StatsDocument(days = mapOf(today.toString() to DayStats(today.toString(), repaired = 3, failed = 1))),
            today,
            zone,
        )
        assertEquals(75, withAttempts.repairSuccessRate)
    }

    private fun event(
        date: LocalDate,
        hour: Int,
        action: EventAction,
        ruleId: String? = null,
        outcome: String? = null,
    ) = LatchEvent(
        level = EventLevel.INFO,
        source = EventSource.UI,
        action = action,
        timestamp = at(date, hour),
        ruleId = ruleId,
        outcome = outcome,
    )

    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
}
