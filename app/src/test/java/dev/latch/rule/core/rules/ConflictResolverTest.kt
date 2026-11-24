package dev.latch.rule.core.rules

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private fun rule(
        id: String,
        capability: Capability,
        target: String = "com.example.app",
        enabled: Boolean = true,
    ) = Rule(id = id, name = id, targetPackage = target, capability = capability, enabled = enabled)

    private fun document(vararg rules: Rule, overrides: List<RuleOverride> = emptyList()) = RuleDocument(
        groups = listOf(RuleGroup("g", "组", "", rules.toList())),
        overrides = overrides,
    )

    private fun override(loser: String, winner: String, createdAt: Long = 1L) =
        RuleOverride(loser, winner, createdAt)

    @Test
    fun `different values for the same op conflict`() {
        val deny = rule("deny", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val allow = rule("allow", Capability.AppOp("CAMERA", OpMode.ALLOW))

        assertEquals("AppOps CAMERA", ConflictResolver.conflictReason(deny, allow))
    }

    @Test
    fun `identical declarations are duplicates, not conflicts`() {
        val first = rule("a", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val second = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE))

        assertNull(ConflictResolver.conflictReason(first, second))
    }

    @Test
    fun `different ops, permissions or packages do not conflict`() {
        val camera = rule("a", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val audio = rule("b", Capability.AppOp("RECORD_AUDIO", OpMode.IGNORE))
        val otherPackage = rule("c", Capability.AppOp("CAMERA", OpMode.ALLOW), target = "com.other.app")
        val permission = rule("d", Capability.PermissionState("android.permission.CAMERA", PermissionMode.ALLOW))

        assertNull(ConflictResolver.conflictReason(camera, audio))
        assertNull(ConflictResolver.conflictReason(camera, otherPackage))
        assertNull(ConflictResolver.conflictReason(camera, permission))
    }

    @Test
    fun `permission states conflict when the value differs`() {
        val allow = rule("a", Capability.PermissionState("android.permission.CAMERA", PermissionMode.ALLOW))
        val deny = rule("b", Capability.PermissionState("android.permission.CAMERA", PermissionMode.DENY))

        assertEquals("权限 CAMERA", ConflictResolver.conflictReason(allow, deny))
    }

    @Test
    fun `a uid scoped op conflicts with a package rule on another package sharing the uid`() {
        val shared = UidLookup { packageName ->
            if (packageName == "com.example.app" || packageName == "com.example.companion") 10100 else null
        }
        val uidScoped = rule("a", Capability.AppOp("CAMERA", OpMode.IGNORE, OpScope.UID))
        val packageScoped = rule(
            "b",
            Capability.AppOp("CAMERA", OpMode.ALLOW, OpScope.PACKAGE),
            target = "com.example.companion",
        )

        assertEquals(
            "共享 UID 上的 AppOps CAMERA",
            ConflictResolver.conflictReason(uidScoped, packageScoped, shared),
        )
    }

    @Test
    fun `package scoped rules on different packages never conflict`() {
        val shared = UidLookup { 10100 }
        val first = rule("a", Capability.AppOp("CAMERA", OpMode.IGNORE, OpScope.PACKAGE))
        val second = rule(
            "b",
            Capability.AppOp("CAMERA", OpMode.ALLOW, OpScope.PACKAGE),
            target = "com.example.companion",
        )

        assertNull(ConflictResolver.conflictReason(first, second, shared))
    }

    @Test
    fun `different uids do not conflict`() {
        val lookup = UidLookup { packageName ->
            if (packageName == "com.example.app") 10100 else 10200
        }
        val first = rule("a", Capability.AppOp("CAMERA", OpMode.IGNORE, OpScope.UID))
        val second = rule(
            "b",
            Capability.AppOp("CAMERA", OpMode.ALLOW, OpScope.PACKAGE),
            target = "com.example.companion",
        )

        assertNull(ConflictResolver.conflictReason(first, second, lookup))
    }

    @Test
    fun `permission facts never cross packages`() {
        val shared = UidLookup { 10100 }
        val first = rule(
            "a",
            Capability.PermissionState("android.permission.CAMERA", PermissionMode.ALLOW),
        )
        val second = rule(
            "b",
            Capability.PermissionState("android.permission.CAMERA", PermissionMode.DENY),
            target = "com.example.companion",
        )

        assertNull(ConflictResolver.conflictReason(first, second, shared))
    }

    @Test
    fun `force stop never conflicts`() {
        val first = rule("a", Capability.ForceStop)
        val second = rule("b", Capability.ForceStop)

        assertNull(ConflictResolver.conflictReason(first, second))
    }

    @Test
    fun `disabled rules and disabled groups are not reported as conflicts`() {
        val existing = rule("a", Capability.AppOp("CAMERA", OpMode.ALLOW))
        val incoming = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val doc = document(existing)

        assertTrue(ConflictResolver.conflicts(doc, incoming, groupEnabled = false).isEmpty())
        assertTrue(
            ConflictResolver.conflicts(doc, incoming.copy(enabled = false), groupEnabled = true).isEmpty(),
        )
        assertEquals(1, ConflictResolver.conflicts(doc, incoming, groupEnabled = true).size)
    }

    @Test
    fun `an override suppresses the loser while both rules are active`() {
        val loser = rule("a", Capability.AppOp("CAMERA", OpMode.ALLOW))
        val winner = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE))

        val suppressed = ConflictResolver.suppressedBy(
            document(loser, winner, overrides = listOf(override("a", "b"))),
        )

        assertEquals(mapOf("a" to "b"), suppressed)
    }

    @Test
    fun `an override is dormant when the winner is disabled`() {
        val loser = rule("a", Capability.AppOp("CAMERA", OpMode.ALLOW))
        val winner = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE), enabled = false)

        assertTrue(
            ConflictResolver.suppressedBy(
                document(loser, winner, overrides = listOf(override("a", "b"))),
            ).isEmpty(),
        )
    }

    @Test
    fun `an override is dormant when the rules no longer conflict`() {
        val loser = rule("a", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val winner = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE))

        assertTrue(
            ConflictResolver.suppressedBy(
                document(loser, winner, overrides = listOf(override("a", "b"))),
            ).isEmpty(),
        )
    }

    @Test
    fun `chained overrides keep the middle rule suppressed`() {
        val a = rule("a", Capability.AppOp("CAMERA", OpMode.ALLOW))
        val b = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val c = rule("c", Capability.AppOp("CAMERA", OpMode.DEFAULT))
        val doc = document(
            a, b, c,
            overrides = listOf(override("a", "b"), override("b", "c")),
        )

        assertEquals(mapOf("a" to "b", "b" to "c"), ConflictResolver.suppressedBy(doc))
    }

    @Test
    fun `the newest override wins when a rule has several`() {
        val a = rule("a", Capability.AppOp("CAMERA", OpMode.ALLOW))
        val b = rule("b", Capability.AppOp("CAMERA", OpMode.IGNORE))
        val c = rule("c", Capability.AppOp("CAMERA", OpMode.DEFAULT))

        val suppressed = ConflictResolver.suppressedBy(
            document(a, b, c, overrides = listOf(override("a", "b", 1), override("a", "c", 5))),
        )

        assertEquals(mapOf("a" to "c"), suppressed)
    }
}
