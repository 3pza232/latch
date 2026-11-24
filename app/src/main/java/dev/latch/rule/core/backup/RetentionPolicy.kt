package dev.latch.rule.core.backup

/**
 * Time-based thinning of automatic snapshots.
 *
 * Recent history stays dense so "1 hour ago" is accurate; older history is
 * downsampled to hours, days and weeks, which still covers the 1/3/7/30 day
 * restore points without keeping thousands of files.
 *
 * Manual snapshots, migration snapshots and the latest snapshot are always kept;
 * pre-restore snapshots are kept up to [MAX_PRE_RESTORE].
 */
object RetentionPolicy {

    const val MAX_SNAPSHOTS = 200
    const val MAX_PRE_RESTORE = 20

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun toDelete(metas: List<BackupMeta>, now: Long): Set<String> {
        if (metas.isEmpty()) return emptySet()
        val sorted = metas.sortedBy { it.createdAt }
        val keep = linkedSetOf<String>()

        sorted.filter { it.reason == BackupReason.MANUAL || it.reason == BackupReason.PRE_MIGRATION }
            .forEach { keep += it.id }

        sorted.filter { it.reason == BackupReason.PRE_RESTORE }
            .takeLast(MAX_PRE_RESTORE)
            .forEach { keep += it.id }

        sorted.lastOrNull()?.let { keep += it.id }

        // One snapshot per bucket, keeping the newest entry of each bucket.
        val buckets = mutableMapOf<String, BackupMeta>()
        sorted.filter { it.reason == BackupReason.AUTO_CHANGE }.forEach { meta ->
            when (val key = bucketKey(now - meta.createdAt)) {
                null -> keep += meta.id
                DROP -> Unit
                else -> buckets[key] = meta
            }
        }
        keep += buckets.values.map { it.id }

        // Hard cap on total size: drop the oldest automatic entries first.
        if (keep.size > MAX_SNAPSHOTS) {
            val automatic = sorted.filter { it.id in keep && it.reason == BackupReason.AUTO_CHANGE }
            val excess = keep.size - MAX_SNAPSHOTS
            automatic.take(excess.coerceAtLeast(0)).forEach { keep -= it.id }
        }

        return metas.map { it.id }.toSet() - keep
    }

    /** Null means "young enough to keep every snapshot". */
    private fun bucketKey(age: Long): String? = when {
        age < HOUR -> null
        age < DAY -> "h-${age / HOUR}"
        age < 7 * DAY -> "6h-${age / (6 * HOUR)}"
        age < 30 * DAY -> "d-${age / DAY}"
        age < 365 * DAY -> "w-${age / (7 * DAY)}"
        else -> DROP
    }

    private const val DROP = "drop"
}
