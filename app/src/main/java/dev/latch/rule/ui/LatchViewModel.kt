package dev.latch.rule.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.latch.rule.AppContainer
import dev.latch.rule.R
import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.RestorePlan
import dev.latch.rule.core.backup.RestoreScope
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleOverride
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.rules.RuleConflict
import dev.latch.rule.core.rules.RuleValidation
import dev.latch.rule.core.stats.StatsSnapshot
import dev.latch.rule.data.BackupCodec
import dev.latch.rule.data.LatchSettings
import dev.latch.rule.data.RuleCodec
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot
import dev.latch.rule.gateway.AgentGateway
import dev.latch.rule.gateway.AgentGatewayService
import dev.latch.rule.privilege.PrivilegeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LatchViewModel(private val container: AppContainer) : ViewModel() {

    data class GroupSummary(
        val state: RuleState,
        val total: Int,
        val applied: Int,
        val drifted: Int,
        val failed: Int,
        val pending: Int,
        val disabled: Int,
        val suppressed: Int = 0,
        val blocked: Int = 0,
    )

    /** A save waiting for the user to choose how to resolve a conflict. */
    data class PendingConflict(
        val groupId: String,
        val rule: Rule,
        val conflicts: List<RuleConflict>,
    )

    data class UiState(
        val privilege: PrivilegeStatus = PrivilegeStatus.UNAVAILABLE,
        val groups: List<RuleGroup> = emptyList(),
        val results: Map<String, StoredResult> = emptyMap(),
        val verifiedAt: Long = 0L,
        val busyGroupId: String? = null,
        val verifyingAll: Boolean = false,
        val loading: Boolean = true,
        val settings: LatchSettings = LatchSettings(),
        val message: String? = null,
        val openGroupId: String? = null,
        val gateway: AgentGateway.State = AgentGateway.State(),
        val logs: List<LatchEvent> = emptyList(),
        val logsLoading: Boolean = false,
        val backups: List<BackupMeta> = emptyList(),
        val backupsLoading: Boolean = false,
        val restorePreview: RestorePlan? = null,
        val restoring: Boolean = false,
        val stats: StatsSnapshot? = null,
        val statsLoading: Boolean = false,
        /** rule id -> name of the rule overriding it. */
        val suppressedBy: Map<String, String> = emptyMap(),
        /** rule id -> reason the target policy skips it. */
        val blocked: Map<String, String> = emptyMap(),
        val pendingConflict: PendingConflict? = null,
        /** Changes when a rule save completes, so the editor can close. */
        val savedRuleToken: Long = 0L,
    ) {
        fun resultOf(ruleId: String): StoredResult? = results[ruleId]

        fun stateOf(ruleId: String, fallbackEnabled: Boolean): RuleState {
            if (!fallbackEnabled) return RuleState.DISABLED
            return results[ruleId]?.state ?: RuleState.PENDING
        }

        fun summary(group: RuleGroup): GroupSummary {
            if (!group.enabled) {
                return GroupSummary(
                    RuleState.DISABLED, group.rules.size, 0, 0, 0, 0, group.rules.size,
                )
            }
            var applied = 0
            var drifted = 0
            var failed = 0
            var pending = 0
            var disabled = 0
            var suppressed = 0
            var blockedCount = 0
            group.rules.forEach { rule ->
                if (!rule.enabled) {
                    disabled++
                    return@forEach
                }
                if (suppressedBy.containsKey(rule.id)) {
                    suppressed++
                    return@forEach
                }
                if (blocked.containsKey(rule.id)) {
                    blockedCount++
                    return@forEach
                }
                when (results[rule.id]?.state) {
                    RuleState.APPLIED -> applied++
                    RuleState.DRIFTED -> drifted++
                    RuleState.FAILED -> failed++
                    RuleState.DISABLED -> disabled++
                    else -> pending++
                }
            }
            val state = when {
                failed > 0 -> RuleState.FAILED
                drifted > 0 -> RuleState.DRIFTED
                pending > 0 -> RuleState.PENDING
                suppressed > 0 || blockedCount > 0 -> RuleState.PENDING
                applied > 0 -> RuleState.APPLIED
                else -> RuleState.DISABLED
            }
            return GroupSummary(
                state, group.rules.size, applied, drifted, failed, pending, disabled,
                suppressed, blockedCount,
            )
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.ruleCoordinator.load()
            _uiState.update {
                it.copy(
                    privilege = container.privilegeBroker.status(),
                    settings = container.settingsStore.load(),
                    loading = false,
                    message = container.ruleCoordinator.snapshot.value.loadError ?: it.message,
                )
            }
            if (_uiState.value.settings.autoVerifyOnOpen &&
                _uiState.value.privilege == PrivilegeStatus.READY
            ) {
                verifyAll(announce = false)
            }
        }
        viewModelScope.launch {
            container.ruleCoordinator.snapshot.collect { snapshot ->
                val names = snapshot.groups
                    .asSequence()
                    .flatMap { it.rules.asSequence() }
                    .associate { it.id to it.name }
                val suppressedBy = snapshot.suppressed
                    .mapValues { (_, winnerId) -> names[winnerId] ?: winnerId }
                _uiState.update {
                    it.copy(
                        groups = snapshot.groups,
                        results = snapshot.results,
                        verifiedAt = snapshot.verifiedAt,
                        loading = snapshot.loading,
                        suppressedBy = suppressedBy,
                        blocked = snapshot.blocked,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.agentGateway.state.collect { gateway ->
                _uiState.update { it.copy(gateway = gateway) }
            }
        }
    }

    fun refreshPrivilege(openShizukuOnFailure: Boolean = false) {
        val status = container.privilegeBroker.status()
        _uiState.update { it.copy(privilege = status) }
        if (openShizukuOnFailure && status != PrivilegeStatus.READY) {
            openShizuku()
        }
    }

    fun openShizuku() {
        val intent = container.appContext.packageManager
            .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            _uiState.update { it.copy(message = text(R.string.msg_shizuku_missing)) }
        } else {
            runCatching { container.appContext.startActivity(intent) }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        refreshPrivilege()
        if (granted) verifyAll(announce = true)
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeOpenGroup() {
        _uiState.update { it.copy(openGroupId = null) }
    }

    // region rule groups and rules

    fun createGroup(name: String, description: String) {
        viewModelScope.launch {
            val group = container.ruleCoordinator.createGroup(name, description)
            _uiState.update { it.copy(openGroupId = group.id) }
        }
    }

    fun updateGroupInfo(groupId: String, name: String, description: String) {
        viewModelScope.launch {
            container.ruleCoordinator.updateGroupInfo(groupId, name, description)
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { container.ruleCoordinator.deleteGroup(groupId) }
    }

    /**
     * Saves a rule. When it contradicts existing rules the save is held back and
     * [UiState.pendingConflict] is set so the user can choose a side.
     */
    fun upsertRule(groupId: String, rule: Rule) {
        container.targetPolicy.blockReason(rule)?.let { reason ->
            _uiState.update { it.copy(message = text(R.string.msg_not_saved, reason)) }
            return
        }
        viewModelScope.launch {
            val conflicts = container.ruleCoordinator.conflictsFor(groupId, rule)
            if (conflicts.isEmpty()) {
                persistRule(groupId, rule, emptyList(), suppressed = false)
            } else {
                _uiState.update {
                    it.copy(pendingConflict = PendingConflict(groupId, rule, conflicts))
                }
            }
        }
    }

    /** [overwrite] true: the new rule wins; false: the existing rules keep winning. */
    fun resolveConflict(overwrite: Boolean) {
        val pending = _uiState.value.pendingConflict ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val overrides = pending.conflicts.map { conflict ->
                if (overwrite) {
                    RuleOverride(conflict.existingRuleId, pending.rule.id, now)
                } else {
                    RuleOverride(pending.rule.id, conflict.existingRuleId, now)
                }
            }
            persistRule(pending.groupId, pending.rule, overrides, suppressed = !overwrite)
        }
    }

    fun cancelConflict() {
        _uiState.update { it.copy(pendingConflict = null) }
    }

    private suspend fun persistRule(
        groupId: String,
        rule: Rule,
        overrides: List<RuleOverride>,
        suppressed: Boolean,
    ) {
        val result = container.ruleCoordinator.upsertRule(groupId, rule, overrides)
        val message = when {
            result != null -> summarize(rule.name, listOf(result))
            suppressed -> text(R.string.msg_saved_covered)
            else -> null
        }
        _uiState.update {
            it.copy(
                pendingConflict = null,
                savedRuleToken = System.currentTimeMillis(),
                message = message ?: it.message,
            )
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch { container.ruleCoordinator.deleteRule(ruleId) }
    }

    fun deleteGroups(groupIds: Set<String>) {
        viewModelScope.launch { container.ruleCoordinator.deleteGroups(groupIds) }
    }

    fun deleteRules(ruleIds: Set<String>) {
        viewModelScope.launch { container.ruleCoordinator.deleteRules(ruleIds) }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { container.ruleCoordinator.setRuleEnabled(ruleId, enabled) }
    }

    fun setGroupEnabled(groupId: String, enabled: Boolean) {
        viewModelScope.launch { container.ruleCoordinator.setGroupEnabled(groupId, enabled) }
    }

    // endregion

    // region apply and verify

    fun applyGroup(groupId: String) {
        val group = _uiState.value.groups.firstOrNull { it.id == groupId } ?: return
        if (_uiState.value.busyGroupId != null) return
        if (group.rules.none { it.enabled }) {
            _uiState.update { it.copy(message = text(R.string.msg_no_enabled_rules, group.name)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busyGroupId = groupId) }
            try {
                val results = container.ruleCoordinator.applyGroup(groupId)
                _uiState.update { it.copy(message = summarize(group.name, results)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update { it.copy(message = text(R.string.msg_apply_failed, error.message ?: text(R.string.msg_unknown_error))) }
            } finally {
                _uiState.update { it.copy(busyGroupId = null) }
            }
        }
    }

    fun verifyAll(announce: Boolean = true) {
        if (_uiState.value.verifyingAll) return
        viewModelScope.launch {
            _uiState.update { it.copy(verifyingAll = true) }
            try {
                val snapshot = container.ruleCoordinator.verifyAll()
                if (announce) {
                    _uiState.update { it.copy(message = summarizeAll(snapshot)) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update { it.copy(message = text(R.string.msg_verify_failed, error.message ?: text(R.string.msg_unknown_error))) }
            } finally {
                _uiState.update { it.copy(verifyingAll = false) }
            }
        }
    }

    // endregion

    // region agent gateway

    fun startAgentGateway() {
        // Only keep a foreground service alive when the socket actually bound.
        if (!container.agentGateway.start()) return
        ContextCompat.startForegroundService(
            container.appContext,
            Intent(container.appContext, AgentGatewayService::class.java),
        )
    }

    fun stopAgentGateway() {
        container.agentGateway.stop()
        container.appContext.stopService(
            Intent(container.appContext, AgentGatewayService::class.java),
        )
    }

    // endregion

    // region settings

    fun updateSettings(settings: LatchSettings) {
        container.updateSettings(settings)
        _uiState.update { it.copy(settings = settings) }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!_uiState.value.settings.notifyOnDrift) return
        if (!granted) {
            updateSettings(_uiState.value.settings.copy(notifyOnDrift = false))
        }
    }

    // endregion

    // region import and export

    fun importJson(text: String) {
        val document = runCatching { RuleCodec.decode(text) }.getOrElse { error ->
            _uiState.update { it.copy(message = text(R.string.msg_import_failed, error.message ?: text(R.string.msg_unknown_error))) }
            return
        }
        if (document.groups.isEmpty()) {
            _uiState.update { it.copy(message = text(R.string.msg_no_groups_in_file)) }
            return
        }
        val problems = RuleValidation.validate(document, container.targetPolicy)
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    message = text(
                        R.string.msg_import_rejected,
                        problems.take(3).joinToString("；") +
                            if (problems.size > 3) "…" else "",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            container.ruleCoordinator.importDocument(document)
            _uiState.update {
                it.copy(
                    message = text(
                        R.string.msg_imported,
                        document.groups.size,
                        document.rules().size,
                    ),
                )
            }
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    container.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                _uiState.update { it.copy(message = text(R.string.msg_read_failed)) }
            } else {
                importJson(text)
            }
        }
    }

    fun exportJson(): String = container.ruleCoordinator.exportJson()

    fun exportToUri(uri: Uri) {
        val payload = exportJson()
        viewModelScope.launch {
            val ok = writeText(uri, payload)
            _uiState.update {
                it.copy(
                    message = if (ok) {
                        text(R.string.msg_exported_groups, _uiState.value.groups.size)
                    } else {
                        text(R.string.msg_export_failed)
                    },
                )
            }
        }
    }

    fun exportLogsToUri(uri: Uri) {
        viewModelScope.launch {
            val text = container.fileEventLog.exportText()
            val ok = text.isNotEmpty() && writeText(uri, text)
            _uiState.update { it.copy(message = text(if (ok) R.string.msg_logs_exported else R.string.msg_no_logs)) }
        }
    }

    fun exportBackupToUri(snapshotId: String, uri: Uri) {
        viewModelScope.launch {
            val snapshot = container.backupService.load(snapshotId)
            val ok = snapshot != null && writeText(uri, BackupCodec.encode(snapshot))
            _uiState.update { it.copy(message = text(if (ok) R.string.msg_backup_exported else R.string.msg_export_failed)) }
        }
    }

    private suspend fun writeText(uri: Uri, text: String): Boolean =
        runCatching {
            withContext(Dispatchers.IO) {
                val stream = container.contentResolver.openOutputStream(uri) ?: return@withContext false
                stream.bufferedWriter().use { it.write(text) }
                true
            }
        }.getOrDefault(false)

    // endregion

    // region event log

    fun loadLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(logsLoading = true) }
            try {
                val logs = container.eventLog.recent(LOG_PAGE_SIZE)
                _uiState.update { it.copy(logs = logs) }
            } finally {
                _uiState.update { it.copy(logsLoading = false) }
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            container.eventLog.clear()
            _uiState.update { it.copy(logs = emptyList()) }
            _uiState.update { it.copy(message = text(R.string.msg_logs_cleared)) }
        }
    }

    // endregion

    // region backups

    fun loadBackups() {
        viewModelScope.launch {
            _uiState.update { it.copy(backupsLoading = true) }
            try {
                val backups = container.backupService.list()
                _uiState.update { it.copy(backups = backups) }
            } finally {
                _uiState.update { it.copy(backupsLoading = false) }
            }
        }
    }

    fun createBackup() {
        viewModelScope.launch {
            val meta = container.backupService.snapshotNow(BackupReason.MANUAL)
            _uiState.update { it.copy(message = text(if (meta != null) R.string.msg_backup_created else R.string.msg_nothing_to_backup)) }
            loadBackups()
        }
    }

    fun previewRestore(snapshotId: String, groupIds: Set<String>?) {
        viewModelScope.launch {
            val plan = container.restoreService.preview(snapshotId, scopeOf(groupIds))
            _uiState.update { it.copy(restorePreview = plan) }
        }
    }

    fun clearRestorePreview() {
        _uiState.update { it.copy(restorePreview = null) }
    }

    fun restoreBackup(snapshotId: String, groupIds: Set<String>?, apply: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(restoring = true) }
            try {
                val plan = container.restoreService.restore(snapshotId, scopeOf(groupIds), apply)
                val message = when {
                    plan == null -> text(R.string.msg_snapshot_missing)
                    plan.affectedGroupIds.isEmpty() -> text(R.string.msg_no_changes)
                    else -> text(
                        if (apply) R.string.msg_restored_applied else R.string.msg_restored,
                        plan.groups.size,
                        plan.ruleCount,
                    )
                }
                _uiState.update { it.copy(restorePreview = null, message = message) }
            } finally {
                _uiState.update { it.copy(restoring = false) }
                loadBackups()
            }
        }
    }

    private fun scopeOf(groupIds: Set<String>?): RestoreScope =
        if (groupIds == null) RestoreScope.Full else RestoreScope.Selected(groupIds)

    // endregion

    // region statistics

    fun loadStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(statsLoading = true) }
            try {
                val stats = container.statsService.refresh()
                _uiState.update { it.copy(stats = stats) }
            } finally {
                _uiState.update { it.copy(statsLoading = false) }
            }
        }
    }

    fun resetStats() {
        viewModelScope.launch {
            container.statsService.reset()
            val stats = container.statsService.refresh()
            _uiState.update {
                it.copy(stats = stats, logs = emptyList(), message = text(R.string.msg_stats_reset))
            }
        }
    }

    // endregion

    suspend fun loadInstalledApps() = withContext(Dispatchers.IO) { container.installedApps.all() }

    private fun summarize(label: String, results: Collection<StoredResult>): String {
        val applied = results.count { it.state == RuleState.APPLIED }
        val drifted = results.count { it.state == RuleState.DRIFTED }
        val failed = results.count { it.state == RuleState.FAILED }
        return buildString {
            append(text(R.string.msg_rule_summary, label, applied, results.size))
            if (drifted > 0) append(text(R.string.msg_rule_drifted, drifted))
            if (failed > 0) append(text(R.string.msg_rule_failed, failed))
        }
    }

    private fun summarizeAll(snapshot: VerificationSnapshot): String {
        val values = snapshot.results.values
        val applied = values.count { it.state == RuleState.APPLIED }
        val drifted = values.count { it.state == RuleState.DRIFTED }
        val failed = values.count { it.state == RuleState.FAILED }
        return buildString {
            append(text(R.string.msg_all_summary, applied, values.size))
            if (drifted > 0) append(text(R.string.msg_rule_drifted, drifted))
            if (failed > 0) append(text(R.string.msg_rule_failed, failed))
        }
    }

    private fun text(id: Int, vararg args: Any): String =
        container.appContext.getString(id, *args)

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val LOG_PAGE_SIZE = 300

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LatchViewModel(container) }
        }
    }
}
