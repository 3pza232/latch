package dev.latch.rule.data

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCodecTest {

    private val sampleGroup = RuleGroup(
        id = "group-1a2b3c4d",
        name = "示例规则组",
        description = "说明文字",
        enabled = true,
        rules = listOf(
            Rule(
                id = "rule-camera",
                name = "禁止相机",
                targetPackage = "com.example.app",
                capability = Capability.AppOp("CAMERA", OpMode.IGNORE, OpScope.UID),
            ),
            Rule(
                id = "rule-permission",
                name = "禁止麦克风",
                targetPackage = "com.example.app",
                capability = Capability.PermissionState(
                    "android.permission.RECORD_AUDIO",
                    PermissionMode.DENY,
                ),
            ),
            Rule(
                id = "rule-stop",
                name = "强制停止",
                targetPackage = "com.example.app",
                capability = Capability.ForceStop,
            ),
            Rule(
                id = "rule-enabled",
                name = "停用应用",
                targetPackage = "com.example.app",
                enabled = false,
                capability = Capability.SetEnabled(false),
            ),
        ),
    )

    @Test
    fun `encode then decode keeps the rule document intact`() {
        val decoded = RuleCodec.decode(RuleCodec.encode(RuleDocument(groups = listOf(sampleGroup))))

        assertEquals(listOf(sampleGroup), decoded.groups)
    }

    @Test
    fun `overrides and revision survive a round trip`() {
        val document = RuleDocument(
            groups = listOf(sampleGroup),
            overrides = listOf(RuleOverride("rule-camera", "rule-permission", 123L)),
            revision = 7L,
        )

        assertEquals(document, RuleCodec.decode(RuleCodec.encode(document)))
    }

    @Test
    fun `a version 1 document is read without overrides`() {
        val legacy = """{"version":1,"groups":[]}"""

        val document = RuleCodec.decode(legacy)

        assertTrue(document.overrides.isEmpty())
        assertEquals(0L, document.revision)
    }

    @Test
    fun `encoded document declares the current schema version`() {
        val text = RuleCodec.encode(RuleDocument(groups = listOf(sampleGroup)))

        assertTrue(text.contains("\"version\": ${RuleCodec.VERSION}"))
    }

    @Test
    fun `legacy permission capabilities are read as permission state`() {
        val legacy = """
            {"version":1,"groups":[{"id":"g","name":"g","description":"","enabled":true,"rules":[
              {"id":"a","name":"a","targetPackage":"com.example.app","enabled":true,
               "capability":{"type":"revokePermission","permission":"android.permission.CAMERA"}},
              {"id":"b","name":"b","targetPackage":"com.example.app","enabled":true,
               "capability":{"type":"grantPermission","permission":"android.permission.CAMERA"}}
            ]}]}
        """.trimIndent()

        val rules = RuleCodec.decode(legacy).groups.single().rules

        assertEquals(
            Capability.PermissionState("android.permission.CAMERA", PermissionMode.DENY),
            rules[0].capability,
        )
        assertEquals(
            Capability.PermissionState("android.permission.CAMERA", PermissionMode.ALLOW),
            rules[1].capability,
        )
    }
}
