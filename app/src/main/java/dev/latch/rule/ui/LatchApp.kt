package dev.latch.rule.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.latch.rule.data.InstalledApp
import dev.latch.rule.ui.screens.GroupScreen
import dev.latch.rule.ui.screens.HomeScreen
import dev.latch.rule.ui.screens.RuleEditorScreen
import dev.latch.rule.ui.screens.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LatchApp(
    viewModel: LatchViewModel,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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

    BackHandler(enabled = editorOpen) { editorOpen = false }
    BackHandler(enabled = !editorOpen && settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !editorOpen && !settingsOpen && selectedGroupId != null) { selectedGroupId = null }

    val selectedGroup = state.groups.firstOrNull { it.id == selectedGroupId }
    val editingRule = selectedGroup?.rules?.firstOrNull { it.id == editorRuleId }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            editorOpen && selectedGroup != null -> RuleEditorScreen(
                apps = apps,
                initialRule = editingRule,
                onSave = { rule ->
                    viewModel.upsertRule(selectedGroup.id, rule)
                    editorOpen = false
                },
                onDelete = editingRule?.let { rule ->
                    { ruleId ->
                        viewModel.deleteRule(ruleId)
                        editorOpen = false
                    }
                },
                onBack = { editorOpen = false },
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
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
