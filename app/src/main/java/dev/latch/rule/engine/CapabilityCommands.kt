package dev.latch.rule.engine

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpScope

/**
 * Maps capabilities to shell commands. This is the only place where command strings are built,
 * and every interpolated token is validated first.
 */
object CapabilityCommands {

    private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val OP_PATTERN = Regex("^[A-Z][A-Z0-9_]*$")
    private val PERMISSION_PATTERN = Regex("^android\\.permission\\.[A-Z0-9_]+$")

    fun of(targetPackage: String, capability: Capability): CommandSpec {
        require(PACKAGE_PATTERN.matches(targetPackage)) { "invalid package name: $targetPackage" }
        return when (capability) {
            is Capability.AppOp -> appOp(targetPackage, capability)
            is Capability.RevokePermission -> revokePermission(targetPackage, capability.permission)
            Capability.ForceStop -> forceStop(targetPackage)
            is Capability.SetEnabled -> setEnabled(targetPackage, capability.enabled)
        }
    }

    private fun appOp(pkg: String, capability: Capability.AppOp): CommandSpec {
        require(OP_PATTERN.matches(capability.op)) { "invalid op: ${capability.op}" }
        val uidFlag = if (capability.scope == OpScope.UID) " --uid" else ""
        return CommandSpec(
            applyCommand = "cmd appops set$uidFlag $pkg ${capability.op} ${capability.mode.wire}",
            verifyCommand = "cmd appops get $pkg ${capability.op}",
            expected = capability.mode.wire,
            parser = AppOpParser(capability.op),
        )
    }

    private fun revokePermission(pkg: String, permission: String): CommandSpec {
        require(PERMISSION_PATTERN.matches(permission)) { "invalid permission: $permission" }
        return CommandSpec(
            applyCommand = "pm revoke --user 0 $pkg $permission",
            verifyCommand = "dumpsys package $pkg | grep -F '$permission: granted' || true",
            expected = "granted=false",
            parser = PermissionGrantParser(permission),
        )
    }

    private fun forceStop(pkg: String): CommandSpec = CommandSpec(
        applyCommand = "am force-stop $pkg",
        verifyCommand = "ps -A -o NAME | grep -F '$pkg' || true",
        expected = "stopped",
        parser = ProcessStoppedParser,
    )

    private fun setEnabled(pkg: String, enabled: Boolean): CommandSpec = CommandSpec(
        applyCommand = if (enabled) "pm enable $pkg" else "pm disable-user --user 0 $pkg",
        verifyCommand = "dumpsys package $pkg | grep -E 'User 0:.*enabled=' || true",
        expected = if (enabled) "enabled" else "disabled",
        parser = EnabledStateParser,
    )
}
