package dev.latch.rule.gateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.latch.rule.LatchApplication
import dev.latch.rule.MainActivity
import dev.latch.rule.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive and reflects the live LAN connection state. */
class AgentGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val gateway = (application as LatchApplication).container.agentGateway
        startForeground(NOTIFICATION_ID, buildNotification(gateway.state.value))
        scope.launch {
            gateway.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(state))
                if (state.status == AgentGateway.Status.IDLE ||
                    state.status == AgentGateway.Status.ERROR
                ) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: AgentGateway.State): android.app.Notification {
        val connected = state.status == AgentGateway.Status.CONNECTED
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setColor(ContextCompat.getColor(this, R.color.ic_launcher_accent))
            .setContentTitle(
                getString(if (connected) R.string.notif_lan_connected else R.string.notif_lan_waiting),
            )
            .setContentText(
                getString(
                    if (connected) R.string.notif_lan_connected_text else R.string.notif_lan_waiting_text,
                ),
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_lan),
                    NotificationManager.IMPORTANCE_LOW,
                ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "latch-lan"
        const val NOTIFICATION_ID = 1002
    }
}
