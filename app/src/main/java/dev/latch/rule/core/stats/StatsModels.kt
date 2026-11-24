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
) {
    val runs: Int get() = applyRuns + verifyRuns + repairRuns
    val good: Int get() = applied + repaired
    val bad: Int get() = failed + drifted
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
