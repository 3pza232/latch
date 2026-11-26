package dev.latch.rule.core.stats

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.LatchEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Turns the event log into per-day counters and a summary.
 *
 * Only batch events (no rule id) count as runs; rule-level events count outcomes.
 * That keeps a verification run with 75 applied rules from looking like 75 runs.
 */
object StatsAggregator {

    fun byDay(events: List<LatchEvent>, zone: ZoneId): Map<String, DayStats> {
        val result = mutableMapOf<String, DayStats>()
        events.forEach { event ->
            val date = dateOf(event.timestamp, zone)
            val hour = hourOf(event.timestamp, zone)
            var day = result[date] ?: DayStats(date)

            if (event.action in ACTIVE_ACTIONS) {
                day = day.copy(activeHours = day.activeHours + hour)
            }
            if (event.ruleId != null && event.outcome in ISSUE_OUTCOMES) {
                day = day.copy(badHours = day.badHours + hour)
            }

            day = when (event.action) {
                EventAction.APPLY -> when {
                    event.ruleId == null -> day.copy(applyRuns = day.applyRuns + 1)
                    event.outcome == APPLIED -> day.copy(applied = day.applied + 1)
                    else -> day
                }
                EventAction.VERIFY -> when {
                    event.ruleId == null -> day.copy(verifyRuns = day.verifyRuns + 1)
                    event.outcome == APPLIED -> day.copy(applied = day.applied + 1)
                    else -> day
                }
                EventAction.REPAIR -> when {
                    event.ruleId == null -> day.copy(repairRuns = day.repairRuns + 1)
                    event.outcome == APPLIED -> day.copy(repaired = day.repaired + 1)
                    else -> day
                }
                else -> day
            }

            if (event.ruleId != null) {
                day = when (event.outcome) {
                    "FAILED" -> day.copy(failed = day.failed + 1)
                    "DRIFTED" -> day.copy(drifted = day.drifted + 1)
                    else -> day
                }
            }
            result[date] = day
        }
        return result
    }

    fun summarize(
        document: StatsDocument,
        today: LocalDate,
        zone: ZoneId,
    ): StatsSnapshot {
        val days = document.days
        val firstDate = document.firstEventAt?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val totalDays = firstDate?.let { ChronoUnit.DAYS.between(it, today).toInt() + 1 } ?: 0

        return StatsSnapshot(
            totalDays = totalDays.coerceAtLeast(0),
            activeDays = days.values.count { it.runs > 0 },
            streakDays = streak(days, today),
            applyRuns = days.values.sumOf { it.applyRuns },
            verifyRuns = days.values.sumOf { it.verifyRuns },
            repairRuns = days.values.sumOf { it.repairRuns },
            applied = days.values.sumOf { it.applied },
            repaired = days.values.sumOf { it.repaired },
            failed = days.values.sumOf { it.failed },
            drifted = days.values.sumOf { it.drifted },
            firstEventAt = document.firstEventAt,
            recentDays = recent(days, today),
        )
    }

    /** Consecutive days with activity, ending today (or yesterday if today is quiet). */
    private fun streak(days: Map<String, DayStats>, today: LocalDate): Int {
        var cursor = today
        if ((days[cursor.toString()]?.runs ?: 0) == 0) cursor = cursor.minusDays(1)
        var count = 0
        while ((days[cursor.toString()]?.runs ?: 0) > 0) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private fun recent(days: Map<String, DayStats>, today: LocalDate): List<DayStats> =
        (DAYS_IN_CHART - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            days[date] ?: DayStats(date)
        }

    private fun dateOf(timestamp: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString()

    private fun hourOf(timestamp: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(timestamp).atZone(zone).hour

    const val DAYS_IN_CHART = 7
    private const val APPLIED = "APPLIED"
    private val ACTIVE_ACTIONS = setOf(
        EventAction.APPLY,
        EventAction.VERIFY,
        EventAction.REPAIR,
        EventAction.SKIP,
    )
    private val ISSUE_OUTCOMES = setOf("FAILED", "DRIFTED")
}
