package dev.latch.rule.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.data.StoredResult

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleRow(
    rule: Rule,
    state: RuleState,
    result: StoredResult?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    selected: Boolean = false,
    /** Why this rule is not enforced (covered by another rule, or a protected target). */
    notice: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val disabled = !rule.enabled || state == RuleState.DISABLED
        val contentAlpha = if (disabled) 0.45f else 1f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha),
            ) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    rule.targetPackage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "期望 ${it.expected} · 实际 ${it.actual}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    it.message?.takeIf { message -> message.isNotBlank() }?.let { message ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "原因：$message",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (!disabled) {
                Spacer(Modifier.width(8.dp))
                if (notice != null) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    StatusChip(state)
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
