package dev.latch.rule.privilege

enum class PrivilegeStatus {
    UNAVAILABLE,
    NOT_GRANTED,
    READY,
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Single entry point for privileged command execution.
 * Implementations decide how the shell privileges are obtained (Shizuku today, root later).
 */
interface PrivilegeBroker {

    fun status(): PrivilegeStatus

    suspend fun execute(command: String): ShellResult
}
