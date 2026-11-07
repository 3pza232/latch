package dev.latch.rule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

fun statusLabel(state: RuleState): String = when (state) {
    RuleState.APPLIED -> "已生效"
    RuleState.DRIFTED -> "已漂移"
    RuleState.FAILED -> "失败"
    RuleState.PENDING -> "待应用"
    RuleState.DISABLED -> "未启用"
}

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
