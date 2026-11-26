package dev.latch.rule.data

import dev.latch.rule.core.stats.DayStats
import dev.latch.rule.core.stats.StatsDocument
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Persistence for aggregated statistics. */
interface StatsStore {
    fun load(): StatsDocument
    fun save(document: StatsDocument)
    fun clear()
}

/**
 * Stores cumulative day buckets so statistics survive log pruning.
 * Day keys are local dates, written as-is to avoid timezone drift in history.
 */
class FileStatsStore(private val directory: File) : StatsStore {

    private val file: File
        get() = File(directory, FILE_NAME)

    override fun load(): StatsDocument = runCatching {
        if (!file.exists()) return@runCatching StatsDocument()
        val json = JSONObject(file.readText())
        val daysJson = json.optJSONObject("days") ?: JSONObject()
        val days = buildMap {
            daysJson.keys().forEach { date ->
                val item = daysJson.getJSONObject(date)
                put(
                    date,
                    DayStats(
                        date = date,
                        applyRuns = item.optInt("applyRuns"),
                        verifyRuns = item.optInt("verifyRuns"),
                        repairRuns = item.optInt("repairRuns"),
                        applied = item.optInt("applied"),
                        repaired = item.optInt("repaired"),
                        failed = item.optInt("failed"),
                        drifted = item.optInt("drifted"),
                        activeHours = item.optJSONArray("activeHours").toIntSet(),
                        badHours = item.optJSONArray("badHours").toIntSet(),
                    ),
                )
            }
        }
        StatsDocument(
            firstEventAt = json.optLongOrNull("firstEventAt"),
            resetAt = json.optLongOrNull("resetAt"),
            days = days,
        )
    }.getOrDefault(StatsDocument())

    override fun save(document: StatsDocument) {
        directory.mkdirs()
        val target = file
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeText(
            JSONObject().apply {
                put("version", 1)
                document.firstEventAt?.let { put("firstEventAt", it) }
                document.resetAt?.let { put("resetAt", it) }
                put(
                    "days",
                    JSONObject().apply {
                        document.days.forEach { (date, day) ->
                            put(
                                date,
                                JSONObject()
                                    .put("applyRuns", day.applyRuns)
                                    .put("verifyRuns", day.verifyRuns)
                                    .put("repairRuns", day.repairRuns)
                                    .put("applied", day.applied)
                                    .put("repaired", day.repaired)
                                    .put("failed", day.failed)
                                    .put("drifted", day.drifted)
                                    .put("activeHours", JSONArray(day.activeHours.sorted()))
                                    .put("badHours", JSONArray(day.badHours.sorted())),
                            )
                        }
                    },
                )
            }.toString(2),
        )
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONArray?.toIntSet(): Set<Int> = this?.let { array ->
        buildSet { for (i in 0 until array.length()) add(array.getInt(i)) }
    }.orEmpty()

    private companion object {
        const val FILE_NAME = "stats.json"
    }
}
