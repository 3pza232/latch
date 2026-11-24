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
        // `check-permission` is the precise per-user answer but is not available on older
        // releases, so the runtime-permission line is kept as a fallback in the same call.
        val verify = buildString {
            append("dumpsys package check-permission $permission $pkg 0; ")
            append("dumpsys package $pkg | grep -F '$permission: granted' || true")
            if (op != null) append("; cmd appops get --uid $pkg $op")
        }
        // Granting only changes the runtime permission; an AppOps override written by an
        // earlier deny rule survives it and keeps the app blocked, so clear it here.
        val apply = when (capability.mode) {
            PermissionMode.ALLOW -> buildList {
                add("pm grant --user 0 $pkg $permission")
                if (op != null) add("cmd appops set --uid $pkg $op default")
            }
            PermissionMode.DENY -> buildList {
                add("pm revoke --user 0 $pkg $permission")
                if (op != null) add("cmd appops set --uid $pkg $op ignore")
            }
        }
        return CommandSpec(
            applyCommands = apply,
            verifyCommand = verify,
            expected = capability.mode.wire,
            parser = PermissionStateParser(permission, op, capability.mode),
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
        verifyCommand = "ps -A -o PID,NAME,ARGS",
        expected = "stopped",
        parser = ProcessStoppedParser(pkg),
    )

    /**
     * `dumpsys package` reports `enabled=0` for a normally enabled app, so the numeric
     * field cannot distinguish enabled from disabled. The package lists can.
     */
    private fun setEnabled(pkg: String, enabled: Boolean): CommandSpec = CommandSpec(
        applyCommand = if (enabled) "pm enable $pkg" else "pm disable-user --user 0 $pkg",
        verifyCommand = buildString {
            append("pm list packages --user 0 -e $pkg; ")
            append("echo ${EnabledStateParser.SECTION_SEPARATOR}; ")
            append("pm list packages --user 0 -d $pkg")
        },
        expected = if (enabled) "enabled" else "disabled",
        parser = EnabledStateParser(pkg),
    )
}
