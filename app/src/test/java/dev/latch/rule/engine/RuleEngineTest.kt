package dev.latch.rule.engine

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.result.LatchErrorCode
import dev.latch.rule.core.result.ShellResult
import dev.latch.rule.privilege.PrivilegeBroker
import dev.latch.rule.privilege.PrivilegeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEngineTest {

    private val rule = Rule(
        id = "rule-1",
        name = "禁止相机",
        targetPackage = "com.example.app",
        capability = Capability.PermissionState("android.permission.CAMERA", PermissionMode.DENY),
    )

    private val VERIFY =
        "dumpsys package check-permission android.permission.CAMERA com.example.app 0; " +
            "dumpsys package com.example.app | grep -F 'android.permission.CAMERA: granted' || true; " +
            "cmd appops get --uid com.example.app CAMERA"

    @Test
    fun `applied when every step succeeds and the read-back matches`() = runBlocking {
        val broker = FakeBroker(
            "pm revoke --user 0 com.example.app android.permission.CAMERA" to ok(""),
            "cmd appops set --uid com.example.app CAMERA ignore" to ok(""),
            VERIFY to ok("-1\nUid mode: CAMERA: ignore"),
        )

        val result = RuleEngine(broker).apply(rule)

        assertEquals(RuleState.APPLIED, result.state)
        assertEquals("deny", result.actual)
    }

    @Test
    fun `a failed step does not hide a successful outcome`() = runBlocking {
        val broker = FakeBroker(
            "pm revoke --user 0 com.example.app android.permission.CAMERA" to failure("not changeable"),
            "cmd appops set --uid com.example.app CAMERA ignore" to ok(""),
            VERIFY to ok("-1\nUid mode: CAMERA: ignore"),
        )

        val result = RuleEngine(broker).apply(rule)

        assertEquals(RuleState.APPLIED, result.state)
    }

    @Test
    fun `failed steps are reported with their step number when the state drifts`() = runBlocking {
        val broker = FakeBroker(
            "pm revoke --user 0 com.example.app android.permission.CAMERA" to failure("not changeable"),
            "cmd appops set --uid com.example.app CAMERA ignore" to ok(""),
            VERIFY to ok("0\nUid mode: CAMERA: allow"),
        )

        val result = RuleEngine(broker).apply(rule)

        assertEquals(RuleState.FAILED, result.state)
        assertEquals(true, result.error?.message?.contains("步骤 1") == true)
    }

    @Test
    fun `unparseable read-back is a failure, never a false success`() = runBlocking {
        val broker = FakeBroker(
            VERIFY to ok("output changed format"),
        )

        val result = RuleEngine(broker).verify(rule)

        assertEquals(RuleState.FAILED, result.state)
        assertEquals(LatchErrorCode.PARSE_FAILED, result.error?.code)
    }

    @Test
    fun `command failure during verification is reported as failed`() = runBlocking {
        val broker = FakeBroker(
            VERIFY to ShellResult(255, "", "no root permission"),
        )

        val result = RuleEngine(broker).verify(rule)

        assertEquals(RuleState.FAILED, result.state)
        assertEquals(LatchErrorCode.NO_PRIVILEGE, result.error?.code)
    }

    @Test
    fun `default mode accepts the system-resolved value`() = runBlocking {
        val defaultRule = rule.copy(capability = Capability.AppOp("CAMERA", dev.latch.rule.core.model.OpMode.DEFAULT))
        val broker = FakeBroker(
            "cmd appops get com.example.app CAMERA" to ok("No operations.\nDefault mode: allow"),
        )

        val result = RuleEngine(broker).verify(defaultRule)

        assertEquals(RuleState.APPLIED, result.state)
        assertEquals("default(allow)", result.actual)
    }

    private fun ok(stdout: String) = ShellResult(0, stdout, "")

    private fun failure(stderr: String) = ShellResult(1, "", stderr)

    private class FakeBroker(vararg responses: Pair<String, ShellResult>) : PrivilegeBroker {

        private val expected = responses.toMap()

        override fun status(): PrivilegeStatus = PrivilegeStatus.READY

        override suspend fun execute(command: String): ShellResult =
            expected[command] ?: ShellResult(1, "", "unexpected command: $command")
    }
}
