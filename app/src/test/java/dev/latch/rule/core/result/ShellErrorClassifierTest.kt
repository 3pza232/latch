package dev.latch.rule.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellErrorClassifierTest {

    private fun classify(exitCode: Int, stdout: String = "", stderr: String = "") =
        ShellErrorClassifier.classify(ShellResult(exitCode, stdout, stderr))

    @Test
    fun `exit 255 means the privilege bridge is unavailable`() {
        assertEquals(LatchErrorCode.NO_PRIVILEGE, classify(255).code)
    }

    @Test
    fun `policy rejection messages map to POLICY_BLOCKED`() {
        assertEquals(
            LatchErrorCode.POLICY_BLOCKED,
            classify(1, stderr = "Error: no root permission to perform this operation").code,
        )
        assertEquals(
            LatchErrorCode.POLICY_BLOCKED,
            classify(1, stderr = "Failure [DELETE_FAILED_INTERNAL_ERROR]").code,
        )
        assertEquals(
            LatchErrorCode.POLICY_BLOCKED,
            classify(1, stderr = "Operation not permitted").code,
        )
    }

    @Test
    fun `a timed out command maps to TIMEOUT`() {
        assertEquals(
            LatchErrorCode.TIMEOUT,
            classify(-1, stderr = "命令超时（15 秒）").code,
        )
    }

    @Test
    fun `security exceptions mean the shell was denied`() {
        assertEquals(
            LatchErrorCode.SHELL_DENIED,
            classify(1, stderr = "java.lang.SecurityException: Shell cannot change component state").code,
        )
    }

    @Test
    fun `missing packages are recognised`() {
        assertEquals(
            LatchErrorCode.PACKAGE_NOT_FOUND,
            classify(1, stderr = "Error: Unknown package: com.no.such.app").code,
        )
    }

    @Test
    fun `unknown failures keep their output`() {
        val error = classify(2, stderr = "some unexpected failure")

        assertEquals(LatchErrorCode.UNKNOWN, error.code)
        assertEquals("some unexpected failure", error.message)
    }

    @Test
    fun `long output is truncated and empty output falls back to the exit code`() {
        assertTrue(classify(1, stderr = "x".repeat(5_000)).message.length <= 500)
        assertEquals("command failed with exit code 3", classify(3).message)
    }

    @Test
    fun `classification never mentions a device vendor`() {
        val message = classify(1, stderr = "no root permission").message.lowercase()

        assertFalse(message.contains("vivo"))
        assertFalse(message.contains("xiaomi"))
    }
}
