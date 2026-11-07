package dev.latch.rule.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.latch.rule.AppContainer
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.data.LatchSettings
import dev.latch.rule.data.RuleCodec
import dev.latch.rule.data.StoredResult
import dev.latch.rule.data.VerificationSnapshot
import dev.latch.rule.gateway.AgentGateway
import dev.latch.rule.gateway.AgentGatewayService
import dev.latch.rule.privilege.PrivilegeStatus
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
    ) {
        fun resultOf(ruleId: String): StoredResult? = results[ruleId]

        fun stateOf(ruleId: String, fallbackEnabled: Boolean): RuleState {
            if (!fallbackEnabled) return RuleState.DISABLED
            return results[ruleId]?.state ?: RuleState.PENDING
        }

        fun summary(group: RuleGroup): GroupSummary {
            if (!group.enabled) {
                return GroupSummary(RuleState.DISABLED, group.rules.size, 0, 0, 0, 0, group.rules.size)
            }
            var applied = 0
            var drifted = 0
            var failed = 0
            var pending = 0
            var disabled = 0
            group.rules.forEach { rule ->
                if (!rule.enabled) {
                    disabled++
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
                applied > 0 -> RuleState.APPLIED
                else -> RuleState.DISABLED
            }
            return GroupSummary(state, group.rules.size, applied, drifted, failed, pending, disabled)
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
                _uiState.update {
                    it.copy(
                        groups = snapshot.groups,
                        results = snapshot.results,
                        verifiedAt = snapshot.verifiedAt,
                        loading = snapshot.loading,
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

    fun refreshPrivilege() {
        _uiState.update { it.copy(privilege = container.privilegeBroker.status()) }
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

    fun upsertRule(groupId: String, rule: Rule) {
        viewModelScope.launch { container.ruleCoordinator.upsertRule(groupId, rule) }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch { container.ruleCoordinator.deleteRule(ruleId) }
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
            _uiState.update { it.copy(message = "${group.name} · 没有启用的规则") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busyGroupId = groupId) }
            val results = container.ruleCoordinator.applyGroup(groupId)
            _uiState.update {
                it.copy(
                    busyGroupId = null,
                    message = summarize(group.name, results),
                )
            }
        }
    }

    fun verifyAll(announce: Boolean = true) {
        if (_uiState.value.verifyingAll) return
        viewModelScope.launch {
            _uiState.update { it.copy(verifyingAll = true) }
            val snapshot = container.ruleCoordinator.verifyAll()
            _uiState.update {
                it.copy(
                    verifyingAll = false,
                    message = if (announce) summarizeAll(snapshot) else it.message,
                )
            }
        }
    }

    // endregion

    // region agent gateway

    fun startAgentGateway() {
        container.agentGateway.start()
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
        val groups = runCatching { RuleCodec.decode(text) }.getOrElse { error ->
            _uiState.update { it.copy(message = "导入失败：${error.message ?: "JSON 格式错误"}") }
            return
        }
        if (groups.isEmpty()) {
            _uiState.update { it.copy(message = "文件中没有规则组") }
            return
        }
        viewModelScope.launch {
            container.ruleCoordinator.importGroups(groups)
            val ruleCount = groups.sumOf { it.rules.size }
            _uiState.update { it.copy(message = "已导入 ${groups.size} 个规则组、$ruleCount 条规则") }
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
                _uiState.update { it.copy(message = "读取文件失败") }
            } else {
                importJson(text)
            }
        }
    }

    fun exportJson(): String = container.ruleCoordinator.exportJson()

    fun exportToUri(uri: Uri) {
        val payload = exportJson()
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    container.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                }
            }.isSuccess
            _uiState.update { it.copy(message = if (ok) "已导出 ${_uiState.value.groups.size} 个规则组" else "导出失败") }
        }
    }

    // endregion

    suspend fun loadInstalledApps() = withContext(Dispatchers.IO) { container.installedApps.all() }

    private fun summarize(label: String, results: Collection<StoredResult>): String {
        val applied = results.count { it.state == RuleState.APPLIED }
        val drifted = results.count { it.state == RuleState.DRIFTED }
        val failed = results.count { it.state == RuleState.FAILED }
        return buildString {
            append(label)
            append(" · $applied/${results.size} 生效")
            if (drifted > 0) append("，$drifted 条漂移")
            if (failed > 0) append("，$failed 条失败")
        }
    }

    private fun summarizeAll(snapshot: VerificationSnapshot): String {
        val values = snapshot.results.values
        val applied = values.count { it.state == RuleState.APPLIED }
        val drifted = values.count { it.state == RuleState.DRIFTED }
        val failed = values.count { it.state == RuleState.FAILED }
        return buildString {
            append("全部规则 · $applied/${values.size} 生效")
            if (drifted > 0) append("，$drifted 条漂移")
            if (failed > 0) append("，$failed 条失败")
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LatchViewModel(container) }
        }
    }
}
