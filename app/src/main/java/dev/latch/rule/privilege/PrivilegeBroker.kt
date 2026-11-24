package dev.latch.rule.privilege

import dev.latch.rule.core.result.ShellResult

enum class PrivilegeStatus {
    UNAVAILABLE,
    NOT_GRANTED,
    READY,
}

/**
 * Single entry point for privileged command execution.
 * Implementations decide how the shell privileges are obtained (Shizuku today, root later).
 */
interface PrivilegeBroker {

    fun status(): PrivilegeStatus

    suspend fun execute(command: String): ShellResult
}
