package dev.latch.rule.domain

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.BackupSnapshot
import dev.latch.rule.core.backup.RetentionPolicy
import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.data.BackupCodec
import dev.latch.rule.data.BackupStore
import dev.latch.rule.data.RuleLoadResult
import dev.latch.rule.data.RuleStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Creates and prunes snapshots of the rule document.
 *
 * A snapshot is taken after every document change (coalesced), before a restore,
 * and on demand. Identical content is not saved twice, so a no-op edit does not
 * produce a duplicate restore point.
 */
class BackupService(
    private val store: BackupStore,
    private val ruleStore: RuleStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventLog: EventLog = EventLog.Noop,
    private val autoCoalesceMs: Long = 30_000L,
) {

    suspend fun list(): List<BackupMeta> = withContext(Dispatchers.IO) { store.list() }

    suspend fun load(id: String): BackupSnapshot? = withContext(Dispatchers.IO) { store.load(id) }

    /** Returns null when there is nothing to record (no readable document, or no change). */
    suspend fun snapshotNow(reason: BackupReason): BackupMeta? = withContext(Dispatchers.IO) {
        val document = currentDocument() ?: return@withContext null
        val hash = BackupCodec.hash(document)
        val latest = store.list().firstOrNull()
        if (reason == BackupReason.AUTO_CHANGE && latest?.docHash == hash) return@withContext null

        val now = clock()
        val meta = BackupMeta(
            id = "b-$now-${UUID.randomUUID().toString().take(8)}",
            createdAt = now,
            reason = reason,
            groups = document.groups.size,
            rules = document.rules().size,
            docHash = hash,
        )
        store.save(BackupSnapshot(meta, document))
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = EventSource.SYSTEM,
                action = EventAction.BACKUP,
                outcome = "created",
                detail = "reason=${reason.wire} groups=${meta.groups} rules=${meta.rules}",
            ),
        )
        meta
    }

    /** Called after a document change; coalesces rapid edits and skips no-op writes. */
    suspend fun onDocumentChanged() {
        ensureBoundaries()
        val latest = withContext(Dispatchers.IO) {
            store.list().firstOrNull { it.reason == BackupReason.AUTO_CHANGE }
        }
        val recentAuto = latest != null && clock() - latest.createdAt < autoCoalesceMs
        if (recentAuto) return
        snapshotNow(BackupReason.AUTO_CHANGE)
    }

    /**
     * Ensures the current clock hour and local day have a boundary snapshot.
     *
     * The capture happens at the first activity inside the boundary, so its real
     * time is kept; the snapshot still belongs to that hour or day. Missing days
     * are never backfilled, which keeps the history honest.
     */
    suspend fun ensureBoundaries(): Int = withContext(Dispatchers.IO) {
        if (currentDocument() == null) return@withContext 0
        val zone = ZoneId.systemDefault()
        val now = clock()
        var created = 0

        var metas = store.list()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dailyExists = metas.any {
            it.reason == BackupReason.DAILY &&
                Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == today
        }
        if (!dailyExists && snapshotNow(BackupReason.DAILY) != null) {
            created++
            metas = store.list()
        }

        val hour = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val hourlyCovered = metas.any {
            (it.reason == BackupReason.HOURLY || it.reason == BackupReason.DAILY) &&
                Instant.ofEpochMilli(it.createdAt).atZone(zone)
                    .truncatedTo(ChronoUnit.HOURS) == hour
        }
        if (!hourlyCovered && snapshotNow(BackupReason.HOURLY) != null) {
            created++
        }
        created
    }

    /** Deletes the given snapshots; returns how many existed. */
    suspend fun delete(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        val removed = ids.count { id -> store.load(id) != null }
        ids.forEach(store::delete)
        if (removed > 0) {
            eventLog.record(
                LatchEvent(
                    level = EventLevel.INFO,
                    source = EventSource.SYSTEM,
                    action = EventAction.ROTATE,
                    outcome = "deleted",
                    detail = "backups=$removed",
                ),
            )
        }
        removed
    }

    suspend fun prune(): Int = withContext(Dispatchers.IO) {
        val metas = store.list()
        val doomed = RetentionPolicy.toDelete(metas)
        doomed.forEach(store::delete)
        if (doomed.isNotEmpty()) {
            eventLog.record(
                LatchEvent(
                    level = EventLevel.INFO,
                    source = EventSource.SYSTEM,
                    action = EventAction.ROTATE,
                    outcome = "pruned",
                    detail = "removed=$doomed.size remaining=${metas.size - doomed.size}",
                ),
            )
        }
        doomed.size
    }

    private fun currentDocument(): dev.latch.rule.core.model.RuleDocument? =
        (ruleStore.load() as? RuleLoadResult.Success)?.document
}
