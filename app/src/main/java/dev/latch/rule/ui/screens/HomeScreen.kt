package dev.latch.rule.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.components.EmptyState
import dev.latch.rule.ui.components.GroupCard
import dev.latch.rule.ui.components.GroupInfoDialog
import dev.latch.rule.ui.components.ShizukuStatusCard
import dev.latch.rule.ui.theme.LatchDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: LatchViewModel.UiState,
    onNewGroup: (String, String) -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshPrivilege: () -> Unit,
    onOpenShizuku: () -> Unit,
    onGroupClick: (String) -> Unit,
    onGroupToggle: (String, Boolean) -> Unit,
    onVerifyAll: () -> Unit,
    onImportJson: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteGroups: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var createDialog by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting) { selection = emptySet() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选择 ${selection.size} 个" else "Latch") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "取消选择")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除所选")
                        }
                    } else {
                        IconButton(onClick = onVerifyAll, enabled = !state.verifyingAll) {
                            if (state.verifyingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "校验全部")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = { createDialog = true },
                    shape = RoundedCornerShape(18.dp),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新建规则组") },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(LatchDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(LatchDimens.ItemGap),
        ) {
            item {
                ShizukuStatusCard(
                    status = state.privilege,
                    onRequestPermission = onRequestPermission,
                    onRefresh = onRefreshPrivilege,
                    onOpenShizuku = onOpenShizuku,
                )
            }
            if (state.groups.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        title = "还没有规则组",
                        description = "先新建一个规则组，再往里添加规则；也可以直接导入 JSON。",
                        primaryLabel = "新建规则组",
                        onPrimary = { createDialog = true },
                        secondaryLabel = "导入 JSON",
                        onSecondary = onImportJson,
                    )
                }
            } else {
                items(state.groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        summary = state.summary(group),
                        verifiedAt = state.verifiedAt,
                        onClick = {
                            if (selecting) {
                                selection = selection.toggle(group.id)
                            } else {
                                onGroupClick(group.id)
                            }
                        },
                        onToggle = { onGroupToggle(group.id, it) },
                        onLongClick = { selection = selection.toggle(group.id) },
                        selected = group.id in selection,
                    )
                }
            }
            item { Spacer(Modifier.height(LatchDimens.SectionGap)) }
        }
    }

    if (createDialog) {
        GroupInfoDialog(
            title = "新建规则组",
            confirmLabel = "创建",
            onConfirm = { name, description ->
                onNewGroup(name, description)
                createDialog = false
            },
            onDismiss = { createDialog = false },
        )
    }

    if (confirmDelete) {
        val ruleCount = state.groups.filter { it.id in selection }.sumOf { it.rules.size }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selection.size} 个规则组？") },
            text = { Text("将同时删除其中的 $ruleCount 条规则，系统上已生效的设置不会被自动还原。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteGroups(selection)
                    selection = emptySet()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
