package dev.latch.rule.engine

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.PermissionMode

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
            is Capability.PermissionState -> permission(targetPackage, capability)
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

    private fun permission(pkg: String, capability: Capability.PermissionState): CommandSpec {
        val permission = capability.permission
        require(PERMISSION_PATTERN.matches(permission)) { "invalid permission: $permission" }
        val op = appOpFor(permission)
        val verifyBase = when (capability.mode) {
            PermissionMode.APPROXIMATE, PermissionMode.SELECTED -> "dumpsys package $pkg"
            else -> if (op == null) {
                "dumpsys package $pkg | grep -F '$permission: granted' || true"
            } else {
                "dumpsys package $pkg | grep -F '$permission: granted' || true; cmd appops get $pkg $op"
            }
        }
        val (apply, verifyExtra) = when (capability.mode) {
            PermissionMode.ALLOW -> "pm grant --user 0 $pkg $permission" to ""
            PermissionMode.DENY -> buildString {
                append("pm revoke --user 0 $pkg $permission")
                if (op != null) append("; cmd appops set --uid $pkg $op ignore")
            } to ""
            PermissionMode.WHILE_IN_USE -> buildString {
                append("pm grant --user 0 $pkg $permission")
                if (op != null) append("; cmd appops set --uid $pkg $op foreground")
            } to ""
            PermissionMode.APPROXIMATE -> buildString {
                append("pm grant --user 0 $pkg android.permission.ACCESS_COARSE_LOCATION")
                append("; pm revoke --user 0 $pkg android.permission.ACCESS_FINE_LOCATION")
            } to ""
            PermissionMode.SELECTED -> buildString {
                append("pm grant --user 0 $pkg android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                append("; pm revoke --user 0 $pkg android.permission.READ_MEDIA_IMAGES")
                append("; pm revoke --user 0 $pkg android.permission.READ_MEDIA_VIDEO")
                append("; pm set-permission-flags --user 0 $pkg android.permission.READ_MEDIA_VISUAL_USER_SELECTED user-set")
                append("; pm set-permission-flags --user 0 $pkg android.permission.READ_MEDIA_IMAGES user-set")
                append("; pm set-permission-flags --user 0 $pkg android.permission.READ_MEDIA_VIDEO user-set")
                append("; cmd appops set --uid $pkg READ_MEDIA_VISUAL_USER_SELECTED ignore")
                append("; cmd appops set --uid $pkg READ_MEDIA_IMAGES ignore")
                append("; cmd appops set --uid $pkg READ_MEDIA_VIDEO ignore")
            } to ""
        }
        return CommandSpec(
            applyCommand = apply,
            verifyCommand = verifyBase + verifyExtra,
            expected = capability.mode.wire,
            parser = PermissionStateParser(permission, capability.mode, op),
        )
    }

    private fun appOpFor(permission: String): String? = when (permission) {
        "android.permission.CAMERA" -> "CAMERA"
        "android.permission.RECORD_AUDIO" -> "RECORD_AUDIO"
        "android.permission.ACCESS_FINE_LOCATION" -> "FINE_LOCATION"
        "android.permission.ACCESS_COARSE_LOCATION" -> "COARSE_LOCATION"
        "android.permission.READ_CONTACTS" -> "READ_CONTACTS"
        "android.permission.WRITE_CONTACTS" -> "WRITE_CONTACTS"
        "android.permission.READ_SMS" -> "READ_SMS"
        "android.permission.SEND_SMS" -> "SEND_SMS"
        "android.permission.RECEIVE_SMS" -> "RECEIVE_SMS"
        "android.permission.READ_CALL_LOG" -> "READ_CALL_LOG"
        "android.permission.WRITE_CALL_LOG" -> "WRITE_CALL_LOG"
        "android.permission.READ_PHONE_STATE" -> "READ_PHONE_STATE"
        "android.permission.READ_PHONE_NUMBERS" -> "READ_PHONE_NUMBERS"
        "android.permission.READ_CALENDAR" -> "READ_CALENDAR"
        "android.permission.WRITE_CALENDAR" -> "WRITE_CALENDAR"
        "android.permission.READ_MEDIA_IMAGES" -> "READ_MEDIA_IMAGES"
        "android.permission.READ_MEDIA_VIDEO" -> "READ_MEDIA_VIDEO"
        "android.permission.READ_MEDIA_AUDIO" -> "READ_MEDIA_AUDIO"
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" -> "READ_MEDIA_VISUAL_USER_SELECTED"
        "android.permission.READ_EXTERNAL_STORAGE" -> "READ_EXTERNAL_STORAGE"
        "android.permission.WRITE_EXTERNAL_STORAGE" -> "WRITE_EXTERNAL_STORAGE"
        "android.permission.BLUETOOTH_CONNECT" -> "BLUETOOTH_CONNECT"
        "android.permission.BLUETOOTH_SCAN" -> "BLUETOOTH_SCAN"
        "android.permission.NEARBY_WIFI_DEVICES" -> "NEARBY_WIFI_DEVICES"
        "android.permission.POST_NOTIFICATIONS" -> "POST_NOTIFICATION"
        else -> null
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
