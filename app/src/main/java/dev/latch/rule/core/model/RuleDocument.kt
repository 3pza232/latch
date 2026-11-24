package dev.latch.rule.core.model

/**
 * A user-confirmed decision that [winnerRuleId] takes precedence over [loserRuleId].
 *
 * The relation is stored because "the newer rule wins" cannot express the other
 * choice: when the user keeps an existing rule, the new rule is the loser even
 * though it was created later.
 */
data class RuleOverride(
    val loserRuleId: String,
    val winnerRuleId: String,
    val createdAt: Long,
    val reason: String = "user-confirmed",
)

/** The whole persisted configuration: groups, override decisions and a write counter. */
data class RuleDocument(
    val groups: List<RuleGroup> = emptyList(),
    val overrides: List<RuleOverride> = emptyList(),
    val revision: Long = 0,
) {
    fun rules(): List<Rule> = groups.flatMap { it.rules }

    fun findRule(ruleId: String): Rule? = groups.asSequence()
        .flatMap { it.rules.asSequence() }
        .firstOrNull { it.id == ruleId }

    fun groupOf(ruleId: String): RuleGroup? = groups.firstOrNull { group ->
        group.rules.any { it.id == ruleId }
    }

    fun isActive(ruleId: String): Boolean {
        val group = groupOf(ruleId) ?: return false
        val rule = group.rules.firstOrNull { it.id == ruleId } ?: return false
        return group.enabled && rule.enabled
    }

    fun withGroups(groups: List<RuleGroup>): RuleDocument =
        copy(groups = groups, revision = revision + 1)

    fun withOverrides(overrides: List<RuleOverride>): RuleDocument =
        copy(overrides = overrides, revision = revision + 1)
}
