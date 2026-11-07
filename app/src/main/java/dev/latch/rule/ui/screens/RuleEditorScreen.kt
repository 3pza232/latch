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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.Rule
import dev.latch.rule.data.InstalledApp
import dev.latch.rule.data.OpLabels
import dev.latch.rule.ui.components.AppPickerDialog
import dev.latch.rule.ui.theme.LatchDimens
import java.util.UUID

private val CAPABILITY_TABS = listOf("AppOps", "撤销权限", "强制停止", "启停应用")

private val COMMON_PERMISSIONS = listOf(
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_CALENDAR",
    "android.permission.BODY_SENSORS",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    apps: List<InstalledApp>,
    initialRule: Rule?,
    onSave: (Rule) -> Unit,
    onDelete: ((String) -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialCapability = initialRule?.capability

    var ruleName by rememberSaveable { mutableStateOf(initialRule?.name.orEmpty()) }
    var packageName by rememberSaveable { mutableStateOf(initialRule?.targetPackage.orEmpty()) }
    var appLabel by rememberSaveable {
        mutableStateOf(apps.firstOrNull { it.packageName == initialRule?.targetPackage }?.label.orEmpty())
    }
    var capabilityTab by rememberSaveable {
        mutableIntStateOf(
            when (initialCapability) {
                is Capability.RevokePermission -> 1
                Capability.ForceStop -> 2
                is Capability.SetEnabled -> 3
                else -> 0
            },
        )
    }
    var op by rememberSaveable { mutableStateOf((initialCapability as? Capability.AppOp)?.op ?: "CAMERA") }
    var mode by rememberSaveable { mutableStateOf((initialCapability as? Capability.AppOp)?.mode ?: OpMode.IGNORE) }
    var scope by rememberSaveable { mutableStateOf((initialCapability as? Capability.AppOp)?.scope ?: OpScope.UID) }
    var permission by rememberSaveable {
        mutableStateOf((initialCapability as? Capability.RevokePermission)?.permission ?: COMMON_PERMISSIONS.first())
    }
    var appEnabled by rememberSaveable {
        mutableStateOf((initialCapability as? Capability.SetEnabled)?.enabled ?: false)
    }
    var pickerOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (packageName.isBlank()) {
            error = "请选择目标应用"
            return
        }
        val capability = when (capabilityTab) {
            1 -> Capability.RevokePermission(permission)
            2 -> Capability.ForceStop
            3 -> Capability.SetEnabled(appEnabled)
            else -> Capability.AppOp(op, mode, scope)
        }
        onSave(
            Rule(
                id = initialRule?.id ?: UUID.randomUUID().toString(),
                name = ruleName.ifBlank { defaultRuleName(capability) },
                targetPackage = packageName,
                capability = capability,
                enabled = initialRule?.enabled ?: true,
            ),
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (initialRule == null) "新建规则" else "编辑规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = ::save) { Text("保存") } },
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
            OutlinedTextField(
                value = ruleName,
                onValueChange = { ruleName = it },
                label = { Text("规则名称") },
                placeholder = { Text("留空则使用能力名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                SectionLabel("目标应用")
                OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            appLabel.isNotBlank() -> "$appLabel · $packageName"
                            packageName.isNotBlank() -> packageName
                            else -> "选择应用"
                        },
                    )
                }
            }

            Column {
                SectionLabel("能力类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CAPABILITY_TABS.forEachIndexed { index, label ->
                        FilterChip(
                            selected = capabilityTab == index,
                            onClick = { capabilityTab = index },
                            label = { Text(label) },
                        )
                    }
                }
            }

            when (capabilityTab) {
                0 -> {
                    Column {
                        SectionLabel("AppOps 项")
                        DropdownField(
                            value = OpLabels.label(op),
                            options = OpLabels.knownOps().map { it to "${OpLabels.label(it)} · $it" },
                            onSelect = { op = it },
                        )
                    }
                    Column {
                        SectionLabel("模式")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpMode.entries.forEach { entry ->
                                FilterChip(
                                    selected = mode == entry,
                                    onClick = { mode = entry },
                                    label = { Text(entry.wire) },
                                )
                            }
                        }
                    }
                    Column {
                        SectionLabel("作用域")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = scope == OpScope.UID,
                                onClick = { scope = OpScope.UID },
                                label = { Text("UID 模式") },
                            )
                            FilterChip(
                                selected = scope == OpScope.PACKAGE,
                                onClick = { scope = OpScope.PACKAGE },
                                label = { Text("包级") },
                            )
                        }
                    }
                }
                1 -> {
                    Column {
                        SectionLabel("权限")
                        DropdownField(
                            value = permission,
                            options = COMMON_PERMISSIONS.map { it to it },
                            onSelect = { permission = it },
                        )
                    }
                }
                2 -> {
                    Text(
                        "强制停止会立即结束目标进程，重启或重新启动后可能恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                3 -> {
                    Column {
                        SectionLabel("目标状态")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = appEnabled,
                                onClick = { appEnabled = true },
                                label = { Text("启用应用") },
                            )
                            FilterChip(
                                selected = !appEnabled,
                                onClick = { appEnabled = false },
                                label = { Text("停用应用") },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "vivo 保护包可能拒绝停用（需 root），失败时会在校验中显示原因。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> Unit
            }

            error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("保存规则") }

            if (onDelete != null && initialRule != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除规则", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = apps,
            onSelect = { app ->
                packageName = app.packageName
                appLabel = app.label
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (confirmDelete && initialRule != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除规则？") },
            text = { Text("删除后不可撤销，系统上已生效的设置不会被自动还原。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initialRule.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun defaultRuleName(capability: Capability): String = when (capability) {
    is Capability.AppOp -> OpLabels.label(capability.op)
    is Capability.RevokePermission -> capability.permission.substringAfterLast('.')
    Capability.ForceStop -> "强制停止"
    is Capability.SetEnabled -> if (capability.enabled) "启用应用" else "停用应用"
}
