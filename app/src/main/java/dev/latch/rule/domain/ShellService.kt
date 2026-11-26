package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.privilege.PrivilegeBroker

/**
 * Executes a paired client's shell command through the privileged bridge.
 *
 * The command runs inside the same Shizuku shell used by rules, so it has the
 * same privileges and the same timeout. Output is capped before it is returned;
 * the log keeps a short excerpt, never the full output.
 */
class ShellService(
    private val broker: PrivilegeBroker,
    private val eventLog: EventLog = EventLog.Noop,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val truncated: Boolean,
    )

    suspend fun execute(command: String): Outcome {
        val normalized = command.trim()
        require(normalized.isNotEmpty()) { "command is empty" }
        require(normalized.length <= MAX_COMMAND) { "command is too long" }

        val started = clock()
        val result = broker.execute(normalized)
        val duration = clock() - started
        val stdoutTruncated = result.stdout.length > MAX_OUTPUT
        val stderrTruncated = result.stderr.length > MAX_OUTPUT
        val truncated = stdoutTruncated || stderrTruncated

        eventLog.record(
            LatchEvent(
                level = if (result.ok) EventLevel.INFO else EventLevel.ERROR,
                source = EventSource.GATEWAY,
                action = EventAction.SHELL,
                outcome = result.exitCode.toString(),
                message = result.stderr.trim().takeIf { it.isNotEmpty() }?.take(MAX_LOG_TEXT),
                command = normalized.take(MAX_LOG_TEXT),
                output = result.stdout.take(MAX_LOG_TEXT),
                durationMs = duration,
            ),
        )

        return Outcome(
            exitCode = result.exitCode,
            stdout = truncate(result.stdout, stdoutTruncated),
            stderr = truncate(result.stderr, stderrTruncated),
            durationMs = duration,
            truncated = truncated,
        )
    }

    private fun truncate(text: String, truncated: Boolean): String =
        if (!truncated) text else text.take(MAX_OUTPUT) + TRUNCATED_MARK

    companion object {
        const val MAX_OUTPUT = 256 * 1024
        const val MAX_COMMAND = 8 * 1024
        private const val MAX_LOG_TEXT = 2_000
        private const val TRUNCATED_MARK = "\n… output truncated"
    }
}
