package dev.latch.rule.engine

import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot

/**
 * Runs verification over the persisted rule document and stores the outcome.
 * Shared by the UI and background workers so both report the same state.
 */
class VerifyService(
    private val ruleStore: RuleStore,
    private val resultStore: ResultStore,
    private val engine: RuleEngine,
) {

    data class RepairReport(
        val checked: Int,
        val repaired: Int,
        val remainingDrifted: Int,
        val remainingFailed: Int,
    )

    suspend fun verifyAll(): VerificationSnapshot {
        val groups = ruleStore.load().filter { it.enabled }
        val checkedAt = System.currentTimeMillis()
        val results = buildMap {
            groups.forEach { group ->
                group.rules.filter { it.enabled }.forEach { rule ->
                    put(rule.id, engine.verify(rule).toStored(checkedAt))
                }
            }
        }
        val snapshot = VerificationSnapshot(results, checkedAt)
        resultStore.save(snapshot)
        return snapshot
    }

    /** Verifies, re-applies drifted rules, then reports what is still broken. */
    suspend fun verifyAndRepair(): RepairReport {
        val groups = ruleStore.load().filter { it.enabled }
        val rules = groups.flatMap { it.rules }.filter { it.enabled }
        val checkedAt = System.currentTimeMillis()

        val initial = rules.associate { it.id to engine.verify(it).toStored(checkedAt) }
        val driftedRules = rules.filter { initial[it.id]?.state == dev.latch.rule.core.model.RuleState.DRIFTED }
        val repaired = driftedRules.associate { rule ->
            rule.id to engine.apply(rule).toStored(System.currentTimeMillis())
        }
        val merged = initial + repaired
        resultStore.save(VerificationSnapshot(merged, System.currentTimeMillis()))

        return RepairReport(
            checked = rules.size,
            repaired = repaired.values.count { it.state == dev.latch.rule.core.model.RuleState.APPLIED },
            remainingDrifted = merged.values.count { it.state == dev.latch.rule.core.model.RuleState.DRIFTED },
            remainingFailed = merged.values.count { it.state == dev.latch.rule.core.model.RuleState.FAILED },
        )
    }
}

fun RuleResult.toStored(checkedAt: Long): StoredResult = StoredResult(
    ruleId = ruleId,
    state = state,
    expected = expected,
    actual = actual,
    message = error?.message ?: message,
    checkedAt = checkedAt,
)
