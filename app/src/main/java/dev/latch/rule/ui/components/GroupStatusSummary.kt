package dev.latch.rule.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.ui.LatchViewModel

/** Shows both the aggregate state and the underlying counts, so mixed states stay readable. */
@Composable
fun GroupStatusSummary(
    summary: LatchViewModel.GroupSummary,
    verifiedAt: Long,
    modifier: Modifier = Modifier,
) {
    val label = when {
        summary.applied > 0 && summary.drifted > 0 -> "部分漂移"
        summary.applied > 0 && summary.failed > 0 -> "部分失败"
        summary.applied > 0 && summary.pending > 0 -> "部分待应用"
        else -> statusLabel(summary.state)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state = summary.state, label = label)
            Spacer(Modifier.width(8.dp))
            Text(
                "${summary.applied}/${summary.total} 生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.drifted > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${summary.drifted} 漂移",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(RuleState.DRIFTED),
                )
            }
            if (summary.failed > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${summary.failed} 失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(RuleState.FAILED),
                )
            }
            if (summary.disabled > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${summary.disabled} 停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(RuleState.DISABLED),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            RelativeTime.text(verifiedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
