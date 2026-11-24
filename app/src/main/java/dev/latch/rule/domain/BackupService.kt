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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

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
        val groups = currentGroups() ?: return@withContext null
        val hash = BackupCodec.hash(groups)
        val latest = store.list().firstOrNull()
        if (reason == BackupReason.AUTO_CHANGE && latest?.docHash == hash) return@withContext null

        val now = clock()
        val meta = BackupMeta(
            id = "b-$now-${UUID.randomUUID().toString().take(8)}",
            createdAt = now,
            reason = reason,
            groups = groups.size,
            rules = groups.sumOf { it.rules.size },
            docHash = hash,
        )
        store.save(BackupSnapshot(meta, groups))
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
        val latest = withContext(Dispatchers.IO) { store.list().firstOrNull() }
        val recentAuto = latest != null &&
            latest.reason == BackupReason.AUTO_CHANGE &&
            clock() - latest.createdAt < autoCoalesceMs
        if (recentAuto) return
        snapshotNow(BackupReason.AUTO_CHANGE)
    }

    /** Records the current state once when no snapshot exists yet. */
    suspend fun snapshotOnStartup() {
        if (withContext(Dispatchers.IO) { store.list() }.isNotEmpty()) return
        val groups = withContext(Dispatchers.IO) { currentGroups() }.orEmpty()
        if (groups.isEmpty()) return
        snapshotNow(BackupReason.AUTO_CHANGE)
    }

    suspend fun prune(): Int = withContext(Dispatchers.IO) {
        val metas = store.list()
        val doomed = RetentionPolicy.toDelete(metas, clock())
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

    private fun currentGroups(): List<dev.latch.rule.core.model.RuleGroup>? =
        (ruleStore.load() as? RuleLoadResult.Success)?.groups
}
