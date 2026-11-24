package dev.latch.rule.gateway

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.RestoreScope
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.RuleOverride
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.rules.RuleValidation
import dev.latch.rule.core.rules.TargetPolicy
import dev.latch.rule.domain.BackupService
import dev.latch.rule.domain.RestoreService
import dev.latch.rule.domain.StatsService
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
    private val eventLog: EventLog,
    private val backupService: BackupService,
    private val restoreService: RestoreService,
    private val statsService: StatsService,
    private val targetPolicy: TargetPolicy,
) : NanoHTTPD(EPHEMERAL_PORT) {

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
                uri == "/api/logs" && method == Method.GET -> logs(session)
                uri == "/api/stats" && method == Method.GET -> stats()
                uri == "/api/backups" && method == Method.GET -> backups()
                uri == "/api/backups" && method == Method.POST -> createBackup()
                uri.startsWith("/api/backups/") && uri.endsWith("/restore") &&
                    method == Method.POST -> restoreBackup(session, uri)
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
        } catch (e: BodyTooLargeException) {
            json(Response.Status.PAYLOAD_TOO_LARGE, error(e.message.orEmpty()))
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
                    .put(endpoint("GET", "/api/logs?limit=200", null, "Recent sanitized events (no commands or raw output)"))
                    .put(endpoint("GET", "/api/stats", null, "Run counters, active days and the last seven days"))
                    .put(endpoint("GET", "/api/backups", null, "List rule snapshots, newest first"))
                    .put(endpoint("POST", "/api/backups", null, "Create a manual snapshot now"))
                    .put(
                        endpoint(
                            "POST",
                            "/api/backups/{id}/restore",
                            JSONObject().put("groups", "<group ids, omit for the whole document>")
                                .put("apply", false),
                            "Restore a snapshot; configuration only unless apply is true",
                        ),
                    )
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
        var suppressed = 0
        snapshot.groups.forEach { group ->
            val states = group.rules.map { rule ->
                when {
                    !rule.enabled || !group.enabled -> RuleState.DISABLED
                    rule.id in snapshot.suppressed -> RuleState.PENDING
                    else -> snapshot.results[rule.id]?.state ?: RuleState.PENDING
                }
            }
            val groupSuppressed = group.rules.count { it.id in snapshot.suppressed }
            applied += states.count { it == RuleState.APPLIED }
            drifted += states.count { it == RuleState.DRIFTED }
            failed += states.count { it == RuleState.FAILED }
            suppressed += groupSuppressed
            groups.put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("enabled", group.enabled)
                    .put("rules", group.rules.size)
                    .put("applied", states.count { it == RuleState.APPLIED })
                    .put("drifted", states.count { it == RuleState.DRIFTED })
                    .put("failed", states.count { it == RuleState.FAILED })
                    .put("suppressed", groupSuppressed),
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
                        .put("failed", failed)
                        .put("suppressed", suppressed),
                ),
        )
    }

    /**
     * Recent events for diagnostics. Read-only and sanitized: raw commands, shell
     * output and credentials never leave the device through this route.
     */
    private fun logs(session: IHTTPSession): Response {
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 1_000) ?: 200
        val events = runBlocking { eventLog.recent(limit) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("count", events.size)
                .put("events", JSONArray().apply { events.forEach { put(eventJson(it)) } }),
        )
    }

    private fun eventJson(event: LatchEvent) = JSONObject()
        .put("id", event.id)
        .put("ts", event.timestamp)
        .put("level", event.level.name)
        .put("source", event.source.wire)
        .put("action", event.action.wire)
        .put("outcome", event.outcome)
        .put("groupId", event.groupId)
        .put("groupName", event.groupName)
        .put("ruleId", event.ruleId)
        .put("ruleName", event.ruleName)
        .put("expected", event.expected)
        .put("actual", event.actual)
        .put("errorCode", event.errorCode)
        .put("message", event.message)
        .put("detail", event.detail)
        .put("durationMs", event.durationMs)

    private fun stats(): Response {
        val snapshot = runBlocking { statsService.refresh() }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("totalDays", snapshot.totalDays)
                .put("activeDays", snapshot.activeDays)
                .put("streakDays", snapshot.streakDays)
                .put("applyRuns", snapshot.applyRuns)
                .put("verifyRuns", snapshot.verifyRuns)
                .put("repairRuns", snapshot.repairRuns)
                .put("applied", snapshot.applied)
                .put("repaired", snapshot.repaired)
                .put("failed", snapshot.failed)
                .put("drifted", snapshot.drifted)
                .put("repairSuccessRate", snapshot.repairSuccessRate ?: JSONObject.NULL)
                .put(
                    "days",
                    JSONArray().apply {
                        snapshot.recentDays.forEach { day ->
                            put(
                                JSONObject()
                                    .put("date", day.date)
                                    .put("runs", day.runs)
                                    .put("applied", day.applied)
                                    .put("repaired", day.repaired)
                                    .put("failed", day.failed)
                                    .put("drifted", day.drifted),
                            )
                        }
                    },
                ),
        )
    }

    private fun backups(): Response {
        val metas = runBlocking { backupService.list() }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("count", metas.size)
                .put("backups", JSONArray().apply { metas.forEach { put(metaJson(it)) } }),
        )
    }

    private fun createBackup(): Response {
        val meta = runBlocking { backupService.snapshotNow(BackupReason.MANUAL) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("created", meta != null)
                .put("backup", meta?.let(::metaJson)),
        )
    }

    private fun restoreBackup(session: IHTTPSession, uri: String): Response {
        val id = uri.removePrefix("/api/backups/").removeSuffix("/restore").trim('/')
        if (id.isBlank()) return json(Response.Status.BAD_REQUEST, error("missing backup id"))
        val body = bodyJson(session)
        val groups = body?.optJSONArray("groups")
        val scope = if (groups == null) {
            RestoreScope.Full
        } else {
            RestoreScope.Selected(
                (0 until groups.length()).map { groups.getString(it) }.toSet(),
            )
        }
        val apply = body?.optBoolean("apply", false) ?: false
        val plan = runBlocking { restoreService.restore(id, scope, apply, EventSource.GATEWAY) }
            ?: return json(Response.Status.NOT_FOUND, error("unknown backup"))
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("added", plan.added)
                .put("replaced", plan.replaced)
                .put("removed", plan.removed)
                .put("groups", plan.groups.size)
                .put("rules", plan.ruleCount)
                .put("applied", apply),
        )
    }

    private fun metaJson(meta: BackupMeta) = JSONObject()
        .put("id", meta.id)
        .put("createdAt", meta.createdAt)
        .put("reason", meta.reason.wire)
        .put("groups", meta.groups)
        .put("rules", meta.rules)

    private fun rules(): Response {
        val text = RuleCodec.encode(coordinator.snapshot.value.document)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
    }

    private fun importRules(session: IHTTPSession): Response {
        val text = bodyText(session)
        if (text.isBlank()) return json(Response.Status.BAD_REQUEST, error("missing body"))
        val document = runCatching { RuleCodec.decode(text) }.getOrElse {
            return json(Response.Status.BAD_REQUEST, error(it.message ?: "invalid rule document"))
        }
        val problems = RuleValidation.validate(document, targetPolicy)
        if (problems.isNotEmpty()) {
            return json(
                Response.Status.BAD_REQUEST,
                error(problems.take(5).joinToString("；")).put("problems", JSONArray(problems)),
            )
        }
        runBlocking { coordinator.importDocument(document, EventSource.GATEWAY) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("groups", document.groups.size)
                .put("rules", document.rules().size),
        )
    }

    /**
     * Saving a rule that contradicts an existing one returns 409 with the conflicts.
     * The client resolves it with `resolve: "overwrite" | "keep"`.
     */
    private fun upsertRule(session: IHTTPSession): Response {
        val json = bodyJson(session) ?: return json(Response.Status.BAD_REQUEST, error("missing body"))
        val groupId = json.optString("groupId")
        val ruleJson = json.optJSONObject("rule") ?: return json(Response.Status.BAD_REQUEST, error("missing rule"))
        val rule = RuleCodec.decodeRule(ruleJson)
        val normalized = if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule
        if (groupId.isBlank() || coordinator.snapshot.value.groups.none { it.id == groupId }) {
            return json(Response.Status.BAD_REQUEST, error("unknown group"))
        }
        if (!RuleValidation.isValidPackage(normalized.targetPackage)) {
            return json(Response.Status.BAD_REQUEST, error("invalid target package"))
        }
        targetPolicy.blockReason(normalized)?.let { reason ->
            return json(Response.Status.BAD_REQUEST, error(reason))
        }

        val conflicts = coordinator.conflictsFor(groupId, normalized)
        val resolve = json.optString("resolve")
        if (conflicts.isNotEmpty() && resolve.isEmpty()) {
            return json(
                Response.Status.CONFLICT,
                error("rule conflicts with existing rules").put(
                    "conflicts",
                    JSONArray().apply {
                        conflicts.forEach { conflict ->
                            put(
                                JSONObject()
                                    .put("ruleId", conflict.existingRuleId)
                                    .put("ruleName", conflict.existingRuleName)
                                    .put("groupId", conflict.existingGroupId)
                                    .put("groupName", conflict.existingGroupName)
                                    .put("reason", conflict.summary),
                            )
                        }
                    },
                ),
            )
        }
        val now = System.currentTimeMillis()
        val overrides = when (resolve) {
            "overwrite" -> conflicts.map {
                RuleOverride(it.existingRuleId, normalized.id, now)
            }
            "keep" -> conflicts.map {
                RuleOverride(normalized.id, it.existingRuleId, now)
            }
            else -> emptyList()
        }
        val result = runBlocking {
            coordinator.upsertRule(groupId, normalized, overrides, EventSource.GATEWAY)
        }
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
                runBlocking { coordinator.setRuleEnabled(id, enabled, EventSource.GATEWAY) }
                json(Response.Status.OK, JSONObject().put("ok", true).put("enabled", enabled))
            }
            segments.size == 1 && session.method == Method.DELETE -> {
                runBlocking { coordinator.deleteRule(id, EventSource.GATEWAY) }
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
        val results = runBlocking { coordinator.applyGroup(groupId, EventSource.GATEWAY) }
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
        runBlocking { coordinator.setGroupEnabled(groupId, enabled, EventSource.GATEWAY) }
        return json(Response.Status.OK, JSONObject().put("ok", true).put("enabled", enabled))
    }

    private fun verify(): Response {
        val snapshot = runBlocking { coordinator.verifyAll(EventSource.GATEWAY) }
        val payload = JSONObject().put("ok", true).put("results", resultsJson(snapshot.results.values.toList()))
        return json(Response.Status.OK, payload)
    }

    private fun repair(): Response {
        val report = runBlocking { coordinator.verifyAndRepair(EventSource.GATEWAY) }
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

    /**
     * Read the request body as UTF-8 regardless of the Content-Type charset.
     *
     * The length is checked before allocating so a hostile local client cannot exhaust
     * memory by announcing a huge body.
     */
    private fun bodyText(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        if (length > MAX_BODY_BYTES) throw BodyTooLargeException()
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

    private class BodyTooLargeException :
        RuntimeException("request body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MiB")

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())

    private companion object {
        /** Let the OS pick a free port; a fixed port is easier to scan. */
        const val EPHEMERAL_PORT = 0
        const val MAX_BODY_BYTES = 2 * 1024 * 1024

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
                    .put("mode", "allow | deny")
                    .put("enabled", "<bool, for setEnabled>"),
            )
    }
}
