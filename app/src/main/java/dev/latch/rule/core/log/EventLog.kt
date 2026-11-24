package dev.latch.rule.core.log

/** Append-only destination for [LatchEvent]s. */
interface EventLog {

    suspend fun record(event: LatchEvent)

    /** Waits until every previously recorded event has been persisted. */
    suspend fun flush() = Unit

    companion object {
        /** Used by tests and by call sites that run without a log directory. */
        val Noop: EventLog = object : EventLog {
            override suspend fun record(event: LatchEvent) = Unit
        }
    }
}
