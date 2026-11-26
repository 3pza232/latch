package dev.latch.rule.core.stats

/** Counters for one local day. */
data class DayStats(
    val date: String,
    val applyRuns: Int = 0,
    val verifyRuns: Int = 0,
    val repairRuns: Int = 0,
    val applied: Int = 0,
    val repaired: Int = 0,
    val failed: Int = 0,
    val drifted: Int = 0,
    /** Local hours (0-23) with any rule activity. */
    val activeHours: Set<Int> = emptySet(),
    /** Local hours (0-23) that contained a failure or drift. */
    val badHours: Set<Int> = emptySet(),
) {
    val runs: Int get() = applyRuns + verifyRuns + repairRuns
}

/** Persisted aggregate. Days whose logs were pruned stay here. */
data class StatsDocument(
    val firstEventAt: Long? = null,
    val resetAt: Long? = null,
    val days: Map<String, DayStats> = emptyMap(),
)

/** What the statistics panel renders. */
data class StatsSnapshot(
    val totalDays: Int = 0,
    val activeDays: Int = 0,
    val streakDays: Int = 0,
    val applyRuns: Int = 0,
    val verifyRuns: Int = 0,
    val repairRuns: Int = 0,
    val applied: Int = 0,
    val repaired: Int = 0,
    val failed: Int = 0,
    val drifted: Int = 0,
    val firstEventAt: Long? = null,
    /** Last seven local days, oldest first, gaps filled with zeros. */
    val recentDays: List<DayStats> = emptyList(),
) {
    val repairAttempts: Int get() = repaired + failed
    val repairSuccessRate: Int?
        get() = if (repairAttempts == 0) null else repaired * 100 / repairAttempts
}
