package dev.latch.rule.core.result

import dev.latch.rule.privilege.ShellResult

/** Stable, user-facing failure taxonomy. */
enum class LatchErrorCode {
    NO_PRIVILEGE,
    PACKAGE_NOT_FOUND,

    /** The system or a protection policy rejected the write. */
    POLICY_BLOCKED,
    SHELL_DENIED,
    INVALID_RULE,

    /** The command succeeded but its output could not be interpreted. */
    PARSE_FAILED,

    /** The command did not finish in time. */
    TIMEOUT,
    UNKNOWN,
}

data class LatchError(
    val code: LatchErrorCode,
    val message: String,
) {
    companion object {

        fun fromShell(result: ShellResult): LatchError = ShellErrorClassifier.classify(result)

        fun invalidRule(message: String): LatchError = LatchError(LatchErrorCode.INVALID_RULE, message)
    }
}
