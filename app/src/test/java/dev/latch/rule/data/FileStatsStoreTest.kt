package dev.latch.rule.data

import dev.latch.rule.core.stats.DayStats
import dev.latch.rule.core.stats.StatsDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStatsStoreTest {

    @get:JvmRule
    val folder = TemporaryFolder()

    @Test
    fun `day buckets round trip including hourly health`() {
        val store = FileStatsStore(File(folder.root, "stats"))
        val document = StatsDocument(
            firstEventAt = 1_000L,
            days = mapOf(
                "2026-11-26" to DayStats(
                    date = "2026-11-26",
                    applyRuns = 2,
                    verifyRuns = 3,
                    repaired = 1,
                    failed = 1,
                    activeHours = setOf(9, 10, 11),
                    badHours = setOf(11),
                ),
            ),
        )

        store.save(document)

        assertEquals(document, store.load())
    }

    @Test
    fun `clear removes the document`() {
        val store = FileStatsStore(File(folder.root, "stats"))
        store.save(StatsDocument(days = mapOf("2026-11-26" to DayStats("2026-11-26"))))

        store.clear()

        assertEquals(StatsDocument(), store.load())
    }
}
