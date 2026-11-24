package dev.latch.rule.domain

import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride
import dev.latch.rule.core.rules.ConflictResolver
import dev.latch.rule.core.rules.RuleConflict
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
    private val autoReapply: () -> Boolean = { true },
) {

    data class Snapshot(
        val document: RuleDocument = RuleDocument(),
        val results: Map<String, StoredResult> = emptyMap(),
        val verifiedAt: Long = 0L,
        val loading: Boolean = true,
        /** Set when the rule document could not be read, so the UI can offer recovery. */
        val loadError: String? = null,
        /** rule id -> id of the rule that overrides it. */
        val suppressed: Map<String, String> = emptyMap(),
    ) {
        val groups: List<RuleGroup> get() = document.groups
        val overrides: List<RuleOverride> get() = document.overrides
    }

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { ruleStore.load() }
        val verification = withContext(Dispatchers.IO) { resultStore.load() }
        val document = (loaded as? RuleLoadResult.Success)?.document ?: RuleDocument()
        _snapshot.value = Snapshot(
            document = document,
            results = verification.results,
            verifiedAt = verification.verifiedAt,
            loading = false,
            loadError = (loaded as? RuleLoadResult.Corrupt)?.toMessage(),
            suppressed = ConflictResolver.suppressedBy(document),
        )
    }

    private fun RuleLoadResult.Corrupt.toMessage(): String {
        val preserved = preservedAs?.let { "已保留副本 $it。" }.orEmpty()
        return "规则文件无法解析：$message。${preserved}请从备份恢复或重新导入。"
    }

    /** Rules that would conflict with [rule] if it were saved into [groupId]. */
    fun conflictsFor(groupId: String, rule: Rule): List<RuleConflict> {
        val document = _snapshot.value.document
        val group = document.groups.firstOrNull { it.id == groupId } ?: return emptyList()
        return ConflictResolver.conflicts(document, rule, group.enabled)
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
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.UPDATE,
                groupId = groupId,
                groupName = group?.name,
                outcome = "updated",
            ),
        )
    }

    suspend fun deleteGroup(groupId: String, source: EventSource = EventSource.UI) = locked {
        deleteGroupsLocked(setOf(groupId), source)
    }

    /** Deletes several groups in one write, so a bulk delete produces one snapshot. */
    suspend fun deleteGroups(groupIds: Set<String>, source: EventSource = EventSource.UI) = locked {
        deleteGroupsLocked(groupIds, source)
    }

    private suspend fun deleteGroupsLocked(groupIds: Set<String>, source: EventSource) {
        if (groupIds.isEmpty()) return
        val document = _snapshot.value.document
        val removed = document.groups.filter { it.id in groupIds }
        if (removed.isEmpty()) return
        val removedRuleIds = removed.flatMap { it.rules }.map { it.id }.toSet()
        persistDocument(
            RuleDocument(
                groups = document.groups.filterNot { it.id in groupIds },
                overrides = document.overrides.filterNot {
                    it.loserRuleId in removedRuleIds || it.winnerRuleId in removedRuleIds
                },
                revision = document.revision + 1,
            ),
        )
        if (removedRuleIds.isNotEmpty()) {
            persistResults(_snapshot.value.results - removedRuleIds, _snapshot.value.verifiedAt)
        }
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.DELETE,
                outcome = "deleted",
                detail = "groups=${removed.size} rules=${removedRuleIds.size}",
            ),
        )
    }

    /** Create or update a rule and, when it is active, apply it immediately. */
    suspend fun upsertRule(
        groupId: String,
        rule: Rule,
        overrides: List<RuleOverride> = emptyList(),
        source: EventSource = EventSource.UI,
    ): StoredResult? = locked {
        val document = _snapshot.value.document
        val group = document.groups.firstOrNull { it.id == groupId } ?: return@locked null
        val exists = group.rules.any { it.id == rule.id }
        val groups = document.groups.map {
            if (it.id != groupId) {
                it
            } else {
                it.withRules(
                    if (exists) it.rules.map { existing -> if (existing.id == rule.id) rule else existing }
                    else it.rules + rule,
                )
            }
        }
        // Editing a rule invalidates the decisions it took part in.
        val cleaned = document.overrides.filterNot {
            it.loserRuleId == rule.id || it.winnerRuleId == rule.id
        }
        val merged = (cleaned + overrides).distinctBy { it.loserRuleId }
        val updated = RuleDocument(groups, merged, document.revision + 1)
        persistDocument(updated)

        val updatedGroup = updated.groupOf(rule.id) ?: return@locked null
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
        val suppressed = ConflictResolver.suppressedBy(updated)
        if (!rule.enabled || !updatedGroup.enabled || rule.id in suppressed) return@locked null

        val checkedAt = System.currentTimeMillis()
        val result = engine.apply(rule)
        eventLog.record(result.toEvent(source, EventAction.APPLY, updatedGroup, rule))
        val stored = result.toStored(checkedAt)
        persistResults(_snapshot.value.results + (rule.id to stored), checkedAt)
        stored
    }

    suspend fun deleteRule(ruleId: String, source: EventSource = EventSource.UI) = locked {
        deleteRulesLocked(setOf(ruleId), source)
    }

    /** Deletes several rules in one write. */
    suspend fun deleteRules(ruleIds: Set<String>, source: EventSource = EventSource.UI) = locked {
        deleteRulesLocked(ruleIds, source)
    }

    private suspend fun deleteRulesLocked(ruleIds: Set<String>, source: EventSource) {
        if (ruleIds.isEmpty()) return
        val document = _snapshot.value.document
        val existing = document.rules().count { it.id in ruleIds }
        if (existing == 0) return
        persistDocument(
            RuleDocument(
                groups = document.groups.map { group ->
                    group.withRules(group.rules.filterNot { it.id in ruleIds })
                },
                overrides = document.overrides.filterNot {
                    it.loserRuleId in ruleIds || it.winnerRuleId in ruleIds
                },
                revision = document.revision + 1,
            ),
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
        val document = _snapshot.value.document
        val group = document.groups.firstOrNull { it.id == groupId } ?: return@locked emptyList()
        val suppressed = ConflictResolver.suppressedBy(document)
        val started = System.currentTimeMillis()
        val checkedAt = System.currentTimeMillis()
        val outcomes = group.rules
            .filter { it.enabled && it.id !in suppressed }
            .map { rule -> rule to engine.apply(rule) }
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
     * Replaces the whole document and drops results for rules that no longer exist.
     * Restored rules keep no result, so they show as pending until the next check.
     */
    suspend fun restore(document: RuleDocument) = locked {
        val bumped = document.copy(revision = _snapshot.value.document.revision + 1)
        persistDocument(bumped)
        val validRuleIds = bumped.rules().map { it.id }.toSet()
        persistResults(
            _snapshot.value.results.filterKeys { it in validRuleIds },
            _snapshot.value.verifiedAt,
        )
    }

    suspend fun importDocument(
        incoming: RuleDocument,
        source: EventSource = EventSource.UI,
    ) = locked {
        val current = _snapshot.value.document
        val groups = current.groups.associateBy { it.id }.toMutableMap().apply {
            incoming.groups.forEach { put(it.id, it) }
        }
        val overrides = current.overrides.associateBy { it.loserRuleId }.toMutableMap().apply {
            incoming.overrides.forEach { put(it.loserRuleId, it) }
        }
        persistDocument(
            RuleDocument(groups.values.toList(), overrides.values.toList(), current.revision + 1),
        )
        eventLog.record(
            LatchEvent(
                level = EventLevel.INFO,
                source = source,
                action = EventAction.IMPORT,
                outcome = "ok",
                detail = "groups=${incoming.groups.size} rules=${incoming.rules().size}",
            ),
        )
    }

    fun exportJson(): String = RuleCodec.encode(_snapshot.value.document)

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private suspend fun persistGroups(groups: List<RuleGroup>) {
        persistDocument(_snapshot.value.document.withGroups(groups))
    }

    private suspend fun persistDocument(document: RuleDocument) {
        ruleStore.save(document)
        val previous = _snapshot.value
        val suppressed = ConflictResolver.suppressedBy(document)
        _snapshot.update { it.copy(document = document, suppressed = suppressed) }
        backupService?.onDocumentChanged()
        reapplyUnsuppressed(previous, document, suppressed)
    }

    /**
     * When an override stops applying (the winner was disabled, deleted or edited),
     * the rule it covered becomes active again. Applying it here keeps the system
     * state in line with the visible configuration.
     */
    private suspend fun reapplyUnsuppressed(
        previous: Snapshot,
        document: RuleDocument,
        suppressed: Map<String, String>,
    ) {
        if (!autoReapply()) return
        val newlyActive = previous.suppressed.keys - suppressed.keys
        if (newlyActive.isEmpty()) return

        val started = System.currentTimeMillis()
        var results = _snapshot.value.results
        newlyActive.forEach { ruleId ->
            if (!document.isActive(ruleId)) return@forEach
            val rule = document.findRule(ruleId) ?: return@forEach
            val group = document.groupOf(ruleId)
            eventLog.record(
                LatchEvent(
                    level = EventLevel.INFO,
                    source = EventSource.SYSTEM,
                    action = EventAction.ENABLE,
                    groupId = group?.id,
                    groupName = group?.name,
                    ruleId = rule.id,
                    ruleName = rule.name,
                    outcome = "unsuppressed",
                ),
            )
            val result = engine.apply(rule)
            eventLog.record(result.toEvent(EventSource.SYSTEM, EventAction.APPLY, group, rule))
            results = results + (ruleId to result.toStored(started))
        }
        if (results != _snapshot.value.results) {
            resultStore.save(VerificationSnapshot(results, started))
            _snapshot.update { it.copy(results = results, verifiedAt = started) }
        }
    }

    private fun persistResults(results: Map<String, StoredResult>, verifiedAt: Long) {
        resultStore.save(VerificationSnapshot(results, verifiedAt))
        _snapshot.update { it.copy(results = results, verifiedAt = verifiedAt) }
    }
}
