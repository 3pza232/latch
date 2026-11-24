package dev.latch.rule.domain

import dev.latch.rule.core.stats.StatsAggregator
import dev.latch.rule.core.stats.StatsDocument
import dev.latch.rule.core.stats.StatsSnapshot
import dev.latch.rule.data.FileEventLog
import dev.latch.rule.data.StatsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Folds the event log into the persisted day buckets and renders the summary.
 *
 * Refreshing overwrites days still covered by logs and keeps days whose logs were
 * pruned, so "active days" can exceed the log retention window.
 */
class StatsService(
    private val eventLog: FileEventLog,
    private val store: StatsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun refresh(): StatsSnapshot {
        val existing = withContext(Dispatchers.IO) { store.load() }
        val events = eventLog.query(limit = MAX_EVENTS)
        val computed = StatsAggregator.byDay(events, zone)
        val firstEventAt = listOfNotNull(existing.firstEventAt, events.minOfOrNull { it.timestamp })
            .minOrNull()
        val updated = existing.copy(
            firstEventAt = firstEventAt,
            days = existing.days + computed,
        )
        withContext(Dispatchers.IO) { store.save(updated) }
        return StatsAggregator.summarize(updated, today(), zone)
    }

    /** Reads the persisted aggregate without touching the log. */
    fun current(): StatsSnapshot = StatsAggregator.summarize(store.load(), today(), zone)

    suspend fun reset() {
        withContext(Dispatchers.IO) {
            store.clear()
            eventLog.clear()
        }
    }

    private fun today(): LocalDate =
        Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    private companion object {
        const val MAX_EVENTS = 1_000_000
    }
}
