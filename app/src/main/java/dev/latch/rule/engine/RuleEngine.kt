package dev.latch.rule.engine

import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.result.LatchError
import dev.latch.rule.core.result.LatchErrorCode
import dev.latch.rule.privilege.PrivilegeBroker
import dev.latch.rule.privilege.ShellResult

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

        // Every step runs even if an earlier one failed: a partly applied capability is
        // still closer to the intent than stopping halfway, and verification decides.
        var failedStep: Pair<Int, ShellResult>? = null
        spec.applyCommands.forEachIndexed { index, command ->
            val result = broker.execute(command)
            if (!result.ok && failedStep == null) failedStep = (index + 1) to result
        }

        val verified = verifyAgainst(rule, spec)
        if (verified.state == RuleState.APPLIED) return verified

        val failure = failedStep
            ?: return if (verified.state == RuleState.DRIFTED) {
                verified.copy(message = NOT_ENFORCED_HINT)
            } else {
                verified
            }
        val error = LatchError.fromShell(failure.second)
        return verified.copy(
            state = RuleState.FAILED,
            error = error.copy(message = "步骤 ${failure.first} 失败：${error.message}"),
        )
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
        val actual = spec.parser.extract(result.stdout)
            ?: return RuleResult(
                ruleId = rule.id,
                state = RuleState.FAILED,
                expected = spec.expected,
                actual = UNKNOWN,
                error = LatchError(LatchErrorCode.PARSE_FAILED, "无法解析系统回读结果"),
            )
        val state = if (matches(spec.expected, actual)) RuleState.APPLIED else RuleState.DRIFTED
        return RuleResult(
            ruleId = rule.id,
            state = state,
            expected = spec.expected,
            actual = actual,
        )
    }

    /**
     * A `default` rule clears the override, so any `default(...)` reading satisfies it.
     * For explicit modes, the same reading also satisfies the rule because the effective
     * value already matches even though the override lives at the system-default layer.
     */
    private fun matches(expected: String, actual: String): Boolean = when {
        expected == "default" -> actual.startsWith("default")
        actual == expected -> true
        else -> actual == "default($expected)"
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
        const val UNKNOWN = "unknown"
    }
}
