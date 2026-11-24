package dev.latch.rule.core.rules

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.Rule

/**
 * Refuses rules that could make the device unusable: stopping or disabling Latch,
 * the privilege bridge, SystemUI, the Android framework or the current launcher.
 *
 * Only destructive capabilities are blocked; a permission or AppOps rule against
 * these packages is still allowed, because it cannot lock the user out.
 */
class TargetPolicy(private val protectedPackages: Set<String>) {

    fun blockReason(rule: Rule): String? {
        if (!isDestructive(rule.capability)) return null
        if (rule.targetPackage !in protectedPackages) return null
        return "受保护目标，已跳过"
    }

    fun isProtected(packageName: String): Boolean = packageName in protectedPackages

    private fun isDestructive(capability: Capability): Boolean = when (capability) {
        is Capability.SetEnabled -> !capability.enabled
        Capability.ForceStop -> true
        else -> false
    }

    companion object {
        /** Packages that always exist and whose removal would break the app or the shell. */
        val ALWAYS_PROTECTED: Set<String> = setOf(
            "dev.latch.rule",
            "moe.shizuku.privileged.api",
            "android",
            "com.android.systemui",
        )
    }
}
