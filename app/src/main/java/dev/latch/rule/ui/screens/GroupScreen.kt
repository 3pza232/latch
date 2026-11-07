package dev.latch.rule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.components.GroupInfoDialog
import dev.latch.rule.ui.components.RuleRow
import dev.latch.rule.ui.theme.LatchDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    group: RuleGroup,
    state: LatchViewModel.UiState,
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onApply: () -> Unit,
    onChangeGroupInfo: (String, String) -> Unit,
    onDeleteGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = state.busyGroupId == group.id

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name)
                        Text(
                            "${group.rules.size} 条规则",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onApply, enabled = !busy) {
                        Text(if (busy) "应用中" else "应用")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑名称与说明") },
                            onClick = { menuOpen = false; renameDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("删除规则组") },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule) {
                Icon(Icons.Filled.Add, contentDescription = "添加规则到本组")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = LatchDimens.ScreenPadding),
            verticalArrangement = Arrangement.Top,
        ) {
            items(group.rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    state = state.stateOf(rule.id, group.enabled && rule.enabled),
                    result = state.resultOf(rule.id),
                    onClick = { onEditRule(rule.id) },
                    onToggle = { onToggleRule(rule.id, it) },
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (renameDialog) {
        GroupInfoDialog(
            title = "编辑规则组",
            initialName = group.name,
            initialDescription = group.description,
            confirmLabel = "保存",
            onConfirm = { name, description ->
                onChangeGroupInfo(name, description)
                renameDialog = false
            },
            onDismiss = { renameDialog = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除规则组？") },
            text = { Text("将同时删除组内 ${group.rules.size} 条规则，系统上已生效的设置不会被自动还原。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteGroup()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
