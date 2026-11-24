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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
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
        summary.applied > 0 && summary.drifted > 0 -> stringResource(R.string.summary_partial_drifted)
        summary.applied > 0 && summary.failed > 0 -> stringResource(R.string.summary_partial_failed)
        summary.applied > 0 && summary.pending > 0 -> stringResource(R.string.summary_partial_pending)
        else -> statusLabel(summary.state)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state = summary.state, label = label)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.summary_applied, summary.applied, summary.total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.drifted > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.summary_drifted, summary.drifted),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(RuleState.DRIFTED),
                )
            }
            if (summary.failed > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.summary_failed, summary.failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(RuleState.FAILED),
                )
            }
            if (summary.suppressed > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.summary_suppressed, summary.suppressed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.blocked > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.summary_blocked, summary.blocked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.disabled > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.summary_disabled, summary.disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(RuleState.DISABLED),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            relativeTimeText(verifiedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
