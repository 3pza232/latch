package dev.latch.rule.core.log

/** Append-only destination for [LatchEvent]s. */
interface EventLog {

    suspend fun record(event: LatchEvent)

    /** Most recent events, newest first. */
    suspend fun recent(limit: Int = 200): List<LatchEvent> = emptyList()

    /** Removes every stored event. */
    suspend fun clear() = Unit

    /** Waits until every previously recorded event has been persisted. */
    suspend fun flush() = Unit

    companion object {
        /** Used by tests and by call sites that run without a log directory. */
        val Noop: EventLog = object : EventLog {
            override suspend fun record(event: LatchEvent) = Unit
        }
    }
}
