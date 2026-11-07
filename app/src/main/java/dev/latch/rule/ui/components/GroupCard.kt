package dev.latch.rule.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.theme.LatchDimens

@Composable
fun GroupCard(
    group: RuleGroup,
    summary: LatchViewModel.GroupSummary,
    verifiedAt: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(LatchDimens.CardPadding)) {
            val contentAlpha = if (group.enabled) 1f else 0.45f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(contentAlpha),
                ) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    if (group.enabled) {
                        GroupStatusSummary(summary = summary, verifiedAt = verifiedAt)
                    } else {
                        Text(
                            "已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = group.enabled, onCheckedChange = onToggle)
            }
            if (group.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    group.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(contentAlpha),
                )
            }
        }
    }
}
