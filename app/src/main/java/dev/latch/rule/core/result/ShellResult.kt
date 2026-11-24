package dev.latch.rule.core.result

/** Output of one privileged shell invocation. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}
