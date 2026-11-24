package dev.latch.rule.core.log

import java.util.UUID

/** Severity of a recorded event. Failures are errors, drift is a warning. */
enum class EventLevel { INFO, WARN, ERROR }

/** Where the action was initiated. */
enum class EventSource(val wire: String) {
    UI("ui"),
    GATEWAY("gateway"),
    PERIODIC("periodic"),
    BOOT("boot"),
    SYSTEM("system"),
    IMPORT("import"),
    RESTORE("restore"),
}

/** What happened. Kept coarse so the log stays filterable. */
enum class EventAction(val wire: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    ENABLE("enable"),
    DISABLE("disable"),
    APPLY("apply"),
    VERIFY("verify"),
    REPAIR("repair"),
    IMPORT("import"),
    EXPORT("export"),
    BACKUP("backup"),
    RESTORE("restore"),
    PAIR("pair"),
    PAIR_FAILED("pair_failed"),
    GATEWAY_START("gateway_start"),
    GATEWAY_STOP("gateway_stop"),
    SKIP("skip"),
    ROTATE("rotate"),
    MIGRATE("migrate"),
}

/**
 * One observable action.
 *
 * Events never carry credentials: pairing codes, tokens and authorization headers
 * are excluded at construction, and command output is truncated when stored.
 */
data class LatchEvent(
    val level: EventLevel,
    val source: EventSource,
    val action: EventAction,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val groupId: String? = null,
    val groupName: String? = null,
    val ruleId: String? = null,
    val ruleName: String? = null,
    val targetPackage: String? = null,
    /** Result label, for example `APPLIED`, `DRIFTED`, `FAILED` or `ok`. */
    val outcome: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val command: String? = null,
    val output: String? = null,
    val durationMs: Long? = null,
    val detail: String? = null,
)
