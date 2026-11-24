package dev.latch.rule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.latch.rule.R
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.ui.theme.LatchPalette

@Composable
fun statusColor(state: RuleState): Color {
    val dark = isSystemInDarkTheme()
    return when (state) {
        RuleState.APPLIED -> if (dark) LatchPalette.DarkSuccess else LatchPalette.Success
        RuleState.DRIFTED -> if (dark) LatchPalette.DarkWarning else LatchPalette.Warning
        RuleState.FAILED -> if (dark) LatchPalette.DarkDanger else LatchPalette.Danger
        RuleState.PENDING -> if (dark) LatchPalette.DarkInfo else LatchPalette.Info
        RuleState.DISABLED -> if (dark) LatchPalette.DarkMuted else LatchPalette.Muted
    }
}

@Composable
fun statusLabel(state: RuleState): String = stringResource(
    when (state) {
        RuleState.APPLIED -> R.string.status_applied
        RuleState.DRIFTED -> R.string.status_drifted
        RuleState.FAILED -> R.string.status_failed
        RuleState.PENDING -> R.string.status_pending
        RuleState.DISABLED -> R.string.status_disabled
    },
)

@Composable
fun StatusChip(state: RuleState, modifier: Modifier = Modifier, label: String = statusLabel(state)) {
    val color = statusColor(state)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
