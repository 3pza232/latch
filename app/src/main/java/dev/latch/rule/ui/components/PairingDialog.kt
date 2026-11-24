package dev.latch.rule.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.latch.rule.gateway.AgentGateway

/** Stays open until a client connects; closing it stops the local gateway. */
@Composable
fun PairingDialog(
    gateway: AgentGateway.State,
    onClose: () -> Unit,
) {
    val address = "http://${gateway.address ?: "-"}:${gateway.port}"
    val code = gateway.code.orEmpty()
    val clipboard = LocalClipboardManager.current
    val connectionInfo = "地址：$address\n配对码：$code"
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.large,
        title = { Text("局域网连接") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                InfoBlock(
                    label = "地址",
                    value = address,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                InfoBlock(
                    label = "配对码",
                    value = code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(connectionInfo))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (copied) "已复制" else "复制连接信息")
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "等待连接…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

@Composable
private fun InfoBlock(label: String, value: String, style: TextStyle) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = style,
            fontFamily = FontFamily.Monospace,
        )
    }
}
