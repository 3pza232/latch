package dev.latch.rule.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.ui.components.EmptyState
import dev.latch.rule.ui.theme.LatchDimens
import dev.latch.rule.ui.theme.LatchPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class LogFilter(val label: String) {
    ALL("全部"),
    ISSUES("异常"),
    APPLY("应用"),
    VERIFY("校验"),
    GATEWAY("网关"),
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/**
 * Read-only view of the event log. Successful verification runs appear as a single
 * batch line; drifted and failed rules expand to show the command and its output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<LatchEvent>,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val visible = remember(logs, filter) { logs.filter(filter::matches) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = LatchDimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                visible.isEmpty() -> EmptyState(
                    title = "暂无日志",
                    description = "应用、校验、修复和配对都会记录在这里。",
                    primaryLabel = "刷新",
                    onPrimary = onRefresh,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = LatchDimens.ScreenPadding,
                        end = LatchDimens.ScreenPadding,
                        bottom = 24.dp,
                    ),
                ) {
                    items(visible, key = { it.id }) { event ->
                        LogRow(
                            event = event,
                            expanded = expandedId == event.id,
                            onClick = { expandedId = if (expandedId == event.id) null else event.id },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空日志？") },
            text = { Text("将删除全部历史事件，统计的明细也会随之减少。规则本身不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LogRow(event: LatchEvent, expanded: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(levelColor(event.level)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                actionLabel(event.action),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTime(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append(sourceLabel(event.source))
                event.groupName?.let { append(" · ").append(it) }
                event.ruleName?.let { append(" · ").append(it) }
                event.outcome?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        event.detail?.let { detail ->
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        event.message?.let { message ->
            Spacer(Modifier.height(2.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = levelColor(event.level),
            )
        }
        if (expanded) {
            event.expected?.let { Detail("期望", it) }
            event.actual?.let { Detail("实际", it) }
            event.command?.let { Detail("命令", it) }
            event.output?.let { Detail("输出", it) }
            event.errorCode?.let { Detail("错误码", it) }
            event.durationMs?.let { Detail("耗时", "${it} ms") }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        "$label：$value",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun levelColor(level: EventLevel): Color {
    val dark = isSystemInDarkTheme()
    return when (level) {
        EventLevel.ERROR -> if (dark) LatchPalette.DarkDanger else LatchPalette.Danger
        EventLevel.WARN -> if (dark) LatchPalette.DarkWarning else LatchPalette.Warning
        EventLevel.INFO -> if (dark) LatchPalette.DarkSuccess else LatchPalette.Success
    }
}

private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

private fun actionLabel(action: EventAction): String = when (action) {
    EventAction.CREATE -> "新建"
    EventAction.UPDATE -> "修改"
    EventAction.DELETE -> "删除"
    EventAction.ENABLE -> "启用"
    EventAction.DISABLE -> "停用"
    EventAction.APPLY -> "应用"
    EventAction.VERIFY -> "校验"
    EventAction.REPAIR -> "修复"
    EventAction.IMPORT -> "导入"
    EventAction.EXPORT -> "导出"
    EventAction.BACKUP -> "备份"
    EventAction.RESTORE -> "恢复"
    EventAction.PAIR -> "配对成功"
    EventAction.PAIR_FAILED -> "配对失败"
    EventAction.GATEWAY_START -> "网关启动"
    EventAction.GATEWAY_STOP -> "网关停止"
    EventAction.SKIP -> "跳过"
    EventAction.ROTATE -> "清理"
    EventAction.MIGRATE -> "迁移"
}

private fun sourceLabel(source: EventSource): String = when (source) {
    EventSource.UI -> "手动"
    EventSource.GATEWAY -> "局域网"
    EventSource.PERIODIC -> "后台"
    EventSource.BOOT -> "开机"
    EventSource.SYSTEM -> "系统"
    EventSource.IMPORT -> "导入"
    EventSource.RESTORE -> "恢复"
}

private fun LogFilter.matches(event: LatchEvent): Boolean = when (this) {
    LogFilter.ALL -> true
    LogFilter.ISSUES -> event.level != EventLevel.INFO
    LogFilter.APPLY -> event.action == EventAction.APPLY
    LogFilter.VERIFY -> event.action == EventAction.VERIFY
    LogFilter.GATEWAY -> event.source == EventSource.GATEWAY
}
