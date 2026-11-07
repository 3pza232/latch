package dev.latch.rule.core.result

import dev.latch.rule.privilege.ShellResult

/** Stable, user-facing failure taxonomy. */
enum class LatchErrorCode {
    NO_PRIVILEGE,
    PACKAGE_NOT_FOUND,
    VIVO_PROTECTED,
    SHELL_DENIED,
    INVALID_RULE,
    UNKNOWN,
}

data class LatchError(
    val code: LatchErrorCode,
    val message: String,
) {
    companion object {

        fun fromShell(result: ShellResult): LatchError {
            val text = result.stderr.ifBlank { result.stdout }.trim()
            val code = when {
                result.exitCode == 255 -> LatchErrorCode.NO_PRIVILEGE
                text.contains("no root permission", ignoreCase = true) -> LatchErrorCode.VIVO_PROTECTED
                text.contains("DELETE_FAILED") -> LatchErrorCode.VIVO_PROTECTED
                text.contains("SecurityException", ignoreCase = true) -> LatchErrorCode.SHELL_DENIED
                text.contains("Unknown package", ignoreCase = true) ||
                    text.contains("not found", ignoreCase = true) -> LatchErrorCode.PACKAGE_NOT_FOUND
                else -> LatchErrorCode.UNKNOWN
            }
            return LatchError(code, text.ifBlank { "command failed with exit code ${result.exitCode}" })
        }

        fun invalidRule(message: String): LatchError = LatchError(LatchErrorCode.INVALID_RULE, message)
    }
}
