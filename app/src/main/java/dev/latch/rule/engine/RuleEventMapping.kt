package dev.latch.rule.engine

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleState

/** One rule outcome, with the commands and output that produced it. */
fun RuleResult.toEvent(
    source: EventSource,
    action: EventAction,
    group: RuleGroup?,
    rule: Rule,
): LatchEvent = LatchEvent(
    level = when (state) {
        RuleState.APPLIED -> EventLevel.INFO
        RuleState.DRIFTED -> EventLevel.WARN
        RuleState.FAILED -> EventLevel.ERROR
        else -> EventLevel.INFO
    },
    source = source,
    action = action,
    groupId = group?.id,
    groupName = group?.name,
    ruleId = rule.id,
    ruleName = rule.name,
    targetPackage = rule.targetPackage,
    outcome = state.name,
    expected = expected,
    actual = actual,
    errorCode = error?.code?.name,
    message = error?.message ?: message,
    command = commands.joinToString(" && ").takeIf { it.isNotBlank() },
    output = output,
)

/**
 * One line per batch run.
 *
 * Verification runs on a timer, so successful checks are summarised instead of being
 * written per rule; individual failures and drift are recorded separately.
 */
fun batchEvent(
    source: EventSource,
    action: EventAction,
    group: RuleGroup?,
    results: Collection<RuleResult>,
    durationMs: Long,
    extraDetail: String? = null,
): LatchEvent {
    val applied = results.count { it.state == RuleState.APPLIED }
    val drifted = results.count { it.state == RuleState.DRIFTED }
    val failed = results.count { it.state == RuleState.FAILED }
    val detail = buildString {
        append("checked=${results.size} applied=$applied drifted=$drifted failed=$failed")
        if (extraDetail != null) append(' ').append(extraDetail)
    }
    return LatchEvent(
        level = when {
            failed > 0 -> EventLevel.ERROR
            drifted > 0 -> EventLevel.WARN
            else -> EventLevel.INFO
        },
        source = source,
        action = action,
        groupId = group?.id,
        groupName = group?.name,
        outcome = when {
            results.isEmpty() -> "empty"
            failed > 0 -> "failed"
            drifted > 0 -> "drifted"
            else -> "ok"
        },
        detail = detail,
        durationMs = durationMs,
    )
}

/** True when this result deserves its own line rather than only a batch summary. */
fun RuleResult.needsDetail(): Boolean =
    state == RuleState.DRIFTED || state == RuleState.FAILED
