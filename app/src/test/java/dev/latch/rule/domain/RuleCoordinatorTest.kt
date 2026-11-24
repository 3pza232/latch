package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride
import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleLoadResult
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot
import dev.latch.rule.engine.RuleEngine
import dev.latch.rule.engine.VerifyService
import dev.latch.rule.privilege.PrivilegeBroker
import dev.latch.rule.privilege.PrivilegeStatus
import dev.latch.rule.privilege.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCoordinatorTest {

    private val rule = Rule(
        id = "rule-camera",
        name = "禁止相机",
        targetPackage = "com.example.app",
        capability = Capability.AppOp("CAMERA", OpMode.IGNORE),
    )

    @Test
    fun `saving an active rule applies it and records the outcome`() = runBlocking {
        val harness = harness()
        val group = harness.coordinator.createGroup("组")

        harness.coordinator.upsertRule(group.id, rule)

        val events = harness.events.all()
        assertEquals(
            listOf(EventAction.CREATE, EventAction.APPLY),
            events.filter { it.ruleId == rule.id }.map { it.action },
        )
        assertEquals("APPLIED", events.first { it.action == EventAction.APPLY }.outcome)
    }

    @Test
    fun `saving a disabled rule records only the document change`() = runBlocking {
        val harness = harness()
        val group = harness.coordinator.createGroup("组")

        harness.coordinator.upsertRule(group.id, rule.copy(enabled = false))

        assertEquals(
            listOf(EventAction.CREATE),
            harness.events.all().filter { it.ruleId == rule.id }.map { it.action },
        )
    }

    @Test
    fun `drift during verification is recorded separately from the batch summary`() = runBlocking {
        val harness = harness(
            "cmd appops get com.example.app CAMERA" to ShellResult(0, "Uid mode: CAMERA: allow", ""),
        )
        harness.coordinator.importDocument(RuleDocument(groups = listOf(groupWith(rule))), EventSource.IMPORT)

        harness.coordinator.verifyAll(EventSource.PERIODIC)

        val events = harness.events.all()
        assertEquals(EventAction.IMPORT, events.first().action)
        assertTrue(events.any { it.action == EventAction.VERIFY && it.ruleId == rule.id && it.outcome == "DRIFTED" })
        assertTrue(events.any { it.action == EventAction.VERIFY && it.ruleId == null && it.detail?.contains("drifted=1") == true })
    }

    @Test
    fun `bulk group delete removes every selected group and its results`() = runBlocking {
        val harness = harness()
        val first = harness.coordinator.createGroup("一")
        val second = harness.coordinator.createGroup("二")
        harness.coordinator.upsertRule(first.id, rule)
        harness.coordinator.upsertRule(second.id, rule.copy(id = "rule-2"))

        harness.coordinator.deleteGroups(setOf(first.id, second.id))

        assertTrue(harness.coordinator.snapshot.value.groups.isEmpty())
        assertTrue(harness.coordinator.snapshot.value.results.isEmpty())
        assertEquals("groups=2 rules=2", harness.events.all().last().detail)
    }

    @Test
    fun `bulk rule delete removes selected rules only`() = runBlocking {
        val harness = harness()
        val group = harness.coordinator.createGroup("组")
        val keep = rule.copy(id = "keep")
        harness.coordinator.upsertRule(group.id, keep)
        harness.coordinator.upsertRule(group.id, rule)

        harness.coordinator.deleteRules(setOf(rule.id))

        val remaining = harness.coordinator.snapshot.value.groups.single().rules
        assertEquals(listOf("keep"), remaining.map { it.id })
        assertEquals(setOf("keep"), harness.coordinator.snapshot.value.results.keys)
    }

    @Test
    fun `saving an override suppresses the loser and applies the winner`() = runBlocking {
        val harness = harness()
        val group = harness.coordinator.createGroup("组")
        val existing = rule.copy(id = "allow", capability = Capability.AppOp("CAMERA", OpMode.ALLOW))
        harness.coordinator.upsertRule(group.id, existing)
        harness.broker.calls.clear()

        harness.coordinator.upsertRule(
            group.id,
            rule,
            listOf(RuleOverride(loserRuleId = "allow", winnerRuleId = rule.id, createdAt = 1L)),
        )

        assertEquals(rule.id, harness.coordinator.snapshot.value.suppressed["allow"])
        assertTrue(
            harness.broker.calls.contains("cmd appops set --uid com.example.app CAMERA ignore"),
        )
    }

    @Test
    fun `disabling the winner re-applies the suppressed rule`() = runBlocking {
        val harness = harness()
        val group = harness.coordinator.createGroup("组")
        val existing = rule.copy(id = "allow", capability = Capability.AppOp("CAMERA", OpMode.ALLOW))
        harness.coordinator.upsertRule(group.id, existing)
        harness.coordinator.upsertRule(
            group.id,
            rule,
            listOf(RuleOverride("allow", rule.id, 1L)),
        )
        harness.broker.calls.clear()

        harness.coordinator.setRuleEnabled(rule.id, false)

        assertTrue(harness.coordinator.snapshot.value.suppressed.isEmpty())
        assertTrue(
            harness.broker.calls.contains("cmd appops set --uid com.example.app CAMERA allow"),
        )
    }

    @Test
    fun `toggling a rule records an enable event`() = runBlocking {
        val harness = harness()
        harness.coordinator.importDocument(RuleDocument(groups = listOf(groupWith(rule))), EventSource.IMPORT)

        harness.coordinator.setRuleEnabled(rule.id, false)

        assertEquals(
            EventAction.DISABLE,
            harness.events.all().last().action,
        )
    }

    private fun harness(
        vararg responses: Pair<String, ShellResult>,
    ): Harness {
        val ruleStore = InMemoryRuleStore()
        val resultStore = InMemoryResultStore()
        val broker = FakeBroker(
            mapOf(
                "cmd appops set --uid com.example.app CAMERA ignore" to ShellResult(0, "", ""),
                "cmd appops set --uid com.example.app CAMERA allow" to ShellResult(0, "", ""),
                "cmd appops set --uid com.example.app CAMERA default" to ShellResult(0, "", ""),
                "cmd appops get com.example.app CAMERA" to ShellResult(0, "Uid mode: CAMERA: ignore", ""),
                *responses,
            ),
        )
        val engine = RuleEngine(broker)
        val events = RecordingEventLog()
        val verifyService = VerifyService(ruleStore, resultStore, engine, events)
        return Harness(
            RuleCoordinator(ruleStore, resultStore, engine, verifyService, events),
            events,
            broker,
        )
    }

    private fun groupWith(vararg rules: Rule) = RuleGroup(
        id = "group-1",
        name = "组",
        description = "",
        rules = rules.toList(),
    )

    private class Harness(
        val coordinator: RuleCoordinator,
        val events: RecordingEventLog,
        val broker: FakeBroker,
    )

    private class RecordingEventLog : EventLog {
        private val recorded = mutableListOf<LatchEvent>()

        override suspend fun record(event: LatchEvent) {
            recorded += event
        }

        fun all(): List<LatchEvent> = recorded.toList()
    }

    private class InMemoryRuleStore : RuleStore {
        private var document: RuleDocument = RuleDocument()

        override fun load(): RuleLoadResult = RuleLoadResult.Success(document)

        override fun save(document: RuleDocument) {
            this.document = document
        }
    }

    private class InMemoryResultStore : ResultStore {
        private var snapshot = VerificationSnapshot()

        override fun load(): VerificationSnapshot = snapshot

        override fun save(snapshot: VerificationSnapshot) {
            this.snapshot = snapshot
        }
    }

    private class FakeBroker(private val responses: Map<String, ShellResult>) : PrivilegeBroker {

        val calls = mutableListOf<String>()

        override fun status(): PrivilegeStatus = PrivilegeStatus.READY

        override suspend fun execute(command: String): ShellResult {
            calls += command
            return responses[command] ?: ShellResult(1, "", "unexpected command: $command")
        }
    }
}
