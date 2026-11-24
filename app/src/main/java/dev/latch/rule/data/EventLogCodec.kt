package dev.latch.rule.data

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import org.json.JSONObject

/** One event per JSON line (NDJSON). Long fields are truncated so a single event cannot bloat the log. */
object EventLogCodec {

    private const val MAX_OUTPUT = 2_000
    private const val MAX_MESSAGE = 500

    fun encode(event: LatchEvent): String = JSONObject().apply {
        put("id", event.id)
        put("ts", event.timestamp)
        put("level", event.level.name)
        put("source", event.source.wire)
        put("action", event.action.wire)
        event.groupId?.let { put("groupId", it) }
        event.groupName?.let { put("groupName", it) }
        event.ruleId?.let { put("ruleId", it) }
        event.ruleName?.let { put("ruleName", it) }
        event.targetPackage?.let { put("targetPackage", it) }
        event.outcome?.let { put("outcome", it) }
        event.expected?.let { put("expected", it) }
        event.actual?.let { put("actual", it) }
        event.errorCode?.let { put("errorCode", it) }
        event.message?.let { put("message", it.take(MAX_MESSAGE)) }
        event.command?.let { put("command", it.take(MAX_OUTPUT)) }
        event.output?.let { put("output", it.take(MAX_OUTPUT)) }
        event.durationMs?.let { put("durationMs", it) }
        event.detail?.let { put("detail", it) }
    }.toString()

    /** Returns null for a partially written or otherwise unreadable line. */
    fun decode(line: String): LatchEvent? = runCatching {
        val json = JSONObject(line)
        LatchEvent(
            level = EventLevel.entries.firstOrNull { it.name == json.optString("level") } ?: EventLevel.INFO,
            source = EventSource.entries.firstOrNull { it.wire == json.optString("source") } ?: EventSource.SYSTEM,
            action = EventAction.entries.firstOrNull { it.wire == json.optString("action") } ?: EventAction.VERIFY,
            id = json.optString("id"),
            timestamp = json.optLong("ts"),
            groupId = json.optStringOrNull("groupId"),
            groupName = json.optStringOrNull("groupName"),
            ruleId = json.optStringOrNull("ruleId"),
            ruleName = json.optStringOrNull("ruleName"),
            targetPackage = json.optStringOrNull("targetPackage"),
            outcome = json.optStringOrNull("outcome"),
            expected = json.optStringOrNull("expected"),
            actual = json.optStringOrNull("actual"),
            errorCode = json.optStringOrNull("errorCode"),
            message = json.optStringOrNull("message"),
            command = json.optStringOrNull("command"),
            output = json.optStringOrNull("output"),
            durationMs = json.optLongOrNull("durationMs"),
            detail = json.optStringOrNull("detail"),
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
