package dev.latch.rule.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.latch.rule.gateway.AgentGateway

/** Stays open until a client connects; closing it stops the local gateway. */
@Composable
fun PairingDialog(
    gateway: AgentGateway.State,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.large,
        title = { Text("局域网连接") },
        text = {
            val ticket = gateway.ticket
            val clipboard = LocalClipboardManager.current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "在同一局域网内访问",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "http://${gateway.address ?: "-"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                if (ticket != null) {
                    Text(
                        "把这串配对码交给 agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ticket,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(ticket)) }) {
                        Text("复制配对串")
                    }
                    Text(
                        "末位 ${ticket.take(3)} · 端口 ${ticket.substring(3, 8)} · " +
                            "配对码 ${ticket.takeLast(6).chunked(3).joinToString(" ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Not on IPv4: the client needs the pieces separately.
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        BigField(label = "端口", value = gateway.port.toString())
                        BigField(
                            label = "配对码",
                            value = gateway.code.orEmpty().chunked(3).joinToString(" "),
                        )
                    }
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
private fun BigField(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
