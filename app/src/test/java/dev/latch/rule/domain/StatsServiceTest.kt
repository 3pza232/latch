package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.data.FileEventLog
import dev.latch.rule.data.FileStatsStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsServiceTest {

    @get:JvmRule
    val folder = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 24)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private lateinit var log: FileEventLog
    private lateinit var service: StatsService

    @Before
    fun setUp() {
        log = FileEventLog(File(folder.root, "logs"), clock = { now }, zone = zone)
        val store = FileStatsStore(File(folder.root, "stats"))
        service = StatsService(log, store, clock = { now }, zone = zone)
    }

    @Test
    fun `refresh aggregates the log into day counters`() = runBlocking {
        log.record(event(today, EventAction.VERIFY))
        log.record(event(today, EventAction.APPLY, ruleId = "r1", outcome = "APPLIED"))

        val snapshot = service.refresh()

        assertEquals(1, snapshot.verifyRuns)
        assertEquals(1, snapshot.applied)
        assertEquals(1, snapshot.activeDays)
        assertEquals(1, snapshot.streakDays)
    }

    @Test
    fun `days survive after their logs are pruned`() = runBlocking {
        log.record(event(today, EventAction.VERIFY))
        service.refresh()

        log.clear()
        val afterPrune = service.refresh()

        assertEquals(1, afterPrune.verifyRuns)
        assertEquals(1, afterPrune.activeDays)
    }

    @Test
    fun `reset clears both counters and logs`() = runBlocking {
        log.record(event(today, EventAction.VERIFY))
        service.refresh()

        service.reset()

        val snapshot = service.refresh()
        assertEquals(0, snapshot.verifyRuns)
        assertEquals(0, snapshot.activeDays)
        assertNull(snapshot.firstEventAt)
    }

    @Test
    fun `current reads persisted counters without touching the log`() = runBlocking {
        log.record(event(today, EventAction.VERIFY))
        service.refresh()

        assertEquals(1, service.current().verifyRuns)
    }

    private fun event(
        date: LocalDate,
        action: EventAction,
        ruleId: String? = null,
        outcome: String? = null,
    ) = LatchEvent(
        level = EventLevel.INFO,
        source = EventSource.UI,
        action = action,
        timestamp = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
        ruleId = ruleId,
        outcome = outcome,
    )
}
