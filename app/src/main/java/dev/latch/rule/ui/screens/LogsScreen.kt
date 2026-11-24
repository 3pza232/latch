package dev.latch.rule.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
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

private enum class LogFilter(val labelRes: Int) {
    ALL(R.string.logs_filter_all),
    ISSUES(R.string.logs_filter_issues),
    APPLY(R.string.logs_filter_apply),
    VERIFY(R.string.logs_filter_verify),
    GATEWAY(R.string.logs_filter_gateway),
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
    onExport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val visible = remember(logs, filter) { logs.filter(filter::matches) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                    IconButton(onClick = onExport, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_export))
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.logs_clear_title))
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
                        label = { Text(stringResource(option.labelRes)) },
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
                    title = stringResource(R.string.logs_empty_title),
                    description = stringResource(R.string.logs_empty_description),
                    primaryLabel = stringResource(R.string.common_refresh),
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
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(stringResource(R.string.logs_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.logs_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
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
                stringResource(actionLabel(event.action)),
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
                append(stringResource(sourceLabel(event.source)))
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
            event.expected?.let { Detail(stringResource(R.string.logs_expected), it) }
            event.actual?.let { Detail(stringResource(R.string.logs_actual), it) }
            event.command?.let { Detail(stringResource(R.string.logs_command), it) }
            event.output?.let { Detail(stringResource(R.string.logs_output), it) }
            event.errorCode?.let { Detail(stringResource(R.string.logs_error_code), it) }
            event.durationMs?.let { Detail(stringResource(R.string.logs_duration), "${it} ms") }
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

private fun actionLabel(action: EventAction): Int = when (action) {
    EventAction.CREATE -> R.string.action_create
    EventAction.UPDATE -> R.string.action_update
    EventAction.DELETE -> R.string.action_delete
    EventAction.ENABLE -> R.string.action_enable
    EventAction.DISABLE -> R.string.action_disable
    EventAction.APPLY -> R.string.action_apply
    EventAction.VERIFY -> R.string.action_verify
    EventAction.REPAIR -> R.string.action_repair
    EventAction.IMPORT -> R.string.action_import
    EventAction.EXPORT -> R.string.action_export
    EventAction.BACKUP -> R.string.action_backup
    EventAction.RESTORE -> R.string.action_restore
    EventAction.PAIR -> R.string.action_pair
    EventAction.PAIR_FAILED -> R.string.action_pair_failed
    EventAction.GATEWAY_START -> R.string.action_gateway_start
    EventAction.GATEWAY_STOP -> R.string.action_gateway_stop
    EventAction.SKIP -> R.string.action_skip
    EventAction.ROTATE -> R.string.action_rotate
    EventAction.MIGRATE -> R.string.action_migrate
}

private fun sourceLabel(source: EventSource): Int = when (source) {
    EventSource.UI -> R.string.source_ui
    EventSource.GATEWAY -> R.string.source_gateway
    EventSource.PERIODIC -> R.string.source_periodic
    EventSource.BOOT -> R.string.source_boot
    EventSource.SYSTEM -> R.string.source_system
    EventSource.IMPORT -> R.string.source_import
    EventSource.RESTORE -> R.string.source_restore
}

private fun LogFilter.matches(event: LatchEvent): Boolean = when (this) {
    LogFilter.ALL -> true
    LogFilter.ISSUES -> event.level != EventLevel.INFO
    LogFilter.APPLY -> event.action == EventAction.APPLY
    LogFilter.VERIFY -> event.action == EventAction.VERIFY
    LogFilter.GATEWAY -> event.source == EventSource.GATEWAY
}
