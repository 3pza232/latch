package dev.latch.rule.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.privilege.PrivilegeStatus
import dev.latch.rule.ui.theme.LatchDimens

@Composable
fun ShizukuStatusCard(
    status: PrivilegeStatus,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(LatchDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (status) {
                        PrivilegeStatus.READY -> "已连接 · 可以应用规则"
                        PrivilegeStatus.NOT_GRANTED -> "已运行 · 需要授权本应用"
                        PrivilegeStatus.UNAVAILABLE -> "未运行 · 请在手机上启动 Shizuku"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (status) {
                PrivilegeStatus.READY -> StatusChip(RuleState.APPLIED)
                PrivilegeStatus.NOT_GRANTED -> Button(onClick = onRequestPermission) { Text("授权") }
                PrivilegeStatus.UNAVAILABLE -> TextButton(onClick = onRefresh) { Text("重新检测") }
            }
        }
    }
}
