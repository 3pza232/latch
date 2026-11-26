package dev.latch.rule.core.backup

/**
 * Bounded retention for every snapshot kind.
 *
 * Boundary snapshots guarantee the quick restore points exist: the current and
 * previous hour, and the newest 31 daily snapshots cover the 1 hour and 1/3/7/30 day slots even across
 * gaps. Change snapshots are kept for undoing recent edits. Manual snapshots and
 * migration snapshots are never pruned; pre-restore snapshots keep their latest
 * [MAX_PRE_RESTORE] entries.
 */
object RetentionPolicy {

    const val MAX_CHANGES = 100
    const val MAX_PRE_RESTORE = 20
    const val MAX_HOURLY = 2
    const val MAX_DAILY = 31

    fun toDelete(metas: List<BackupMeta>): Set<String> {
        if (metas.isEmpty()) return emptySet()
        val sorted = metas.sortedBy { it.createdAt }
        val keep = linkedSetOf<String>()

        sorted.filter { it.reason == BackupReason.MANUAL || it.reason == BackupReason.PRE_MIGRATION }
            .forEach { keep += it.id }
        sorted.filter { it.reason == BackupReason.PRE_RESTORE }
            .takeLast(MAX_PRE_RESTORE)
            .forEach { keep += it.id }
        sorted.filter { it.reason == BackupReason.AUTO_CHANGE }
            .takeLast(MAX_CHANGES)
            .forEach { keep += it.id }
        sorted.filter { it.reason == BackupReason.HOURLY }
            .takeLast(MAX_HOURLY)
            .forEach { keep += it.id }
        sorted.filter { it.reason == BackupReason.DAILY }
            .takeLast(MAX_DAILY)
            .forEach { keep += it.id }

        // Keep the newest snapshot even if it uses an unexpected reason.
        sorted.lastOrNull()?.let { keep += it.id }

        return metas.map { it.id }.toSet() - keep
    }
}
