package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.privilege.PrivilegeBroker
import dev.latch.rule.privilege.PrivilegeStatus
import dev.latch.rule.core.result.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellServiceTest {

    private val events = RecordingEventLog()

    private fun service(result: ShellResult) =
        ShellService(FakeBroker(result), events, clock = { 0L })

    @Test
    fun `returns the result and logs the command`() = runBlocking {
        val outcome = service(ShellResult(0, "hello", "")).execute("pm list packages")

        assertEquals(0, outcome.exitCode)
        assertEquals("hello", outcome.stdout)
        val event = events.all().single()
        assertEquals(EventAction.SHELL, event.action)
        assertEquals("pm list packages", event.command)
        assertEquals("0", event.outcome)
    }

    @Test
    fun `large output is truncated`() = runBlocking {
        val big = "x".repeat(ShellService.MAX_OUTPUT + 10)
        val outcome = service(ShellResult(0, big, "")).execute("dumpsys package")

        assertTrue(outcome.truncated)
        assertTrue(outcome.stdout.length >= ShellService.MAX_OUTPUT)
        assertTrue(outcome.stdout.length < ShellService.MAX_OUTPUT + 100)
        assertTrue(outcome.stdout.endsWith("output truncated"))
    }

    @Test
    fun `blank and oversized commands are rejected`() {
        val service = service(ShellResult(0, "", ""))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.execute("  ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.execute("x".repeat(ShellService.MAX_COMMAND + 1)) }
        }
    }

    private class FakeBroker(private val result: ShellResult) : PrivilegeBroker {

        override fun status(): PrivilegeStatus = PrivilegeStatus.READY

        override suspend fun execute(command: String): ShellResult = result
    }

    private class RecordingEventLog : EventLog {
        private val recorded = mutableListOf<LatchEvent>()

        override suspend fun record(event: LatchEvent) {
            recorded += event
        }

        fun all(): List<LatchEvent> = recorded.toList()
    }
}
