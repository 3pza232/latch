package dev.latch.rule.engine

import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.result.LatchError
import dev.latch.rule.privilege.PrivilegeBroker

data class RuleResult(
    val ruleId: String,
    val state: RuleState,
    val expected: String,
    val actual: String,
    val error: LatchError? = null,
    val message: String? = null,
)

/**
 * Applies rules and verifies them by reading system state back.
 * A rule is only reported APPLIED when the observed state matches its expectation.
 */
class RuleEngine(private val broker: PrivilegeBroker) {

    suspend fun apply(rule: Rule): RuleResult {
        val spec = runCatching { CapabilityCommands.of(rule.targetPackage, rule.capability) }
            .getOrElse { return invalid(rule, it.message.orEmpty()) }

        val applied = broker.execute(spec.applyCommand)
        if (!applied.ok) {
            return RuleResult(
                ruleId = rule.id,
                state = RuleState.FAILED,
                expected = spec.expected,
                actual = "-",
                error = LatchError.fromShell(applied),
            )
        }
        return verifyAgainst(rule, spec).let { result ->
            if (result.state == RuleState.DRIFTED) result.copy(message = NOT_ENFORCED_HINT) else result
        }
    }

    suspend fun verify(rule: Rule): RuleResult {
        val spec = runCatching { CapabilityCommands.of(rule.targetPackage, rule.capability) }
            .getOrElse { return invalid(rule, it.message.orEmpty()) }
        return verifyAgainst(rule, spec)
    }

    private suspend fun verifyAgainst(rule: Rule, spec: CommandSpec): RuleResult {
        val result = broker.execute(spec.verifyCommand)
        if (!result.ok) {
            return RuleResult(
                ruleId = rule.id,
                state = RuleState.FAILED,
                expected = spec.expected,
                actual = "-",
                error = LatchError.fromShell(result),
            )
        }
        val actual = spec.parser.extract(result.stdout) ?: "unknown"
        // "default" means "clear the override"; the effective value is system-chosen.
        val expected = if (spec.expected == "default") actual else spec.expected
        val state = if (actual == expected) RuleState.APPLIED else RuleState.DRIFTED
        return RuleResult(
            ruleId = rule.id,
            state = state,
            expected = expected,
            actual = actual,
        )
    }

    private fun invalid(rule: Rule, message: String) = RuleResult(
        ruleId = rule.id,
        state = RuleState.FAILED,
        expected = "-",
        actual = "-",
        error = LatchError.invalidRule(message.ifBlank { "invalid rule" }),
    )

    private companion object {
        const val NOT_ENFORCED_HINT = "已下发但系统未接受：该应用或该操作可能受系统保护"
    }
}
