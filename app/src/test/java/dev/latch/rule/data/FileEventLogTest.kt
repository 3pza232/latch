package dev.latch.rule.data

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileEventLogTest {

    @get:JvmRule
    val folder = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDate.of(2026, 11, 24)

    private fun timestampOf(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + 60_000

    private fun log(clock: () -> Long) = FileEventLog(File(folder.root, "logs"), clock, zone)

    @Test
    fun `recorded events are read back newest first`() = runBlocking {
        val log = log { timestampOf(now) }
        log.record(event(timestampOf(now), "first"))
        log.record(event(timestampOf(now) + 1, "second"))

        val events = log.query()

        assertEquals(listOf("second", "first"), events.map { it.message })
    }

    @Test
    fun `events are grouped into one file per day`() = runBlocking {
        val log = log { timestampOf(now) }
        log.record(event(timestampOf(now), "today"))
        log.record(event(timestampOf(now.minusDays(1)), "yesterday"))

        val files = File(folder.root, "logs").listFiles()!!.map { it.name }.sorted()

        assertEquals(listOf("2026-11-23.ndjson", "2026-11-24.ndjson"), files)
    }

    @Test
    fun `prune deletes only files older than the retention window`() = runBlocking {
        val log = log { timestampOf(now) }
        log.record(event(timestampOf(now), "today"))
        log.record(event(timestampOf(now.minusDays(10)), "old"))

        val removed = log.prune(keepDays = 7)

        assertEquals(1, removed)
        assertEquals(listOf("today"), log.query().map { it.message })
    }

    @Test
    fun `a partially written line does not hide the rest of the log`() = runBlocking {
        val log = log { timestampOf(now) }
        log.record(event(timestampOf(now), "good"))
        File(folder.root, "logs/2026-11-24.ndjson").appendText("{ broken\n")

        val events = log.query()

        assertEquals(listOf("good"), events.map { it.message })
    }

    @Test
    fun `reads can be narrowed by time`() = runBlocking {
        val log = log { timestampOf(now) }
        log.record(event(timestampOf(now), "recent"))
        log.record(event(timestampOf(now.minusDays(2)), "older"))

        val events = log.query(since = timestampOf(now.minusDays(1)))

        assertEquals(listOf("recent"), events.map { it.message })
    }

    @Test
    fun `codec round trips every field and truncates long output`() {
        val event = event(timestampOf(now), "msg").copy(
            groupId = "g", ruleId = "r", ruleName = "相机", targetPackage = "com.example.app",
            outcome = "FAILED", expected = "ignore", actual = "allow", errorCode = "POLICY_BLOCKED",
            command = "cmd appops set", output = "x".repeat(10_000), durationMs = 42, detail = "d",
        )

        val decoded = EventLogCodec.decode(EventLogCodec.encode(event))!!

        assertEquals(event.copy(output = "x".repeat(2_000)), decoded)
        assertTrue(decoded.output!!.length == 2_000)
    }

    @Test
    fun `unreadable lines decode to null`() {
        assertNull(EventLogCodec.decode("{ not json"))
    }

    private fun event(timestamp: Long, message: String) = LatchEvent(
        level = EventLevel.INFO,
        source = EventSource.UI,
        action = EventAction.APPLY,
        timestamp = timestamp,
        message = message,
    )
}
