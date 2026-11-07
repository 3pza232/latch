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
}

fun RuleResult.toStored(checkedAt: Long): StoredResult = StoredResult(
    ruleId = ruleId,
    state = state,
    expected = expected,
    actual = actual,
    message = error?.message ?: message,
    checkedAt = checkedAt,
)
