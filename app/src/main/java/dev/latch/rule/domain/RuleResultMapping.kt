package dev.latch.rule.domain

import dev.latch.rule.data.StoredResult
import dev.latch.rule.engine.RuleResult

/** Compact form persisted in `results.json`, which keeps the last known state. */
fun RuleResult.toStored(checkedAt: Long): StoredResult = StoredResult(
    ruleId = ruleId,
    state = state,
    expected = expected,
    actual = actual,
    message = error?.message ?: message,
    checkedAt = checkedAt,
)
