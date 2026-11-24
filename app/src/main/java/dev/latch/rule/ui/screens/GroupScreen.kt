package dev.latch.rule.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
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
    onDeleteRules: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteRules by remember { mutableStateOf(false) }
    val busy = state.busyGroupId == group.id
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting) { selection = emptySet() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) {
                        Text(stringResource(R.string.group_selected_count, selection.size))
                    } else {
                        Column {
                            Text(group.name)
                            Text(
                                stringResource(R.string.group_rule_count, group.rules.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_clear_selection))
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { confirmDeleteRules = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.home_delete_selected))
                        }
                    } else {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        TextButton(onClick = onApply, enabled = !busy) {
                            Text(stringResource(if (busy) R.string.group_applying else R.string.group_apply))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.group_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_edit_info)) },
                                onClick = { menuOpen = false; renameDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_delete)) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
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
                FloatingActionButton(onClick = onAddRule) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.group_add_rule))
                }
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
                    onClick = {
                        if (selecting) selection = selection.toggle(rule.id) else onEditRule(rule.id)
                    },
                    onToggle = { onToggleRule(rule.id, it) },
                    onLongClick = { selection = selection.toggle(rule.id) },
                    selected = rule.id in selection,
                    notice = state.suppressedBy[rule.id]?.let { stringResource(R.string.rule_covered_by, it) }
                        ?: state.blocked[rule.id],
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (renameDialog) {
        GroupInfoDialog(
            title = stringResource(R.string.group_edit_title),
            initialName = group.name,
            initialDescription = group.description,
            confirmLabel = stringResource(R.string.common_save),
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
            title = { Text(stringResource(R.string.group_delete_title)) },
            text = { Text(stringResource(R.string.group_delete_body, group.rules.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteGroup()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmDeleteRules) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRules = false },
            title = { Text(stringResource(R.string.group_delete_selected_title, selection.size)) },
            text = { Text(stringResource(R.string.group_delete_irreversible)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteRules = false
                    onDeleteRules(selection)
                    selection = emptySet()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteRules = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
