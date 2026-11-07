package dev.latch.rule.data

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical JSON form of stored rules. This is also the import/export format,
 * so it is versioned and must stay backward compatible.
 */
object RuleCodec {

    const val VERSION = 1

    fun encode(groups: List<RuleGroup>): String = JSONObject().apply {
        put("version", VERSION)
        put("groups", JSONArray().apply { groups.forEach { put(encodeGroup(it)) } })
    }.toString(2)

    fun decode(text: String): List<RuleGroup> {
        val root = JSONObject(text)
        val array = root.optJSONArray("groups") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                add(decodeGroup(array.getJSONObject(i)))
            }
        }
    }

    private fun encodeGroup(group: RuleGroup) = JSONObject().apply {
        put("id", group.id)
        put("name", group.name)
        put("description", group.description)
        put("enabled", group.enabled)
        put("rules", JSONArray().apply { group.rules.forEach { put(encodeRule(it)) } })
    }

    private fun decodeGroup(json: JSONObject) = RuleGroup(
        id = json.getString("id"),
        name = json.optString("name", json.getString("id")),
        description = json.optString("description", ""),
        enabled = json.optBoolean("enabled", true),
        rules = json.optJSONArray("rules")?.let { array ->
            buildList { for (i in 0 until array.length()) add(decodeRule(array.getJSONObject(i))) }
        }.orEmpty(),
    )

    fun encodeRule(rule: Rule) = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("targetPackage", rule.targetPackage)
        put("enabled", rule.enabled)
        put("capability", encodeCapability(rule.capability))
    }

    fun decodeRule(json: JSONObject) = Rule(
        id = json.optString("id"),
        name = json.optString("name", json.optString("id")),
        targetPackage = json.getString("targetPackage"),
        enabled = json.optBoolean("enabled", true),
        capability = decodeCapability(json.getJSONObject("capability")),
    )

    private fun encodeCapability(capability: Capability) = JSONObject().apply {
        when (capability) {
            is Capability.AppOp -> {
                put("type", "appOp")
                put("op", capability.op)
                put("mode", capability.mode.wire)
                put("scope", capability.scope.name.lowercase())
            }
            is Capability.PermissionState -> {
                put("type", "permissionState")
                put("permission", capability.permission)
                put("mode", capability.mode.wire)
            }
            Capability.ForceStop -> put("type", "forceStop")
            is Capability.SetEnabled -> {
                put("type", "setEnabled")
                put("enabled", capability.enabled)
            }
        }
    }

    private fun decodeCapability(json: JSONObject): Capability = when (json.getString("type")) {
        "appOp" -> Capability.AppOp(
            op = json.getString("op"),
            mode = OpMode.entries.firstOrNull { it.wire == json.optString("mode") } ?: OpMode.IGNORE,
            scope = if (json.optString("scope") == "package") OpScope.PACKAGE else OpScope.UID,
        )
        "permissionState" -> Capability.PermissionState(
            permission = json.getString("permission"),
            mode = PermissionMode.entries.firstOrNull { it.wire == json.optString("mode") } ?: PermissionMode.ALLOW,
        )
        // Legacy formats: treat old grant/revoke rules as allow/deny states.
        "revokePermission" -> Capability.PermissionState(
            permission = json.getString("permission"),
            mode = PermissionMode.DENY,
        )
        "grantPermission" -> Capability.PermissionState(
            permission = json.getString("permission"),
            mode = PermissionMode.ALLOW,
        )
        "forceStop" -> Capability.ForceStop
        "setEnabled" -> Capability.SetEnabled(json.optBoolean("enabled", false))
        else -> error("unknown capability: ${json.getString("type")}")
    }
}
