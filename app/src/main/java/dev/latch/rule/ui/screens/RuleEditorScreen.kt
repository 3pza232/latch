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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.OpScope
import dev.latch.rule.core.model.PermissionMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.data.InstalledApp
import dev.latch.rule.data.OpLabels
import dev.latch.rule.ui.components.AppPickerDialog
import dev.latch.rule.ui.theme.LatchDimens
import java.util.UUID

private val CAPABILITY_TABS = listOf(
    R.string.editor_tab_appops,
    R.string.editor_tab_permission,
    R.string.editor_tab_force_stop,
    R.string.editor_tab_enabled,
)

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
                is Capability.PermissionState -> 1
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
        mutableStateOf((initialCapability as? Capability.PermissionState)?.permission ?: COMMON_PERMISSIONS.first())
    }
    var permissionMode by rememberSaveable {
        mutableStateOf((initialCapability as? Capability.PermissionState)?.mode ?: PermissionMode.ALLOW)
    }
    var appEnabled by rememberSaveable {
        mutableStateOf((initialCapability as? Capability.SetEnabled)?.enabled ?: false)
    }
    // The app list may load after the editor opens; fill the label once it does.
    LaunchedEffect(apps) {
        if (appLabel.isBlank() && packageName.isNotBlank()) {
            appLabel = apps.firstOrNull { it.packageName == packageName }?.label.orEmpty()
        }
    }

    var pickerOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickAppError = stringResource(R.string.editor_error_pick_app)
    val forceStopName = stringResource(R.string.editor_tab_force_stop)
    val enableName = stringResource(R.string.editor_enable_app)
    val disableName = stringResource(R.string.editor_disable_app)
    val allowName = stringResource(R.string.permission_allow)
    val denyName = stringResource(R.string.permission_deny)
    fun permissionName(capability: Capability): String {
        val mode = (capability as? Capability.PermissionState)?.mode ?: return ""
        return if (mode == PermissionMode.ALLOW) allowName else denyName
    }

    fun save() {
        if (packageName.isBlank()) {
            error = pickAppError
            return
        }
        val capability = when (capabilityTab) {
            1 -> Capability.PermissionState(permission, permissionMode)
            2 -> Capability.ForceStop
            3 -> Capability.SetEnabled(appEnabled)
            else -> Capability.AppOp(op, mode, scope)
        }
        onSave(
            Rule(
                id = initialRule?.id ?: UUID.randomUUID().toString(),
                name = ruleName.ifBlank {
                    defaultRuleName(capability, forceStopName, enableName, disableName, permissionName(capability))
                },
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
                title = { Text(stringResource(if (initialRule == null) R.string.editor_new else R.string.editor_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = { TextButton(onClick = ::save) { Text(stringResource(R.string.common_save)) } },
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
                label = { Text(stringResource(R.string.editor_name)) },
                placeholder = { Text(stringResource(R.string.editor_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                SectionLabel(stringResource(R.string.editor_target))
                OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            appLabel.isNotBlank() -> "$appLabel · $packageName"
                            packageName.isNotBlank() -> packageName
                            else -> stringResource(R.string.editor_pick_app)
                        },
                    )
                }
            }

            Column {
                SectionLabel(stringResource(R.string.editor_capability))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CAPABILITY_TABS.forEachIndexed { index, label ->
                        FilterChip(
                            selected = capabilityTab == index,
                            onClick = { capabilityTab = index },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (capabilityTab) {
                        1 -> stringResource(R.string.editor_desc_permission)
                        2 -> stringResource(R.string.editor_desc_force_stop)
                        3 -> stringResource(R.string.editor_desc_enabled)
                        else -> stringResource(R.string.editor_desc_appops)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (capabilityTab) {
                0 -> {
                    Column {
                        SectionLabel(stringResource(R.string.editor_op))
                        DropdownField(
                            value = OpLabels.label(op),
                            options = OpLabels.knownOps().map { it to "${OpLabels.label(it)} · $it" },
                            onSelect = { op = it },
                        )
                    }
                    Column {
                        SectionLabel(stringResource(R.string.editor_mode))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpMode.entries.forEach { entry ->
                                FilterChip(
                                    selected = mode == entry,
                                    onClick = { mode = entry },
                                    label = { Text(entry.wire) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.editor_mode_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        SectionLabel(stringResource(R.string.editor_scope))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = scope == OpScope.UID,
                                onClick = { scope = OpScope.UID },
                                label = { Text(stringResource(R.string.editor_scope_uid)) },
                            )
                            FilterChip(
                                selected = scope == OpScope.PACKAGE,
                                onClick = { scope = OpScope.PACKAGE },
                                label = { Text(stringResource(R.string.editor_scope_package)) },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.editor_scope_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                1 -> {
                    Column {
                        SectionLabel(stringResource(R.string.editor_permission))
                        DropdownField(
                            value = permission,
                            options = COMMON_PERMISSIONS.map { it to it },
                            onSelect = {
                                permission = it
                            },
                        )
                    }
                    Column {
                        SectionLabel(stringResource(R.string.editor_action))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            permissionModes().forEach { entry ->
                                FilterChip(
                                    selected = permissionMode == entry,
                                    onClick = { permissionMode = entry },
                                    label = {
                                        Text(
                                            stringResource(
                                                if (entry == PermissionMode.ALLOW) {
                                                    R.string.permission_allow
                                                } else {
                                                    R.string.permission_deny
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.editor_permission_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                2 -> {
                    Text(
                        stringResource(R.string.editor_force_stop_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                3 -> {
                    Column {
                        SectionLabel(stringResource(R.string.editor_target_state))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = appEnabled,
                                onClick = { appEnabled = true },
                                label = { Text(stringResource(R.string.editor_enable_app)) },
                            )
                            FilterChip(
                                selected = !appEnabled,
                                onClick = { appEnabled = false },
                                label = { Text(stringResource(R.string.editor_disable_app)) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.editor_enabled_hint),
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

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.editor_save_rule)) }

            if (onDelete != null && initialRule != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.editor_delete_rule), color = MaterialTheme.colorScheme.error)
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
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initialRule.id)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
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

private fun permissionModes(): List<PermissionMode> = listOf(PermissionMode.ALLOW, PermissionMode.DENY)

private fun defaultRuleName(
    capability: Capability,
    forceStopName: String,
    enableName: String,
    disableName: String,
    permissionName: String,
): String = when (capability) {
    is Capability.AppOp -> OpLabels.label(capability.op)
    is Capability.PermissionState ->
        "$permissionName ${capability.permission.substringAfterLast('.')}"
    Capability.ForceStop -> forceStopName
    is Capability.SetEnabled -> if (capability.enabled) enableName else disableName
}
