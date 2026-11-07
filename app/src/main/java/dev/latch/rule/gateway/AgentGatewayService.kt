package dev.latch.rule.gateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.latch.rule.R

/** Keeps the process alive (and visible to the user) while the gateway is listening. */
class AgentGatewayService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_latch)
        .setColor(ContextCompat.getColor(this, R.color.ic_launcher_accent))
        .setContentTitle("局域网连接已开启")
        .setContentText("等待连接；可在 Latch 设置中断开")
        .setOngoing(true)
        .build()
        .also {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "局域网连接", NotificationManager.IMPORTANCE_LOW),
            )
        }

    private companion object {
        const val CHANNEL_ID = "latch-lan"
        const val NOTIFICATION_ID = 1002
    }
}
