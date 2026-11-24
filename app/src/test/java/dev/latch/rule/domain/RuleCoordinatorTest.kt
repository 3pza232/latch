package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
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
        harness.coordinator.importGroups(listOf(groupWith(rule)), EventSource.IMPORT)

        harness.coordinator.verifyAll(EventSource.PERIODIC)

        val events = harness.events.all()
        assertEquals(EventAction.IMPORT, events.first().action)
        assertTrue(events.any { it.action == EventAction.VERIFY && it.ruleId == rule.id && it.outcome == "DRIFTED" })
        assertTrue(events.any { it.action == EventAction.VERIFY && it.ruleId == null && it.detail?.contains("drifted=1") == true })
    }

    @Test
    fun `toggling a rule records an enable event`() = runBlocking {
        val harness = harness()
        harness.coordinator.importGroups(listOf(groupWith(rule)), EventSource.IMPORT)

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
        )
    }

    private fun groupWith(vararg rules: Rule) = RuleGroup(
        id = "group-1",
        name = "组",
        description = "",
        rules = rules.toList(),
    )

    private class Harness(val coordinator: RuleCoordinator, val events: RecordingEventLog)

    private class RecordingEventLog : EventLog {
        private val recorded = mutableListOf<LatchEvent>()

        override suspend fun record(event: LatchEvent) {
            recorded += event
        }

        fun all(): List<LatchEvent> = recorded.toList()
    }

    private class InMemoryRuleStore : RuleStore {
        private var groups: List<RuleGroup> = emptyList()

        override fun load(): RuleLoadResult = RuleLoadResult.Success(groups)

        override fun save(groups: List<RuleGroup>) {
            this.groups = groups
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

        override fun status(): PrivilegeStatus = PrivilegeStatus.READY

        override suspend fun execute(command: String): ShellResult =
            responses[command] ?: ShellResult(1, "", "unexpected command: $command")
    }
}
