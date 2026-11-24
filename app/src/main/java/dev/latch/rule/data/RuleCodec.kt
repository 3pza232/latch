package dev.latch.rule.data

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical JSON form of stored rules. This is also the import/export format,
 * so it is versioned and keeps reading older documents.
 *
 * Version 2 adds root-level `overrides` (user-confirmed conflict decisions) and
 * `revision`. A version 1 document is read as a document without overrides.
 */
object RuleCodec {

    const val VERSION = 2

    fun encode(document: RuleDocument): String = JSONObject().apply {
        put("version", VERSION)
        put("revision", document.revision)
        put("groups", JSONArray().apply { document.groups.forEach { put(encodeGroup(it)) } })
        put("overrides", JSONArray().apply { document.overrides.forEach { put(encodeOverride(it)) } })
    }.toString(2)

    fun decode(text: String): RuleDocument {
        val root = JSONObject(text)
        val version = if (root.has("version")) root.getInt("version") else 1
        require(version <= VERSION) { "unsupported rule document version $version" }
        val array = root.optJSONArray("groups") ?: JSONArray()
        val groups = buildList {
            for (i in 0 until array.length()) {
                add(decodeGroup(array.getJSONObject(i)))
            }
        }
        val overridesJson = root.optJSONArray("overrides") ?: JSONArray()
        val overrides = buildList {
            for (i in 0 until overridesJson.length()) {
                add(decodeOverride(overridesJson.getJSONObject(i)))
            }
        }
        return RuleDocument(
            groups = groups,
            overrides = overrides,
            revision = root.optLong("revision", 0L),
        )
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

    private fun encodeOverride(override: RuleOverride) = JSONObject().apply {
        put("loserRuleId", override.loserRuleId)
        put("winnerRuleId", override.winnerRuleId)
        put("createdAt", override.createdAt)
        put("reason", override.reason)
    }

    private fun decodeOverride(json: JSONObject) = RuleOverride(
        loserRuleId = json.getString("loserRuleId"),
        winnerRuleId = json.getString("winnerRuleId"),
        createdAt = json.optLong("createdAt"),
        reason = json.optString("reason", "user-confirmed"),
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
            mode = PermissionMode.entries.firstOrNull { it.wire == json.optString("mode") } ?: PermissionMode.DENY,
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
