package dev.latch.rule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
import dev.latch.rule.data.LatchSettings
import dev.latch.rule.gateway.AgentGateway
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.components.PairingDialog
import dev.latch.rule.ui.components.ShizukuStatusCard
import dev.latch.rule.ui.theme.LatchDimens

private val PERIODIC_OPTIONS = listOf(
    0 to R.string.settings_period_off,
    15 to R.string.settings_period_15,
    30 to R.string.settings_period_30,
    60 to R.string.settings_period_60,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: LatchViewModel.UiState,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshPrivilege: () -> Unit,
    onOpenShizuku: () -> Unit,
    onUpdateSettings: (LatchSettings) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit,
    onStartAgent: () -> Unit,
    onStopAgent: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    val ruleCount = state.groups.sumOf { it.rules.size }
    var pairingVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.gateway.status) {
        if (state.gateway.status != AgentGateway.Status.WAITING) {
            pairingVisible = false
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LatchDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShizukuStatusCard(
                status = state.privilege,
                onRequestPermission = onRequestPermission,
                onRefresh = onRefreshPrivilege,
                onOpenShizuku = onOpenShizuku,
            )

            SectionTitle(stringResource(R.string.settings_lan))
            when (state.gateway.status) {
                AgentGateway.Status.IDLE -> AgentRow(
                    title = stringResource(R.string.settings_lan_idle),
                    subtitle = stringResource(R.string.settings_lan_idle_sub),
                    actionLabel = stringResource(R.string.settings_connect),
                    onAction = {
                        pairingVisible = true
                        onStartAgent()
                    },
                )

                AgentGateway.Status.WAITING -> AgentRow(
                    title = stringResource(R.string.settings_waiting),
                    subtitle = "http://${state.gateway.address ?: "-"}:${state.gateway.port}",
                    actionLabel = stringResource(R.string.settings_show_code),
                    onAction = { pairingVisible = true },
                    secondaryLabel = stringResource(R.string.settings_disconnect),
                    onSecondary = onStopAgent,
                )

                AgentGateway.Status.CONNECTED -> AgentRow(
                    title = stringResource(R.string.settings_connected),
                    subtitle = stringResource(
                        R.string.settings_lan_address,
                        "${state.gateway.address ?: "-"}:${state.gateway.port}",
                    ),
                    actionLabel = stringResource(R.string.settings_disconnect),
                    onAction = onStopAgent,
                )

                AgentGateway.Status.ERROR -> AgentRow(
                    title = stringResource(R.string.settings_start_failed),
                    subtitle = state.gateway.error
                        ?: stringResource(R.string.settings_port_unavailable, state.gateway.port),
                    actionLabel = stringResource(R.string.settings_retry),
                    onAction = onStartAgent,
                )
            }

            SwitchRow(
                title = stringResource(R.string.settings_shell),
                subtitle = stringResource(R.string.settings_shell_sub),
                checked = state.settings.allowRemoteShell,
                onChange = {
                    onUpdateSettings(state.settings.copy(allowRemoteShell = it))
                },
            )

            SectionTitle(stringResource(R.string.settings_selfcheck))
            SwitchRow(
                title = stringResource(R.string.settings_auto_open),
                subtitle = stringResource(R.string.settings_auto_open_sub),
                checked = state.settings.autoVerifyOnOpen,
                onChange = { onUpdateSettings(state.settings.copy(autoVerifyOnOpen = it)) },
            )
            SwitchRow(
                title = stringResource(R.string.settings_auto_boot),
                subtitle = stringResource(R.string.settings_auto_boot_sub),
                checked = state.settings.autoVerifyOnBoot,
                onChange = { onUpdateSettings(state.settings.copy(autoVerifyOnBoot = it)) },
            )
            Column {
                Text(stringResource(R.string.settings_periodic), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.settings_periodic_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PERIODIC_OPTIONS.forEach { (minutes, label) ->
                        FilterChip(
                            selected = state.settings.periodicMinutes == minutes,
                            onClick = { onUpdateSettings(state.settings.copy(periodicMinutes = minutes)) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_notify),
                subtitle = stringResource(R.string.settings_notify_sub),
                checked = state.settings.notifyOnDrift,
                onChange = { enabled ->
                    onUpdateSettings(state.settings.copy(notifyOnDrift = enabled))
                    if (enabled) onRequestNotificationPermission()
                },
            )
            SwitchRow(
                title = stringResource(R.string.settings_reapply),
                subtitle = stringResource(R.string.settings_reapply_sub),
                checked = state.settings.autoReapplyOnUnsuppress,
                onChange = {
                    onUpdateSettings(state.settings.copy(autoReapplyOnUnsuppress = it))
                },
            )
            SwitchRow(
                title = stringResource(R.string.settings_hide_recents),
                subtitle = stringResource(R.string.settings_hide_recents_sub),
                checked = state.settings.hideFromRecents,
                onChange = { onUpdateSettings(state.settings.copy(hideFromRecents = it)) },
            )

            SectionTitle(stringResource(R.string.settings_diagnostics))
            AgentRow(
                title = stringResource(R.string.settings_logs),
                subtitle = stringResource(R.string.settings_logs_sub),
                actionLabel = stringResource(R.string.common_view),
                onAction = onOpenLogs,
            )
            AgentRow(
                title = stringResource(R.string.settings_backups),
                subtitle = stringResource(R.string.settings_backups_sub),
                actionLabel = stringResource(R.string.common_view),
                onAction = onOpenBackups,
            )
            AgentRow(
                title = stringResource(R.string.settings_stats),
                subtitle = stringResource(R.string.settings_stats_sub),
                actionLabel = stringResource(R.string.common_view),
                onAction = onOpenStats,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_rule_data),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                CompactButton(stringResource(R.string.settings_import), onImportJson)
                Spacer(Modifier.width(8.dp))
                CompactButton(stringResource(R.string.settings_export), onExportJson)
            }
            Text(
                stringResource(R.string.settings_rule_data_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.settings_about))
            InfoRow(stringResource(R.string.settings_version), versionName)
            InfoRow(
                stringResource(R.string.settings_rules),
                stringResource(R.string.settings_rules_summary, state.groups.size, ruleCount),
            )
            InfoRow(
                stringResource(R.string.settings_last_check),
                stringResource(
                    if (state.verifiedAt > 0) R.string.settings_checked else R.string.settings_not_checked,
                ),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "by pza",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pairingVisible && state.gateway.status == AgentGateway.Status.WAITING) {
        PairingDialog(gateway = state.gateway) {
            pairingVisible = false
            onStopAgent()
        }
    }
}

@Composable
private fun AgentRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CompactButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
