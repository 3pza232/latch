package dev.latch.rule.gateway

import dev.latch.rule.core.model.RuleState
import dev.latch.rule.data.RuleCodec
import dev.latch.rule.domain.RuleCoordinator
import dev.latch.rule.privilege.PrivilegeBroker
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Minimal JSON REST surface for the paired agent. Pairing is the only unauthenticated route. */
class GatewayServer(
    private val gateway: AgentGateway,
    private val coordinator: RuleCoordinator,
    private val privilege: PrivilegeBroker,
    private val versionName: String,
) : NanoHTTPD(AgentGateway.PORT) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        return try {
            when {
                uri == "/pair" && method == Method.POST -> handlePair(session)
                (uri == "/" || uri == "/meta" || uri == "/api/meta") && method == Method.GET -> meta()
                !gateway.isAuthorized(session.headers["authorization"]) ->
                    json(Response.Status.UNAUTHORIZED, error("unauthorized"))
                (uri == "/api" || uri == "/api/docs") && method == Method.GET -> apiDocs()
                uri == "/api/state" && method == Method.GET -> state()
                uri == "/api/rules" && method == Method.GET -> rules()
                uri == "/api/import" && method == Method.POST -> importRules(session)
                uri == "/api/rules" && method == Method.POST -> upsertRule(session)
                uri.startsWith("/api/rules/") -> ruleAction(session, uri.removePrefix("/api/rules/"))
                uri.endsWith("/apply") && uri.startsWith("/api/groups/") && method == Method.POST ->
                    applyGroup(uri)
                uri.endsWith("/enabled") && uri.startsWith("/api/groups/") && method == Method.POST ->
                    setGroupEnabled(session, uri)
                uri == "/api/verify" && method == Method.POST -> verify()
                uri == "/api/repair" && method == Method.POST -> repair()
                uri == "/api/ping" -> json(Response.Status.OK, JSONObject().put("ok", true))
                else -> json(Response.Status.NOT_FOUND, error("not found"))
            }
        } catch (t: Throwable) {
            json(Response.Status.INTERNAL_ERROR, error(t.message ?: "internal error"))
        }
    }

    /** Public discovery document: how to pair and where the full API description lives. */
    private fun meta(): Response = json(
        Response.Status.OK,
        JSONObject()
            .put("name", "Latch")
            .put("version", versionName)
            .put("description", "Local Android rule manager (AppOps / permissions / app state) reachable over LAN")
            .put(
                "auth",
                JSONObject()
                    .put(
                        "pair",
                        JSONObject()
                            .put("method", "POST")
                            .put("path", "/pair")
                            .put("body", JSONObject().put("code", "<6-digit pairing code>"))
                            .put("returns", JSONObject().put("token", "<bearer token>")),
                    )
                    .put("header", "Authorization: Bearer <token>")
                    .put("note", "The pairing code is shown in Latch Settings -> 局域网连接"),
            )
            .put("docs", JSONObject().put("method", "GET").put("path", "/api"))
            .put("state", JSONObject().put("method", "GET").put("path", "/api/state")),
    )

    /** Authenticated, self-describing API reference so a fresh client can learn the contract. */
    private fun apiDocs(): Response = json(
        Response.Status.OK,
        JSONObject()
            .put("name", "Latch API")
            .put("version", versionName)
            .put(
                "endpoints",
                JSONArray()
                    .put(endpoint("GET", "/api/state", null, "Gateway/Shizuku status, group counts and summary"))
                    .put(endpoint("GET", "/api/rules", null, "Full rule document (same shape as exported JSON)"))
                    .put(endpoint("POST", "/api/import", JSONObject().put("version", 1).put("groups", "<rule groups>"), "Replace/merge rules from a full rule document"))
                    .put(
                        endpoint(
                            "POST",
                            "/api/rules",
                            JSONObject().put("groupId", "<groupId>").put("rule", RULE_SHAPE)
                                .put("hint", "rule.id is optional; server generates one when absent"),
                            "Create or update a rule in a group",
                        ),
                    )
                    .put(endpoint("POST", "/api/rules/{id}/enabled", JSONObject().put("enabled", true), "Enable/disable one rule"))
                    .put(endpoint("DELETE", "/api/rules/{id}", null, "Delete one rule"))
                    .put(endpoint("POST", "/api/groups/{id}/enabled", JSONObject().put("enabled", true), "Enable/disable a whole group; enabling applies it"))
                    .put(endpoint("POST", "/api/groups/{id}/apply", null, "Apply all enabled rules in a group and verify"))
                    .put(endpoint("POST", "/api/verify", null, "Verify every rule and refresh stored results"))
                    .put(endpoint("POST", "/api/repair", null, "Verify, re-apply drifted rules, and report what remains broken")),
            )
            .put(
                "capabilities",
                JSONObject()
                    .put("editRules", true)
                    .put("toggleRules", true)
                    .put("toggleGroups", true)
                    .put("refreshState", true)
                    .put("applyAndVerify", true),
            )
            .put("ruleShape", RULE_SHAPE)
            .put(
                "examples",
                JSONArray()
                    .put("GET /api/state")
                    .put("POST /api/verify")
                    .put("POST /api/groups/<groupId>/apply")
                    .put("POST /api/rules/<ruleId>/enabled  {\"enabled\":false}")
                    .put("POST /api/rules  {\"groupId\":\"<groupId>\",\"rule\":{\"name\":\"短信\",\"targetPackage\":\"com.example.app\",\"enabled\":true,\"capability\":{\"type\":\"appOp\",\"op\":\"READ_SMS\",\"mode\":\"ignore\",\"scope\":\"uid\"}}}"),
            ),
    )

    private fun endpoint(method: String, path: String, body: JSONObject?, description: String) = JSONObject()
        .put("method", method)
        .put("path", path)
        .put("description", description)
        .apply { if (body != null) put("body", body) }

    private fun handlePair(session: IHTTPSession): Response {
        val code = bodyJson(session)?.optString("code").orEmpty()
        if (code.isBlank()) return json(Response.Status.BAD_REQUEST, error("missing code"))
        val (token, failure) = gateway.pair(code)
        return if (token != null) {
            json(Response.Status.OK, JSONObject().put("token", token))
        } else {
            json(Response.Status.UNAUTHORIZED, error(failure ?: "pairing failed"))
        }
    }

    private fun state(): Response {
        val snapshot = coordinator.snapshot.value
        val groups = JSONArray()
        var applied = 0
        var drifted = 0
        var failed = 0
        snapshot.groups.forEach { group ->
            val states = group.rules.map { rule ->
                if (!rule.enabled || !group.enabled) RuleState.DISABLED
                else snapshot.results[rule.id]?.state ?: RuleState.PENDING
            }
            applied += states.count { it == RuleState.APPLIED }
            drifted += states.count { it == RuleState.DRIFTED }
            failed += states.count { it == RuleState.FAILED }
            groups.put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("enabled", group.enabled)
                    .put("rules", group.rules.size)
                    .put("applied", states.count { it == RuleState.APPLIED })
                    .put("drifted", states.count { it == RuleState.DRIFTED })
                    .put("failed", states.count { it == RuleState.FAILED }),
            )
        }
        val gatewayState = gateway.state.value
        return json(
            Response.Status.OK,
            JSONObject()
                .put("gateway", JSONObject().put("status", gatewayState.status.name).put("clients", gatewayState.clients))
                .put("shizuku", privilege.status().name)
                .put("verifiedAt", snapshot.verifiedAt)
                .put("groups", groups)
                .put(
                    "summary",
                    JSONObject()
                        .put("groups", snapshot.groups.size)
                        .put("rules", snapshot.groups.sumOf { it.rules.size })
                        .put("applied", applied)
                        .put("drifted", drifted)
                        .put("failed", failed),
                ),
        )
    }

    private fun rules(): Response {
        val text = RuleCodec.encode(coordinator.snapshot.value.groups)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
    }

    private fun importRules(session: IHTTPSession): Response {
        val text = bodyText(session)
        if (text.isBlank()) return json(Response.Status.BAD_REQUEST, error("missing body"))
        val groups = runCatching { RuleCodec.decode(text) }.getOrElse {
            return json(Response.Status.BAD_REQUEST, error(it.message ?: "invalid rule document"))
        }
        runBlocking { coordinator.importGroups(groups) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("groups", groups.size)
                .put("rules", groups.sumOf { it.rules.size }),
        )
    }

    private fun upsertRule(session: IHTTPSession): Response {
        val json = bodyJson(session) ?: return json(Response.Status.BAD_REQUEST, error("missing body"))
        val groupId = json.optString("groupId")
        val ruleJson = json.optJSONObject("rule") ?: return json(Response.Status.BAD_REQUEST, error("missing rule"))
        val rule = RuleCodec.decodeRule(ruleJson)
        val normalized = if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule
        if (groupId.isBlank() || coordinator.snapshot.value.groups.none { it.id == groupId }) {
            return json(Response.Status.BAD_REQUEST, error("unknown group"))
        }
        val result = runBlocking { coordinator.upsertRule(groupId, normalized) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("rule", RuleCodec.encodeRule(normalized))
                .put("result", result?.let { resultsJson(listOf(it)) }),
        )
    }

    private fun ruleAction(session: IHTTPSession, tail: String): Response {
        val segments = tail.trim('/').split('/')
        val id = segments.firstOrNull().orEmpty()
        if (id.isBlank()) return json(Response.Status.BAD_REQUEST, error("missing rule id"))
        val exists = coordinator.snapshot.value.groups.any { group -> group.rules.any { it.id == id } }
        if (!exists) return json(Response.Status.NOT_FOUND, error("unknown rule"))
        return when {
            segments.size == 2 && segments[1] == "enabled" && session.method == Method.POST -> {
                val json = bodyJson(session) ?: return json(Response.Status.BAD_REQUEST, error("missing body"))
                if (!json.has("enabled")) {
                    return json(Response.Status.BAD_REQUEST, error("missing enabled"))
                }
                val enabled = json.optBoolean("enabled")
                runBlocking { coordinator.setRuleEnabled(id, enabled) }
                json(Response.Status.OK, JSONObject().put("ok", true).put("enabled", enabled))
            }
            segments.size == 1 && session.method == Method.DELETE -> {
                runBlocking { coordinator.deleteRule(id) }
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            else -> json(Response.Status.METHOD_NOT_ALLOWED, error("method not allowed"))
        }
    }

    private fun applyGroup(uri: String): Response {
        val groupId = uri.removePrefix("/api/groups/").removeSuffix("/apply").trim('/')
        if (coordinator.snapshot.value.groups.none { it.id == groupId }) {
            return json(Response.Status.NOT_FOUND, error("unknown group"))
        }
        val results = runBlocking { coordinator.applyGroup(groupId) }
        return json(Response.Status.OK, JSONObject().put("ok", true).put("results", resultsJson(results)))
    }

    private fun setGroupEnabled(session: IHTTPSession, uri: String): Response {
        val groupId = uri.removePrefix("/api/groups/").removeSuffix("/enabled").trim('/')
        if (coordinator.snapshot.value.groups.none { it.id == groupId }) {
            return json(Response.Status.NOT_FOUND, error("unknown group"))
        }
        val json = bodyJson(session) ?: return json(Response.Status.BAD_REQUEST, error("missing body"))
        if (!json.has("enabled")) return json(Response.Status.BAD_REQUEST, error("missing enabled"))
        val enabled = json.optBoolean("enabled")
        runBlocking { coordinator.setGroupEnabled(groupId, enabled) }
        return json(Response.Status.OK, JSONObject().put("ok", true).put("enabled", enabled))
    }

    private fun verify(): Response {
        val snapshot = runBlocking { coordinator.verifyAll() }
        val payload = JSONObject().put("ok", true).put("results", resultsJson(snapshot.results.values.toList()))
        return json(Response.Status.OK, payload)
    }

    private fun repair(): Response {
        val report = runBlocking { coordinator.verifyAndRepair() }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("checked", report.checked)
                .put("repaired", report.repaired)
                .put("remainingDrifted", report.remainingDrifted)
                .put("remainingFailed", report.remainingFailed),
        )
    }

    private fun resultsJson(results: List<dev.latch.rule.data.StoredResult>): JSONArray = JSONArray().apply {
        results.forEach { result ->
            put(
                JSONObject()
                    .put("ruleId", result.ruleId)
                    .put("state", result.state.name)
                    .put("expected", result.expected)
                    .put("actual", result.actual)
                    .put("message", result.message),
            )
        }
    }

    private fun bodyJson(session: IHTTPSession): JSONObject? = runCatching {
        JSONObject(bodyText(session))
    }.getOrNull()

    /** Read the request body as UTF-8 regardless of the Content-Type charset. */
    private fun bodyText(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val stream = session.inputStream ?: return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = stream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, Charsets.UTF_8)
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())

    private companion object {
        val RULE_SHAPE: JSONObject = JSONObject()
            .put("id", "<optional>")
            .put("name", "<display name>")
            .put("targetPackage", "<package name>")
            .put("enabled", true)
            .put(
                "capability",
                JSONObject()
                    .put("type", "appOp | permissionState | forceStop | setEnabled")
                    .put("op", "<AppOps op, for appOp>")
                    .put("mode", "ignore | allow | default")
                    .put("scope", "uid | package")
                    .put("permission", "<permission, for permissionState>")
                    .put("mode", "allow | foreground | ask | deny | approximate | selected")
                    .put("enabled", "<bool, for setEnabled>"),
            )
    }
}
