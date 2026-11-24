package dev.latch.rule.core.rules

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPolicyTest {

    private val policy = TargetPolicy(TargetPolicy.ALWAYS_PROTECTED + "com.example.launcher")

    private fun rule(target: String, capability: Capability) = Rule(
        id = "r-$target",
        name = "rule",
        targetPackage = target,
        capability = capability,
    )

    @Test
    fun `stopping or disabling a protected target is blocked`() {
        assertEquals("受保护目标，已跳过", policy.blockReason(rule("com.example.launcher", Capability.ForceStop)))
        assertEquals(
            "受保护目标，已跳过",
            policy.blockReason(rule("com.android.systemui", Capability.SetEnabled(false))),
        )
        assertEquals(
            "受保护目标，已跳过",
            policy.blockReason(rule("dev.latch.rule", Capability.ForceStop)),
        )
    }

    @Test
    fun `non destructive capabilities stay allowed`() {
        assertNull(policy.blockReason(rule("com.android.systemui", Capability.SetEnabled(true))))
        assertNull(
            policy.blockReason(
                rule("com.example.launcher", Capability.AppOp("CAMERA", OpMode.IGNORE)),
            ),
        )
        assertNull(
            policy.blockReason(
                rule(
                    "com.example.launcher",
                    Capability.PermissionState("android.permission.CAMERA", PermissionMode.DENY),
                ),
            ),
        )
    }

    @Test
    fun `ordinary packages are never blocked`() {
        assertNull(policy.blockReason(rule("com.example.app", Capability.ForceStop)))
        assertNull(policy.blockReason(rule("com.example.app", Capability.SetEnabled(false))))
    }

    @Test
    fun `validation reports duplicates, bad packages and protected targets`() {
        val document = RuleDocument(
            groups = listOf(
                RuleGroup(
                    id = "g",
                    name = "组",
                    description = "",
                    rules = listOf(
                        rule("com.example.app", Capability.ForceStop),
                        rule("com.example.app", Capability.ForceStop),
                        rule("not a package", Capability.ForceStop),
                        rule("com.android.systemui", Capability.ForceStop),
                    ),
                ),
            ),
        )

        val problems = RuleValidation.validate(document, policy)

        assertTrue(problems.any { it.contains("重复") })
        assertTrue(problems.any { it.contains("无效的包名") })
        assertTrue(problems.any { it.contains("受保护目标") })
    }

    @Test
    fun `a clean document has no validation problems`() {
        val document = RuleDocument(
            groups = listOf(
                RuleGroup(
                    id = "g",
                    name = "组",
                    description = "",
                    rules = listOf(rule("com.example.app", Capability.ForceStop)),
                ),
            ),
        )

        assertTrue(RuleValidation.validate(document, policy).isEmpty())
    }
}
