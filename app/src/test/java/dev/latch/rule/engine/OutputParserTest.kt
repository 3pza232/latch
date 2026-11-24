package dev.latch.rule.engine

import dev.latch.rule.core.model.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Samples are taken from a real device (`cmd appops get`, `pm list packages`,
 * `ps -A`) so the parsers are exercised against the formats they actually see.
 */
class AppOpParserTest {

    private val parser = AppOpParser("CAMERA")

    @Test
    fun `uid override has priority`() {
        assertEquals("ignore", parser.extract("Uid mode: CAMERA: ignore"))
    }

    @Test
    fun `uid override wins over the package override`() {
        val output = "Uid mode: CAMERA: ignore\nCAMERA: allow"

        assertEquals("ignore", parser.extract(output))
    }

    @Test
    fun `package override is used when no uid override exists`() {
        assertEquals("ignore", parser.extract("CAMERA: ignore"))
    }

    @Test
    fun `no override is reported as the resolved default`() {
        assertEquals("default(allow)", parser.extract("No operations.\nDefault mode: allow"))
    }

    @Test
    fun `unrecognised output is indeterminate`() {
        assertNull(parser.extract("something unexpected"))
    }
}

class PermissionStateParserTest {

    private val allow = PermissionStateParser("android.permission.CAMERA", "CAMERA", PermissionMode.ALLOW)
    private val deny = PermissionStateParser("android.permission.CAMERA", "CAMERA", PermissionMode.DENY)

    @Test
    fun `granted and not ops-blocked reads allow`() {
        assertEquals("allow", allow.extract("0\nUid mode: CAMERA: default"))
    }

    @Test
    fun `granted but ops ignore reads blocked`() {
        assertEquals("blocked", allow.extract("0\nUid mode: CAMERA: ignore"))
    }

    @Test
    fun `not granted reads denied`() {
        assertEquals("denied", allow.extract("-1\nNo operations.\nDefault mode: allow"))
    }

    @Test
    fun `deny reads deny only when the permission is not granted`() {
        assertEquals("deny", deny.extract("-1"))
        assertEquals("granted", deny.extract("0"))
    }

    @Test
    fun `falls back to the dumpsys grant flag when check-permission is unavailable`() {
        val output = "android.permission.CAMERA: granted=true, flags=[ USER_SET ]"

        assertEquals("allow", allow.extract(output))
    }

    @Test
    fun `unrecognised output is indeterminate`() {
        assertNull(allow.extract("error: something"))
    }
}

class EnabledStateParserTest {

    private val parser = EnabledStateParser("com.example.app")

    @Test
    fun `package in the enabled list reads enabled`() {
        val output = "package:com.example.app\n---LATCH---"

        assertEquals("enabled", parser.extract(output))
    }

    @Test
    fun `package in the disabled list reads disabled`() {
        val output = "---LATCH---\npackage:com.example.app"

        assertEquals("disabled", parser.extract(output))
    }

    @Test
    fun `substring matches in the filter are not accepted`() {
        val output = "package:com.example.app.extra\n---LATCH---"

        assertNull(parser.extract(output))
    }

    @Test
    fun `missing separator is indeterminate`() {
        assertNull(parser.extract("package:com.example.app"))
    }
}

class ProcessStoppedParserTest {

    private val parser = ProcessStoppedParser("dev.latch.rule")

    @Test
    fun `app process in the listing reads running`() {
        val output = """
            PID NAME ARGS
            1 init init
            29103 dev.latch.rule dev.latch.rule
        """.trimIndent()

        assertEquals("running", parser.extract(output))
    }

    @Test
    fun `only the verifying shell reads stopped`() {
        val output = """
            PID NAME ARGS
            13151 sh sh -c ps -A -o PID,NAME,ARGS
            13154 grep grep -F dev.latch.rule
        """.trimIndent()

        assertEquals("stopped", parser.extract(output))
    }

    @Test
    fun `service process of the same app reads running`() {
        val output = """
            PID NAME ARGS
            29103 dev.latch.rule dev.latch.rule:remote
        """.trimIndent()

        assertEquals("running", parser.extract(output))
    }
}
