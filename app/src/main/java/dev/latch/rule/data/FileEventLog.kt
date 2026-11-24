package dev.latch.rule.data

import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.LatchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Append-only NDJSON log, one file per local day.
 *
 * Writes are serialised so concurrent callers cannot interleave partial lines. A
 * write failure is swallowed: losing a log line must never break rule application.
 */
class FileEventLog(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : EventLog {

    private val mutex = Mutex()

    override suspend fun record(event: LatchEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                directory.mkdirs()
                fileFor(event.timestamp).appendText(EventLogCodec.encode(event) + "\n")
            }
        }
        Unit
    }

    /**
     * Reads events newest first, stopping at [limit].
     * A partially written trailing line is skipped rather than failing the whole read.
     */
    suspend fun read(since: Long = 0L, until: Long = Long.MAX_VALUE, limit: Int = 500): List<LatchEvent> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    logFiles()
                        .asReversed()
                        .flatMap { file ->
                            file.readLines().asReversed().mapNotNull(EventLogCodec::decode)
                        }
                        .filter { it.timestamp in since..until }
                        .take(limit)
                }.getOrDefault(emptyList())
            }
        }

    /** Deletes whole day files older than [keepDays]. */
    suspend fun prune(keepDays: Int): Int = withContext(Dispatchers.IO) {
        if (keepDays <= 0) return@withContext 0
        mutex.withLock {
            val cutoff = today().minusDays(keepDays.toLong())
            var removed = 0
            logFiles().forEach { file ->
                val date = dateOf(file) ?: return@forEach
                if (date.isBefore(cutoff) && runCatching { file.delete() }.getOrDefault(false)) {
                    removed++
                }
            }
            removed
        }
    }

    private fun logFiles(): List<File> =
        directory.listFiles { file -> file.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun fileFor(timestamp: Long): File =
        File(directory, "${dateOf(timestamp)}$SUFFIX")

    private fun dateOf(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun dateOf(file: File): LocalDate? = runCatching {
        LocalDate.parse(file.name.removeSuffix(SUFFIX))
    }.getOrNull()

    private fun today(): LocalDate = dateOf(clock())

    private companion object {
        const val SUFFIX = ".ndjson"
    }
}
