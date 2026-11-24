package dev.latch.rule.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private val RESTORE_POINTS = listOf(
    "1 小时前" to HOUR,
    "1 天前" to DAY,
    "3 天前" to 3 * DAY,
    "7 天前" to 7 * DAY,
    "30 天前" to 30 * DAY,
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
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf<BackupMeta?>(null) }
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

    LaunchedEffect(target?.id, scopeAll, selectedGroupIds) {
        val meta = target ?: return@LaunchedEffect
        onPreview(meta.id, if (scopeAll) null else selectedGroupIds)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Filled.Add, contentDescription = "立即备份")
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
                    title = "还没有备份",
                    description = "修改规则后会自动创建恢复点，也可以点右上角立即备份。",
                    primaryLabel = "立即备份",
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
                    SectionTitle("恢复点")
                    Spacer(Modifier.height(8.dp))
                }
                items(RESTORE_POINTS, key = { it.first }) { (label, offset) ->
                    RestorePointRow(
                        label = label,
                        meta = nearestBefore(backups, offset),
                        onClick = { meta -> openRestore(meta) },
                    )
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("全部快照")
                    Spacer(Modifier.height(8.dp))
                }
                items(backups, key = { it.id }) { meta ->
                    SnapshotRow(meta) { openRestore(meta) }
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
}

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
        title = { Text("恢复备份") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "恢复到 ${formatTime(meta.createdAt)} 的状态",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scopeAll,
                        onClick = { onScopeChange(true) },
                        label = { Text("全部规则") },
                    )
                    FilterChip(
                        selected = !scopeAll,
                        onClick = { onScopeChange(false) },
                        label = { Text("选择规则组") },
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
                            "当前没有规则组",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    preview?.let(::describePlan) ?: "正在计算改动…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview?.let { plan ->
                    val changed = plan.changes.filter { it.kind != GroupChangeKind.UNCHANGED }
                    if (changed.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        changed.take(6).forEach { change ->
                            Text(
                                "· ${change.groupName}：${changeLabel(change.kind)}（${change.ruleCount} 条）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (changed.size > 6) {
                            Text(
                                "… 还有 ${changed.size - 6} 个组",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("恢复后立即应用", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "默认只恢复配置，不改变系统状态",
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
                Text("恢复")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !restoring) { Text("取消") }
        },
    )
}

@Composable
private fun RestorePointRow(
    label: String,
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
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Text(
            meta?.let { "${formatTime(it.createdAt)} · ${it.groups} 组 / ${it.rules} 条" }
                ?: "无可用恢复点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (meta != null) {
            Text("恢复", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SnapshotRow(meta: BackupMeta, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row {
            Text(
                formatTime(meta.createdAt),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                reasonLabel(meta.reason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${meta.groups} 组 / ${meta.rules} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun nearestBefore(backups: List<BackupMeta>, offset: Long): BackupMeta? {
    val cutoff = System.currentTimeMillis() - offset
    return backups.firstOrNull { it.createdAt <= cutoff }
}

private fun describePlan(plan: RestorePlan): String {
    val changed = plan.added + plan.replaced + plan.removed
    if (changed == 0) return "与当前规则一致，没有需要恢复的改动"
    return buildString {
        append("将更改 $changed 个组：新增 ${plan.added}、替换 ${plan.replaced}")
        if (plan.removed > 0) append("、删除 ${plan.removed}")
        append("；恢复后共 ${plan.groups.size} 组 / ${plan.ruleCount} 条")
    }
}

private fun changeLabel(kind: GroupChangeKind): String = when (kind) {
    GroupChangeKind.ADDED -> "新增"
    GroupChangeKind.REPLACED -> "替换"
    GroupChangeKind.REMOVED -> "删除"
    GroupChangeKind.UNCHANGED -> "不变"
}

private fun reasonLabel(reason: BackupReason): String = when (reason) {
    BackupReason.MANUAL -> "手动"
    BackupReason.AUTO_CHANGE -> "自动"
    BackupReason.PRE_RESTORE -> "恢复前"
    BackupReason.PRE_MIGRATION -> "迁移前"
}

private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
