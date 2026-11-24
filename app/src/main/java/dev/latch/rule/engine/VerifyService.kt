package dev.latch.rule.engine

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.rules.ConflictResolver
import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleLoadResult
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot

/**
 * Runs verification over the persisted rule document and stores the outcome.
 * Shared by the UI and background workers so both report the same state.
 *
 * Successful checks are summarised into one event per run; only drifted and failed
 * rules get their own event, which keeps a periodic verification from flooding the log.
 */
class VerifyService(
    private val ruleStore: RuleStore,
    private val resultStore: ResultStore,
    private val engine: RuleEngine,
    private val eventLog: EventLog = EventLog.Noop,
) {

    data class RepairReport(
        val checked: Int,
        val repaired: Int,
        val remainingDrifted: Int,
        val remainingFailed: Int,
    )

    suspend fun verifyAll(source: EventSource = EventSource.UI): VerificationSnapshot {
        val started = System.currentTimeMillis()
        val checkedAt = System.currentTimeMillis()
        val outcomes = enabledEntries().map { (group, rule) -> Triple(group, rule, engine.verify(rule)) }

        outcomes.filter { it.third.needsDetail() }
            .forEach { (group, rule, result) -> eventLog.record(result.toEvent(source, EventAction.VERIFY, group, rule)) }
        eventLog.record(batchEvent(source, EventAction.VERIFY, null, outcomes.map { it.third }, System.currentTimeMillis() - started))

        val snapshot = VerificationSnapshot(
            results = outcomes.associate { (_, rule, result) -> rule.id to result.toStored(checkedAt) },
            verifiedAt = checkedAt,
        )
        resultStore.save(snapshot)
        return snapshot
    }

    /** Verifies, re-applies drifted rules, then reports what is still broken. */
    suspend fun verifyAndRepair(source: EventSource = EventSource.UI): RepairReport {
        val started = System.currentTimeMillis()
        val entries = enabledEntries()
        val checkedAt = System.currentTimeMillis()

        val initial = entries.map { (group, rule) -> Triple(group, rule, engine.verify(rule)) }
        initial.filter { it.third.needsDetail() }
            .forEach { (group, rule, result) -> eventLog.record(result.toEvent(source, EventAction.VERIFY, group, rule)) }

        val repaired = initial
            .filter { it.third.state == RuleState.DRIFTED }
            .map { (group, rule, _) -> Triple(group, rule, engine.apply(rule)) }
        repaired.forEach { (group, rule, result) ->
            eventLog.record(result.toEvent(source, EventAction.REPAIR, group, rule))
        }

        val storedAt = System.currentTimeMillis()
        val merged = (initial + repaired).associate { (_, rule, result) -> rule.id to result.toStored(storedAt) }
        resultStore.save(VerificationSnapshot(merged, storedAt))

        val allResults = (initial + repaired).map { it.third }
        val repairedNow = repaired.count { it.third.state == RuleState.APPLIED }
        eventLog.record(
            batchEvent(
                source = source,
                action = EventAction.REPAIR,
                group = null,
                results = allResults,
                durationMs = System.currentTimeMillis() - started,
                extraDetail = "repaired=$repairedNow",
            ),
        )

        return RepairReport(
            checked = entries.size,
            repaired = repairedNow,
            remainingDrifted = merged.values.count { it.state == RuleState.DRIFTED },
            remainingFailed = merged.values.count { it.state == RuleState.FAILED },
        )
    }

    /**
     * Active rules, excluding rules covered by an override: verifying them would
     * report drift for a state the user intentionally replaced.
     * An unreadable document must not look like "there are no rules".
     */
    private fun enabledEntries(): List<Pair<RuleGroup, Rule>> {
        val document = (ruleStore.load() as? RuleLoadResult.Success)?.document ?: return emptyList()
        val suppressed = ConflictResolver.suppressedBy(document)
        return document.groups.filter { it.enabled }.flatMap { group ->
            group.rules.filter { it.enabled && it.id !in suppressed }.map { group to it }
        }
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
