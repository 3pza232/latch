package dev.latch.rule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.latch.rule.data.LatchSettings
import dev.latch.rule.gateway.AgentGateway
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.components.PairingDialog
import dev.latch.rule.ui.components.ShizukuStatusCard
import dev.latch.rule.ui.theme.LatchDimens

private val PERIODIC_OPTIONS = listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟")

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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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

            SectionTitle("局域网连接")
            when (state.gateway.status) {
                AgentGateway.Status.IDLE -> AgentRow(
                    title = "未连接",
                    subtitle = "在同一局域网内管理规则",
                    actionLabel = "连接",
                    onAction = {
                        pairingVisible = true
                        onStartAgent()
                    },
                )

                AgentGateway.Status.WAITING -> AgentRow(
                    title = "等待连接",
                    subtitle = "http://${state.gateway.address ?: "-"}:${state.gateway.port}",
                    actionLabel = "显示配对码",
                    onAction = { pairingVisible = true },
                    secondaryLabel = "断开",
                    onSecondary = onStopAgent,
                )

                AgentGateway.Status.CONNECTED -> AgentRow(
                    title = "已连接",
                    subtitle = "局域网 ${state.gateway.address ?: "-"}:${state.gateway.port}",
                    actionLabel = "断开",
                    onAction = onStopAgent,
                )
            }

            SectionTitle("自检")
            SwitchRow(
                title = "打开应用时自动校验",
                subtitle = "启动后立即核对所有启用规则",
                checked = state.settings.autoVerifyOnOpen,
                onChange = { onUpdateSettings(state.settings.copy(autoVerifyOnOpen = it)) },
            )
            SwitchRow(
                title = "开机自检",
                subtitle = "重启后自动核对一次（需 Shizuku 已授权）",
                checked = state.settings.autoVerifyOnBoot,
                onChange = { onUpdateSettings(state.settings.copy(autoVerifyOnBoot = it)) },
            )
            Column {
                Text("定期自检", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "后台按间隔核对规则，发现漂移可通知",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PERIODIC_OPTIONS.forEach { (minutes, label) ->
                        FilterChip(
                            selected = state.settings.periodicMinutes == minutes,
                            onClick = { onUpdateSettings(state.settings.copy(periodicMinutes = minutes)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            SwitchRow(
                title = "漂移通知",
                subtitle = "后台自检发现漂移时发送通知",
                checked = state.settings.notifyOnDrift,
                onChange = { enabled ->
                    onUpdateSettings(state.settings.copy(notifyOnDrift = enabled))
                    if (enabled) onRequestNotificationPermission()
                },
            )

            SectionTitle("数据")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onImportJson) { Text("导入 JSON") }
                OutlinedButton(onClick = onExportJson) { Text("导出 JSON") }
            }
            Text(
                "规则保存在 files/rules.json，覆盖安装保留，卸载清除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("关于")
            InfoRow("版本", versionName)
            InfoRow("规则", "${state.groups.size} 个规则组 / $ruleCount 条规则")
            InfoRow("上次校验", if (state.verifiedAt > 0) "已校验" else "尚未校验")

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
