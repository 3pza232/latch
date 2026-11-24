package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleCodec
import dev.latch.rule.data.RuleLoadResult
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot
import dev.latch.rule.engine.RuleEngine
import dev.latch.rule.engine.VerifyService
import dev.latch.rule.engine.batchEvent
import dev.latch.rule.engine.toEvent
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
    private val eventLog: EventLog = EventLog.Noop,
    private val backupService: BackupService? = null,
) {

    data class Snapshot(
        val groups: List<RuleGroup> = emptyList(),
        val results: Map<String, StoredResult> = emptyMap(),
        val verifiedAt: Long = 0L,
        val loading: Boolean = true,
        /** Set when the rule document could not be read, so the UI can offer recovery. */
        val loadError: String? = null,
    )

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { ruleStore.load() }
        val verification = withContext(Dispatchers.IO) { resultStore.load() }
        _snapshot.value = Snapshot(
            groups = (loaded as? RuleLoadResult.Success)?.groups.orEmpty(),
            results = verification.results,
            verifiedAt = verification.verifiedAt,
            loading = false,
            loadError = (loaded as? RuleLoadResult.Corrupt)?.toMessage(),
        )
    }

    private fun RuleLoadResult.Corrupt.toMessage(): String {
        val preserved = preservedAs?.let { "已保留副本 $it。" }.orEmpty()
        return "规则文件无法解析：$message。${preserved}请从备份恢复或重新导入。"
    }

    suspend fun createGroup(
        name: String,
        description: String = "",
        source: EventSource = EventSource.UI,
    ): RuleGroup = locked {
        val group = RuleGroup(
            id = "group-${UUID.randomUUID().toString().take(8)}",
            name = name.ifBlank { "未命名规则组" },
            description = description,
            rules = emptyList(),
        )
        persistGroups(_snapshot.value.groups + group)
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.CREATE,
                groupId = group.id,
                groupName = group.name,
                outcome = "created",
            ),
        )
        group
    }

    suspend fun updateGroupInfo(
        groupId: String,
        name: String,
        description: String,
        source: EventSource = EventSource.UI,
    ) = locked {
        val updated = _snapshot.value.groups.map {
            if (it.id == groupId) it.copy(name = name.ifBlank { it.name }, description = description) else it
        }
        persistGroups(updated)
        val group = updated.firstOrNull { it.id == groupId }
        eventLog.record(group?.let {
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.UPDATE,
                groupId = it.id,
                groupName = it.name,
                outcome = "updated",
            )
        } ?: noopEvent(source, EventAction.UPDATE, groupId = groupId))
    }

    suspend fun deleteGroup(groupId: String, source: EventSource = EventSource.UI) = locked {
        val group = _snapshot.value.groups.firstOrNull { it.id == groupId } ?: return@locked
        val remaining = _snapshot.value.groups.filterNot { it.id == groupId }
        persistGroups(remaining)
        val ruleIds = group.rules.map { it.id }.toSet()
        if (ruleIds.isNotEmpty()) {
            persistResults(_snapshot.value.results - ruleIds, _snapshot.value.verifiedAt)
        }
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.DELETE,
                groupId = group.id,
                groupName = group.name,
                outcome = "deleted",
                detail = "rules=${group.rules.size}",
            ),
        )
    }

    /** Create or update a rule and, when it is active, apply it immediately. */
    suspend fun upsertRule(
        groupId: String,
        rule: Rule,
        source: EventSource = EventSource.UI,
    ): StoredResult? = locked {
        val current = _snapshot.value.groups
        val group = current.firstOrNull { it.id == groupId } ?: return@locked null
        val exists = group.rules.any { it.id == rule.id }
        val groups = current.map {
            if (it.id != groupId) {
                it
            } else {
                it.withRules(
                    if (exists) it.rules.map { existing -> if (existing.id == rule.id) rule else existing }
                    else it.rules + rule,
                )
            }
        }
        val updatedGroup = groups.first { it.id == groupId }
        persistGroups(groups)
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = if (exists) EventAction.UPDATE else EventAction.CREATE,
                groupId = updatedGroup.id,
                groupName = updatedGroup.name,
                ruleId = rule.id,
                ruleName = rule.name,
                targetPackage = rule.targetPackage,
                outcome = "saved",
            ),
        )
        if (!rule.enabled || !updatedGroup.enabled) return@locked null

        val checkedAt = System.currentTimeMillis()
        val result = engine.apply(rule)
        eventLog.record(result.toEvent(source, EventAction.APPLY, updatedGroup, rule))
        val stored = result.toStored(checkedAt)
        persistResults(_snapshot.value.results + (rule.id to stored), checkedAt)
        stored
    }

    /** Deletes several groups in one write, so a bulk delete produces one snapshot. */
    suspend fun deleteGroups(groupIds: Set<String>, source: EventSource = EventSource.UI) = locked {
        if (groupIds.isEmpty()) return@locked
        val removed = _snapshot.value.groups.filter { it.id in groupIds }
        if (removed.isEmpty()) return@locked
        persistGroups(_snapshot.value.groups.filterNot { it.id in groupIds })
        val ruleIds = removed.flatMap { it.rules }.map { it.id }.toSet()
        if (ruleIds.isNotEmpty()) {
            persistResults(_snapshot.value.results - ruleIds, _snapshot.value.verifiedAt)
        }
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.DELETE,
                outcome = "deleted",
                detail = "groups=${removed.size} rules=${ruleIds.size}",
            ),
        )
    }

    /** Deletes several rules in one write. */
    suspend fun deleteRules(ruleIds: Set<String>, source: EventSource = EventSource.UI) = locked {
        if (ruleIds.isEmpty()) return@locked
        val existing = _snapshot.value.groups.flatMap { it.rules }.count { it.id in ruleIds }
        if (existing == 0) return@locked
        persistGroups(
            _snapshot.value.groups.map { group ->
                group.withRules(group.rules.filterNot { it.id in ruleIds })
            },
        )
        persistResults(_snapshot.value.results - ruleIds, _snapshot.value.verifiedAt)
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.DELETE,
                outcome = "deleted",
                detail = "rules=$existing",
            ),
        )
    }

    suspend fun deleteRule(ruleId: String, source: EventSource = EventSource.UI) = locked {
        val group = _snapshot.value.groups.firstOrNull { candidate -> candidate.rules.any { it.id == ruleId } }
        val rule = group?.rules?.firstOrNull { it.id == ruleId }
        persistGroups(_snapshot.value.groups.map { it.withRules(it.rules.filterNot { r -> r.id == ruleId }) })
        _snapshot.update { it.copy(results = it.results - ruleId) }
        persistResults(_snapshot.value.results, _snapshot.value.verifiedAt)
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.DELETE,
                groupId = group?.id,
                groupName = group?.name,
                ruleId = ruleId,
                ruleName = rule?.name,
                outcome = "deleted",
            ),
        )
    }

    suspend fun setRuleEnabled(
        ruleId: String,
        enabled: Boolean,
        source: EventSource = EventSource.UI,
    ) = locked {
        val group = _snapshot.value.groups.firstOrNull { candidate -> candidate.rules.any { it.id == ruleId } }
        val rule = group?.rules?.firstOrNull { it.id == ruleId }
        persistGroups(
            _snapshot.value.groups.map { candidate ->
                candidate.withRules(
                    candidate.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it },
                )
            },
        )
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = if (enabled) EventAction.ENABLE else EventAction.DISABLE,
                groupId = group?.id,
                groupName = group?.name,
                ruleId = ruleId,
                ruleName = rule?.name,
                outcome = if (enabled) "enabled" else "disabled",
            ),
        )
    }

    suspend fun setGroupEnabled(
        groupId: String,
        enabled: Boolean,
        source: EventSource = EventSource.UI,
    ) {
        locked {
            val updated = _snapshot.value.groups.map { if (it.id == groupId) it.copy(enabled = enabled) else it }
            persistGroups(updated)
            val group = updated.firstOrNull { it.id == groupId }
            eventLog.record(
                LatchEvent(
                    level = EventLevel.INFO,
                    source = source,
                    action = if (enabled) EventAction.ENABLE else EventAction.DISABLE,
                    groupId = groupId,
                    groupName = group?.name,
                    outcome = if (enabled) "enabled" else "disabled",
                ),
            )
        }
        if (enabled) applyGroup(groupId, source)
    }

    suspend fun applyGroup(
        groupId: String,
        source: EventSource = EventSource.UI,
    ): List<StoredResult> = locked {
        val group = _snapshot.value.groups.firstOrNull { it.id == groupId } ?: return@locked emptyList()
        val started = System.currentTimeMillis()
        val checkedAt = System.currentTimeMillis()
        val outcomes = group.rules.filter { it.enabled }.map { rule -> rule to engine.apply(rule) }
        outcomes.forEach { (rule, result) -> eventLog.record(result.toEvent(source, EventAction.APPLY, group, rule)) }
        eventLog.record(
            batchEvent(source, EventAction.APPLY, group, outcomes.map { it.second }, System.currentTimeMillis() - started),
        )

        val updated = outcomes.associate { (rule, result) -> rule.id to result.toStored(checkedAt) }
        if (updated.isNotEmpty()) {
            persistResults(_snapshot.value.results + updated, checkedAt)
        }
        updated.values.toList()
    }

    suspend fun verifyAll(source: EventSource = EventSource.UI): VerificationSnapshot {
        val verification = verifyService.verifyAll(source)
        _snapshot.update { it.copy(results = verification.results, verifiedAt = verification.verifiedAt) }
        return verification
    }

    suspend fun verifyAndRepair(source: EventSource = EventSource.UI): VerifyService.RepairReport {
        val report = verifyService.verifyAndRepair(source)
        val stored = resultStore.load()
        _snapshot.update { it.copy(results = stored.results, verifiedAt = stored.verifiedAt) }
        return report
    }

    /**
     * Replaces the whole document with [groups] and drops results for rules that no
     * longer exist. Results of surviving rules are kept; restored rules will show as
     * pending until the next verification.
     */
    suspend fun restore(groups: List<RuleGroup>) = locked {
        persistGroups(groups)
        val validRuleIds = groups.flatMap { it.rules }.map { it.id }.toSet()
        persistResults(
            _snapshot.value.results.filterKeys { it in validRuleIds },
            _snapshot.value.verifiedAt,
        )
    }

    suspend fun importGroups(
        groups: List<RuleGroup>,
        source: EventSource = EventSource.UI,
    ) = locked {
        val merged = _snapshot.value.groups.associateBy { it.id }.toMutableMap()
        groups.forEach { merged[it.id] = it }
        persistGroups(merged.values.toList())
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.IMPORT,
                outcome = "ok",
                detail = "groups=${groups.size} rules=${groups.sumOf { it.rules.size }}",
            ),
        )
    }

    fun exportJson(): String = RuleCodec.encode(_snapshot.value.groups)

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private suspend fun persistGroups(groups: List<RuleGroup>) {
        ruleStore.save(groups)
        _snapshot.update { it.copy(groups = groups) }
        backupService?.onDocumentChanged()
    }

    private fun persistResults(results: Map<String, StoredResult>, verifiedAt: Long) {
        resultStore.save(VerificationSnapshot(results, verifiedAt))
        _snapshot.update { it.copy(results = results, verifiedAt = verifiedAt) }
    }

    private fun noopEvent(source: EventSource, action: EventAction, groupId: String? = null) =
        LatchEvent(level = EventLevel.WARN, source = source, action = action, groupId = groupId, outcome = "missed")
}
