package dev.latch.rule.domain

import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleCodec
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot
import dev.latch.rule.engine.RuleEngine
import dev.latch.rule.engine.VerifyService
import dev.latch.rule.engine.toStored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single source of truth for rules and verification state.
 * Both the UI and the Agent gateway mutate state through this coordinator.
 */
class RuleCoordinator(
    private val ruleStore: RuleStore,
    private val resultStore: ResultStore,
    private val engine: RuleEngine,
    private val verifyService: VerifyService,
) {

    data class Snapshot(
        val groups: List<RuleGroup> = emptyList(),
        val results: Map<String, StoredResult> = emptyMap(),
        val verifiedAt: Long = 0L,
        val loading: Boolean = true,
    )

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    suspend fun load() {
        val groups = withContext(Dispatchers.IO) { ruleStore.load() }
        val verification = withContext(Dispatchers.IO) { resultStore.load() }
        _snapshot.value = Snapshot(
            groups = groups,
            results = verification.results,
            verifiedAt = verification.verifiedAt,
            loading = false,
        )
    }

    suspend fun createGroup(name: String, description: String = ""): RuleGroup = locked {
        val group = RuleGroup(
            id = "group-${UUID.randomUUID().toString().take(8)}",
            name = name.ifBlank { "未命名规则组" },
            description = description,
            rules = emptyList(),
        )
        persistGroups(_snapshot.value.groups + group)
        group
    }

    suspend fun updateGroupInfo(groupId: String, name: String, description: String) = locked {
        persistGroups(
            _snapshot.value.groups.map {
                if (it.id == groupId) {
                    it.copy(name = name.ifBlank { it.name }, description = description)
                } else {
                    it
                }
            },
        )
    }

    suspend fun deleteGroup(groupId: String) = locked {
        val group = _snapshot.value.groups.firstOrNull { it.id == groupId } ?: return@locked
        val remaining = _snapshot.value.groups.filterNot { it.id == groupId }
        persistGroups(remaining)
        val ruleIds = group.rules.map { it.id }.toSet()
        if (ruleIds.isNotEmpty()) {
            persistResults(_snapshot.value.results - ruleIds, _snapshot.value.verifiedAt)
        }
    }

    /** Create or update a rule and, when it is active, apply it immediately. */
    suspend fun upsertRule(groupId: String, rule: Rule): StoredResult? = locked {
        val groups = _snapshot.value.groups.map { group ->
            if (group.id != groupId) return@map group
            val exists = group.rules.any { it.id == rule.id }
            group.withRules(
                if (exists) group.rules.map { if (it.id == rule.id) rule else it }
                else group.rules + rule,
            )
        }
        val group = groups.firstOrNull { it.id == groupId } ?: return@locked null
        persistGroups(groups)
        if (!rule.enabled || !group.enabled) return@locked null
        val checkedAt = System.currentTimeMillis()
        val result = engine.apply(rule).toStored(checkedAt)
        persistResults(_snapshot.value.results + (rule.id to result), checkedAt)
        result
    }

    suspend fun deleteRule(ruleId: String) = locked {
        persistGroups(_snapshot.value.groups.map { it.withRules(it.rules.filterNot { r -> r.id == ruleId }) })
        _snapshot.update { it.copy(results = it.results - ruleId) }
        persistResults(_snapshot.value.results, _snapshot.value.verifiedAt)
    }

    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) = locked {
        persistGroups(
            _snapshot.value.groups.map { group ->
                group.withRules(group.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
            },
        )
    }

    suspend fun setGroupEnabled(groupId: String, enabled: Boolean) {
        locked {
            persistGroups(_snapshot.value.groups.map { if (it.id == groupId) it.copy(enabled = enabled) else it })
        }
        if (enabled) applyGroup(groupId)
    }

    suspend fun applyGroup(groupId: String): List<StoredResult> = locked {
        val group = _snapshot.value.groups.firstOrNull { it.id == groupId } ?: return@locked emptyList()
        val checkedAt = System.currentTimeMillis()
        val updated = group.rules.filter { it.enabled }.associate { rule ->
            rule.id to engine.apply(rule).toStored(checkedAt)
        }
        if (updated.isNotEmpty()) {
            persistResults(_snapshot.value.results + updated, checkedAt)
        }
        updated.values.toList()
    }

    suspend fun verifyAll(): VerificationSnapshot {
        val verification = verifyService.verifyAll()
        _snapshot.update { it.copy(results = verification.results, verifiedAt = verification.verifiedAt) }
        return verification
    }

    suspend fun verifyAndRepair(): VerifyService.RepairReport {
        val report = verifyService.verifyAndRepair()
        val stored = resultStore.load()
        _snapshot.update { it.copy(results = stored.results, verifiedAt = stored.verifiedAt) }
        return report
    }

    suspend fun importGroups(groups: List<RuleGroup>) = locked {
        val merged = _snapshot.value.groups.associateBy { it.id }.toMutableMap()
        groups.forEach { merged[it.id] = it }
        persistGroups(merged.values.toList())
    }

    fun exportJson(): String = RuleCodec.encode(_snapshot.value.groups)

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun persistGroups(groups: List<RuleGroup>) {
        ruleStore.save(groups)
        _snapshot.update { it.copy(groups = groups) }
    }

    private fun persistResults(results: Map<String, StoredResult>, verifiedAt: Long) {
        resultStore.save(VerificationSnapshot(results, verifiedAt))
        _snapshot.update { it.copy(results = results, verifiedAt = verifiedAt) }
    }
}
