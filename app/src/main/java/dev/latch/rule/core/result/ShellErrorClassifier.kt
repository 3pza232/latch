package dev.latch.rule.core.result

import dev.latch.rule.privilege.ShellResult

/**
 * Maps a failed shell invocation to a stable error code.
 *
 * Classification is based on the exit status and on signals present in the output,
 * never on the device model or ROM: package-manager rejections, SELinux denials and
 * revoked privileges all surface through a small, bounded set of messages. Anything
 * unrecognised stays [LatchErrorCode.UNKNOWN] with the original output preserved so
 * the failure can still be diagnosed.
 */
object ShellErrorClassifier {

    /** Output markers that indicate a policy or privilege boundary, not a bad rule. */
    private val POLICY_SIGNALS = listOf(
        "no root permission",
        "delete_failed",
        "operation not permitted",
    )

    fun classify(result: ShellResult): LatchError {
        val output = result.stderr.ifBlank { result.stdout }.trim()
        val code = when {
            output.contains("命令超时") -> LatchErrorCode.TIMEOUT
            result.exitCode == 255 -> LatchErrorCode.NO_PRIVILEGE
            containsAny(output, POLICY_SIGNALS) -> LatchErrorCode.POLICY_BLOCKED
            output.contains("SecurityException", ignoreCase = true) -> LatchErrorCode.SHELL_DENIED
            output.contains("unknown package", ignoreCase = true) ||
                output.contains("not found", ignoreCase = true) -> LatchErrorCode.PACKAGE_NOT_FOUND
            else -> LatchErrorCode.UNKNOWN
        }
        return LatchError(code, describe(output, result.exitCode))
    }

    private fun containsAny(text: String, signals: List<String>): Boolean =
        signals.any { text.contains(it, ignoreCase = true) }

    private fun describe(output: String, exitCode: Int): String =
        output.takeIf { it.isNotBlank() }?.take(MAX_MESSAGE)
            ?: "command failed with exit code $exitCode"

    private const val MAX_MESSAGE = 500
}
