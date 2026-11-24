package dev.latch.rule.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.latch.rule.data.InstalledApp
import dev.latch.rule.ui.screens.BackupsScreen
import dev.latch.rule.ui.screens.GroupScreen
import dev.latch.rule.ui.screens.HomeScreen
import dev.latch.rule.ui.screens.LogsScreen
import dev.latch.rule.ui.screens.RuleEditorScreen
import dev.latch.rule.ui.screens.SettingsScreen
import dev.latch.rule.ui.screens.StatsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LatchApp(
    viewModel: LatchViewModel,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.settings.hideFromRecents) {
        RecentsHider.apply(context as? android.app.Activity, state.settings.hideFromRecents)
    }

    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var logsOpen by rememberSaveable { mutableStateOf(false) }
    var backupsOpen by rememberSaveable { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importFromUri(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) viewModel.exportToUri(uri)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    LaunchedEffect(state.openGroupId) {
        val groupId = state.openGroupId ?: return@LaunchedEffect
        selectedGroupId = groupId
        viewModel.consumeOpenGroup()
    }

    LaunchedEffect(editorOpen) {
        if (editorOpen && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) { viewModel.loadInstalledApps() }
        }
    }

    LaunchedEffect(logsOpen) {
        if (logsOpen) viewModel.loadLogs()
    }

    LaunchedEffect(backupsOpen) {
        if (backupsOpen) viewModel.loadBackups()
    }

    LaunchedEffect(statsOpen) {
        if (statsOpen) viewModel.loadStats()
    }

    // Closing only after a save completes keeps the editor open while a conflict
    // is being resolved.
    LaunchedEffect(state.savedRuleToken) {
        if (state.savedRuleToken > 0L) editorOpen = false
    }

    BackHandler(enabled = editorOpen) { editorOpen = false }
    BackHandler(enabled = !editorOpen && logsOpen) { logsOpen = false }
    BackHandler(enabled = !editorOpen && !logsOpen && backupsOpen) { backupsOpen = false }
    BackHandler(enabled = !editorOpen && !logsOpen && !backupsOpen && statsOpen) { statsOpen = false }
    BackHandler(enabled = !editorOpen && !logsOpen && !backupsOpen && !statsOpen && settingsOpen) {
        settingsOpen = false
    }
    BackHandler(
        enabled = !editorOpen && !logsOpen && !backupsOpen && !statsOpen &&
            !settingsOpen && selectedGroupId != null,
    ) {
        selectedGroupId = null
    }

    val selectedGroup = state.groups.firstOrNull { it.id == selectedGroupId }
    val editingRule = selectedGroup?.rules?.firstOrNull { it.id == editorRuleId }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            editorOpen && selectedGroup != null -> RuleEditorScreen(
                apps = apps,
                initialRule = editingRule,
                onSave = { rule -> viewModel.upsertRule(selectedGroup.id, rule) },
                onDelete = editingRule?.let { rule ->
                    { ruleId ->
                        viewModel.deleteRule(ruleId)
                        editorOpen = false
                    }
                },
                onBack = { editorOpen = false },
            )

            // Checked before settings: the log screen is opened from there and the
            // settings flag stays set so Back returns to it.
            logsOpen -> LogsScreen(
                logs = state.logs,
                loading = state.logsLoading,
                onBack = { logsOpen = false },
                onRefresh = viewModel::loadLogs,
                onClear = viewModel::clearLogs,
            )

            backupsOpen -> BackupsScreen(
                backups = state.backups,
                groups = state.groups,
                loading = state.backupsLoading,
                restoring = state.restoring,
                preview = state.restorePreview,
                onBack = { backupsOpen = false },
                onRefresh = viewModel::loadBackups,
                onCreate = viewModel::createBackup,
                onPreview = viewModel::previewRestore,
                onClearPreview = viewModel::clearRestorePreview,
                onRestore = viewModel::restoreBackup,
            )

            statsOpen -> StatsScreen(
                state = state,
                onBack = { statsOpen = false },
                onRefresh = viewModel::loadStats,
                onReset = viewModel::resetStats,
            )

            settingsOpen -> SettingsScreen(
                state = state,
                onBack = { settingsOpen = false },
                onRequestPermission = onRequestPermission,
                onRefreshPrivilege = { viewModel.refreshPrivilege(openShizukuOnFailure = true) },
                onOpenShizuku = viewModel::openShizuku,
                onUpdateSettings = viewModel::updateSettings,
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.onNotificationPermissionResult(true)
                    }
                },
                onImportJson = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                onExportJson = { exportLauncher.launch("latch-rules.json") },
                onStartAgent = viewModel::startAgentGateway,
                onStopAgent = viewModel::stopAgentGateway,
                onOpenLogs = { logsOpen = true },
                onOpenBackups = { backupsOpen = true },
                onOpenStats = { statsOpen = true },
            )

            selectedGroup != null -> GroupScreen(
                group = selectedGroup,
                state = state,
                onBack = { selectedGroupId = null },
                onAddRule = {
                    editorRuleId = null
                    editorOpen = true
                },
                onEditRule = { ruleId ->
                    editorRuleId = ruleId
                    editorOpen = true
                },
                onToggleRule = viewModel::setRuleEnabled,
                onApply = { viewModel.applyGroup(selectedGroup.id) },
                onChangeGroupInfo = { name, description ->
                    viewModel.updateGroupInfo(selectedGroup.id, name, description)
                },
                onDeleteGroup = {
                    viewModel.deleteGroup(selectedGroup.id)
                    selectedGroupId = null
                },
                onDeleteRules = viewModel::deleteRules,
            )

            else -> HomeScreen(
                state = state,
                onNewGroup = viewModel::createGroup,
                onRequestPermission = onRequestPermission,
                onRefreshPrivilege = { viewModel.refreshPrivilege(openShizukuOnFailure = true) },
                onOpenShizuku = viewModel::openShizuku,
                onGroupClick = { selectedGroupId = it },
                onGroupToggle = viewModel::setGroupEnabled,
                onVerifyAll = { viewModel.verifyAll() },
                onImportJson = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                onOpenSettings = { settingsOpen = true },
                onDeleteGroups = viewModel::deleteGroups,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    state.pendingConflict?.let { pending ->
        ConflictDialog(
            pending = pending,
            onResolve = viewModel::resolveConflict,
            onDismiss = viewModel::cancelConflict,
        )
    }
}

@Composable
private fun ConflictDialog(
    pending: LatchViewModel.PendingConflict,
    onResolve: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var overwrite by remember(pending.rule.id) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则冲突") },
        text = {
            Column {
                Text("新规则与以下现有规则声明了不同的值：")
                Spacer(Modifier.height(8.dp))
                pending.conflicts.take(6).forEach { conflict ->
                    Text(
                        "· ${conflict.existingRuleName} · ${conflict.summary}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (pending.conflicts.size > 6) {
                    Text(
                        "… 还有 ${pending.conflicts.size - 6} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = overwrite,
                        onClick = { overwrite = true },
                        label = { Text("新规则覆盖") },
                    )
                    FilterChip(
                        selected = !overwrite,
                        onClick = { overwrite = false },
                        label = { Text("保留现有") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (overwrite) {
                        "保存后，现有规则显示为被新规则覆盖。"
                    } else {
                        "保存后，新规则显示为被现有规则覆盖，暂不生效。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(overwrite) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
