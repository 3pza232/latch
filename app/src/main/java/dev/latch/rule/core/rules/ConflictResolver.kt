package dev.latch.rule.core.rules

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument

/** An existing rule that would be overridden by the rule being saved. */
data class RuleConflict(
    val existingRuleId: String,
    val existingRuleName: String,
    val existingGroupId: String,
    val existingGroupName: String,
    val summary: String,
)

/**
 * Detects rules that declare different values for the same system fact, and derives
 * which rule is currently suppressed by a user-confirmed override.
 *
 * Two rules conflict when they target the same package with the same capability key
 * and ask for different values. Identical declarations are duplicates, not conflicts.
 * `forceStop` is a one-shot action with no persistent fact, so it never conflicts.
 */
object ConflictResolver {

    fun conflicts(
        document: RuleDocument,
        incoming: Rule,
        groupEnabled: Boolean,
        uidLookup: UidLookup = UidLookup.None,
    ): List<RuleConflict> {
        if (!incoming.enabled || !groupEnabled) return emptyList()
        return buildList {
            document.groups.forEach { group ->
                if (!group.enabled) return@forEach
                group.rules.forEach { existing ->
                    if (existing.id == incoming.id || !existing.enabled) return@forEach
                    val summary = conflictReason(existing, incoming, uidLookup) ?: return@forEach
                    add(
                        RuleConflict(
                            existingRuleId = existing.id,
                            existingRuleName = existing.name,
                            existingGroupId = group.id,
                            existingGroupName = group.name,
                            summary = summary,
                        ),
                    )
                }
            }
        }
    }

    /** Human-readable reason when the two rules contradict each other, otherwise null. */
    fun conflictReason(
        a: Rule,
        b: Rule,
        uidLookup: UidLookup = UidLookup.None,
    ): String? {
        if (a.id == b.id) return null
        if (a.targetPackage != b.targetPackage) {
            return crossPackageReason(a, b, uidLookup)
        }
        val keyA = key(a) ?: return null
        val keyB = key(b) ?: return null
        if (keyA != keyB) return null
        if (value(a) == value(b)) return null
        return keyA.describe()
    }

    /**
     * A UID-scoped AppOp applies to every package sharing that uid, so a rule on one
     * package can contradict a package-scoped rule on another. Permission and enabled
     * facts stay package-local and never overlap this way.
     */
    private fun crossPackageReason(a: Rule, b: Rule, uidLookup: UidLookup): String? {
        val opA = a.capability as? Capability.AppOp ?: return null
        val opB = b.capability as? Capability.AppOp ?: return null
        if (opA.op != opB.op) return null
        if (opA.scope == OpScope.PACKAGE && opB.scope == OpScope.PACKAGE) return null
        if (opA.mode.wire == opB.mode.wire) return null
        val uidA = uidLookup.uidOf(a.targetPackage) ?: return null
        val uidB = uidLookup.uidOf(b.targetPackage) ?: return null
        if (uidA != uidB) return null
        return "共享 UID 上的 AppOps ${opA.op}"
    }

    /**
     * Maps each suppressed rule to the rule that overrides it.
     * An override is dormant while either side is inactive or the two no longer conflict.
     */
    fun suppressedBy(
        document: RuleDocument,
        uidLookup: UidLookup = UidLookup.None,
    ): Map<String, String> {
        val latest = mutableMapOf<String, Long>()
        val result = mutableMapOf<String, String>()
        document.overrides.forEach { override ->
            val loser = document.findRule(override.loserRuleId) ?: return@forEach
            val winner = document.findRule(override.winnerRuleId) ?: return@forEach
            if (!document.isActive(loser.id) || !document.isActive(winner.id)) return@forEach
            if (conflictReason(loser, winner, uidLookup) == null) return@forEach
            if (override.createdAt >= (latest[loser.id] ?: Long.MIN_VALUE)) {
                latest[loser.id] = override.createdAt
                result[loser.id] = winner.id
            }
        }
        return result
    }

    private data class Key(val type: String, val target: String, val detail: String) {
        fun describe(): String = when (type) {
            "appOp" -> "AppOps $detail"
            "permission" -> "权限 ${detail.substringAfterLast('.')}"
            "enabled" -> "应用启停"
            else -> detail
        }
    }

    private fun key(rule: Rule): Key? = when (val capability = rule.capability) {
        is Capability.AppOp -> Key("appOp", rule.targetPackage, capability.op)
        is Capability.PermissionState -> Key("permission", rule.targetPackage, capability.permission)
        is Capability.SetEnabled -> Key("enabled", rule.targetPackage, "")
        Capability.ForceStop -> null
    }

    private fun value(rule: Rule): String? = when (val capability = rule.capability) {
        is Capability.AppOp -> capability.mode.wire
        is Capability.PermissionState -> capability.mode.wire
        is Capability.SetEnabled -> capability.enabled.toString()
        Capability.ForceStop -> null
    }
}
