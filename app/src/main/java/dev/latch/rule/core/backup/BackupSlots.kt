package dev.latch.rule.core.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Snapshot picked for each quick restore point. Any entry may be null. */
data class RestorePoints(
    val hourly: BackupMeta? = null,
    val oneDay: BackupMeta? = null,
    val threeDays: BackupMeta? = null,
    val sevenDays: BackupMeta? = null,
    val thirtyDays: BackupMeta? = null,
)

/**
 * Chooses the snapshot each restore point refers to.
 *
 * A point is a slot, not "now minus N": the hourly slot belongs to the previous
 * clock hour (one hour of buffer), the daily slots belong to the start of the day
 * [daysAgo] days back. A slot takes the newest matching boundary snapshot, and
 * falls back to an older one when the target has no snapshot, so a gap never
 * empties the slot.
 */
object BackupSlots {

    fun select(metas: List<BackupMeta>, now: Long, zone: ZoneId): RestorePoints {
        val today = dateOf(now, zone)
        val targetHour = hourOf(now, zone).minusHours(1)
        val boundary = metas.filter {
            it.reason == BackupReason.HOURLY || it.reason == BackupReason.DAILY
        }

        val hourly = boundary
            .filter { hourOf(it.createdAt, zone) == targetHour }
            .maxByOrNull { it.createdAt }
            ?: boundary.filter { hourOf(it.createdAt, zone) <= targetHour }
                .maxByOrNull { it.createdAt }
            ?: boundary.maxByOrNull { it.createdAt }
            ?: metas.filter { it.createdAt <= now }.maxByOrNull { it.createdAt }

        fun dailyAt(daysAgo: Long): BackupMeta? {
            val target = today.minusDays(daysAgo)
            val atOrBefore = metas.filter { dateOf(it.createdAt, zone) <= target }
            return atOrBefore.filter { it.reason == BackupReason.DAILY }.maxByOrNull { it.createdAt }
                ?: atOrBefore.maxByOrNull { it.createdAt }
        }

        return RestorePoints(
            hourly = hourly,
            oneDay = dailyAt(0),
            threeDays = dailyAt(3),
            sevenDays = dailyAt(7),
            thirtyDays = dailyAt(30),
        )
    }

    private fun dateOf(timestamp: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun hourOf(timestamp: Long, zone: ZoneId) =
        Instant.ofEpochMilli(timestamp).atZone(zone).truncatedTo(ChronoUnit.HOURS)
}
