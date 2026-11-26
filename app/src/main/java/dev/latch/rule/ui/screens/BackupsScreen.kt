package dev.latch.rule.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.GroupChangeKind
import dev.latch.rule.core.backup.RestorePlan
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.ui.components.EmptyState
import dev.latch.rule.ui.theme.LatchDimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.latch.rule.core.backup.BackupSlots

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private val RESTORE_POINT_LABELS = listOf(
    R.string.backups_ago_hour,
    R.string.backups_ago_day,
    R.string.backups_ago_3days,
    R.string.backups_ago_7days,
    R.string.backups_ago_30days,
)

/**
 * Snapshot list with quick restore points and a scoped restore dialog.
 * Restore never applies to the system unless the user explicitly asks for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(
    backups: List<BackupMeta>,
    groups: List<RuleGroup>,
    loading: Boolean,
    restoring: Boolean,
    preview: RestorePlan?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onPreview: (String, Set<String>?) -> Unit,
    onClearPreview: () -> Unit,
    onRestore: (String, Set<String>?, Boolean) -> Unit,
    onDelete: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf<BackupMeta?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting) { selection = emptySet() }
    var scopeAll by remember { mutableStateOf(true) }
    var selectedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var applyAfter by remember { mutableStateOf(false) }

    fun openRestore(meta: BackupMeta) {
        target = meta
        scopeAll = true
        selectedGroupIds = emptySet()
        applyAfter = false
        onPreview(meta.id, null)
    }

    fun closeRestore() {
        target = null
        onClearPreview()
    }

    val points = remember(backups) {
        BackupSlots.select(backups, System.currentTimeMillis(), ZoneId.systemDefault())
    }
    val quickPoints = listOf(
        points.hourly,
        points.oneDay,
        points.threeDays,
        points.sevenDays,
        points.thirtyDays,
    )
    // Boundary snapshots are the restore points and are not listed or deletable here.
    val changeSnapshots = remember(backups) {
        backups.filter {
            it.reason != BackupReason.HOURLY && it.reason != BackupReason.DAILY
        }
    }

    LaunchedEffect(target?.id, scopeAll, selectedGroupIds) {
        val meta = target ?: return@LaunchedEffect
        onPreview(meta.id, if (scopeAll) null else selectedGroupIds)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) {
                            stringResource(R.string.backups_selected, selection.size)
                        } else {
                            stringResource(R.string.backups_title)
                        },
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.home_clear_selection),
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.home_delete_selected),
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.common_refresh),
                            )
                        }
                        IconButton(onClick = onCreate) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.backups_create),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        when {
            loading && backups.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            backups.isEmpty() -> Column(modifier = Modifier.padding(innerPadding)) {
                EmptyState(
                    title = stringResource(R.string.backups_empty_title),
                    description = stringResource(R.string.backups_empty_description),
                    primaryLabel = stringResource(R.string.backups_create),
                    onPrimary = onCreate,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(
                    start = LatchDimens.ScreenPadding,
                    end = LatchDimens.ScreenPadding,
                    bottom = 24.dp,
                ),
            ) {
                item {
                    SectionTitle(stringResource(R.string.backups_restore_points))
                    Spacer(Modifier.height(8.dp))
                }
                items(RESTORE_POINT_LABELS, key = { it }) { label ->
                    val index = RESTORE_POINT_LABELS.indexOf(label)
                    RestorePointRow(
                        label = label,
                        meta = quickPoints.getOrNull(index),
                        onClick = { meta -> openRestore(meta) },
                    )
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle(stringResource(R.string.backups_changes))
                    Spacer(Modifier.height(8.dp))
                }
                if (changeSnapshots.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.backups_changes_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(changeSnapshots, key = { it.id }) { meta ->
                    SnapshotRow(
                        meta = meta,
                        selected = meta.id in selection,
                        onClick = {
                            if (selecting) {
                                selection = selection.toggle(meta.id)
                            } else {
                                openRestore(meta)
                            }
                        },
                        onLongClick = { selection = selection.toggle(meta.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    target?.let { meta ->
        RestoreDialog(
            meta = meta,
            groups = groups,
            scopeAll = scopeAll,
            selectedGroupIds = selectedGroupIds,
            applyAfter = applyAfter,
            preview = preview?.takeIf { it.snapshotId == meta.id },
            restoring = restoring,
            onScopeChange = { scopeAll = it },
            onToggleGroup = { groupId ->
                selectedGroupIds = if (groupId in selectedGroupIds) {
                    selectedGroupIds - groupId
                } else {
                    selectedGroupIds + groupId
                }
            },
            onApplyChange = { applyAfter = it },
            onConfirm = {
                onRestore(meta.id, if (scopeAll) null else selectedGroupIds, applyAfter)
                target = null
            },
            onDismiss = ::closeRestore,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.backups_delete_title, selection.size)) },
            text = { Text(stringResource(R.string.backups_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selection
                    confirmDelete = false
                    selection = emptySet()
                    onDelete(ids)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreDialog(
    meta: BackupMeta,
    groups: List<RuleGroup>,
    scopeAll: Boolean,
    selectedGroupIds: Set<String>,
    applyAfter: Boolean,
    preview: RestorePlan?,
    restoring: Boolean,
    onScopeChange: (Boolean) -> Unit,
    onToggleGroup: (String) -> Unit,
    onApplyChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.backups_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.backups_dialog_target, formatTime(meta.createdAt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scopeAll,
                        onClick = { onScopeChange(true) },
                        label = { Text(stringResource(R.string.backups_scope_all)) },
                    )
                    FilterChip(
                        selected = !scopeAll,
                        onClick = { onScopeChange(false) },
                        label = { Text(stringResource(R.string.backups_scope_groups)) },
                    )
                }

                if (!scopeAll) {
                    Spacer(Modifier.height(8.dp))
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleGroup(group.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = group.id in selectedGroupIds,
                                onCheckedChange = { onToggleGroup(group.id) },
                            )
                            Text(group.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (groups.isEmpty()) {
                        Text(
                            stringResource(R.string.backups_no_groups),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    preview?.let { describePlan(it) } ?: stringResource(R.string.backups_calculating),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview?.let { plan ->
                    val changed = plan.changes.filter { it.kind != GroupChangeKind.UNCHANGED }
                    if (changed.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        changed.take(6).forEach { change ->
                            Text(
                                stringResource(
                                    R.string.backups_change_item,
                                    change.groupName,
                                    stringResource(changeLabel(change.kind)),
                                    change.ruleCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (changed.size > 6) {
                            Text(
                                stringResource(R.string.backups_more_groups, changed.size - 6),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.backups_apply_now), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.backups_apply_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = applyAfter, onCheckedChange = onApplyChange)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !restoring &&
                    (scopeAll || selectedGroupIds.isNotEmpty()) &&
                    preview != null &&
                    preview.affectedGroupIds.isNotEmpty(),
            ) {
                if (restoring) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.backups_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !restoring) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun RestorePointRow(
    label: Int,
    meta: BackupMeta?,
    onClick: (BackupMeta) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (meta != null) Modifier.clickable { onClick(meta) } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Text(
            meta?.let {
                stringResource(
                    R.string.backups_point_summary,
                    formatTime(it.createdAt),
                    it.groups,
                    it.rules,
                )
            } ?: stringResource(R.string.backups_no_point),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (meta != null) {
            Text(stringResource(R.string.backups_restore), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnapshotRow(
    meta: BackupMeta,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(
                    formatTime(meta.createdAt),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(reasonLabel(meta.reason)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.backups_snapshot_summary, meta.groups, meta.rules),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}



@Composable
private fun describePlan(plan: RestorePlan): String {
    val changed = plan.added + plan.replaced + plan.removed
    if (changed == 0) return stringResource(R.string.backups_no_changes)
    return stringResource(
        R.string.backups_change_summary,
        changed,
        plan.added,
        plan.replaced,
        plan.removed,
        plan.groups.size,
        plan.ruleCount,
    )
}

private fun changeLabel(kind: GroupChangeKind): Int = when (kind) {
    GroupChangeKind.ADDED -> R.string.change_added
    GroupChangeKind.REPLACED -> R.string.change_replaced
    GroupChangeKind.REMOVED -> R.string.change_removed
    GroupChangeKind.UNCHANGED -> R.string.change_unchanged
}

private fun reasonLabel(reason: BackupReason): Int = when (reason) {
    BackupReason.MANUAL -> R.string.backups_reason_manual
    BackupReason.AUTO_CHANGE -> R.string.backups_reason_auto
    BackupReason.HOURLY -> R.string.backups_reason_hourly
    BackupReason.DAILY -> R.string.backups_reason_daily
    BackupReason.PRE_RESTORE -> R.string.backups_reason_pre_restore
    BackupReason.PRE_MIGRATION -> R.string.backups_reason_pre_migration
}

private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
