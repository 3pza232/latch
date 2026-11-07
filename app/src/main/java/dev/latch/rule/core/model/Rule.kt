package dev.latch.rule.core.model

/** How an app operation is applied: to the whole uid or to the package only. */
enum class OpScope { UID, PACKAGE }

enum class OpMode(val wire: String) {
    IGNORE("ignore"),
    ALLOW("allow"),
    DEFAULT("default"),
}

enum class PermissionMode(val wire: String, val label: String) {
    ALLOW("allow", "允许"),
    WHILE_IN_USE("foreground", "仅使用时允许"),
    ASK("ask", "每次询问"),
    DENY("deny", "禁止"),
    APPROXIMATE("approximate", "大致位置"),
    SELECTED("selected", "受限访问"),
}

/** A single control that can be applied to a target package. */
sealed interface Capability {

    data class AppOp(
        val op: String,
        val mode: OpMode,
        val scope: OpScope = OpScope.UID,
    ) : Capability

    data class PermissionState(
        val permission: String,
        val mode: PermissionMode,
    ) : Capability

    data object ForceStop : Capability

    data class SetEnabled(val enabled: Boolean) : Capability
}

/** An atomic rule: one target, one capability, one expected outcome. */
data class Rule(
    val id: String,
    val name: String,
    val targetPackage: String,
    val capability: Capability,
    val enabled: Boolean = true,
)

/** A named bundle of rules that can be applied and verified together. */
data class RuleGroup(
    val id: String,
    val name: String,
    val description: String,
    val rules: List<Rule>,
    val enabled: Boolean = true,
) {
    /** Group state is derived from the worst rule state, never stored. */
    fun withRules(rules: List<Rule>): RuleGroup = copy(rules = rules)
}

enum class RuleState {
    DISABLED,
    PENDING,
    APPLIED,
    DRIFTED,
    FAILED,
}
