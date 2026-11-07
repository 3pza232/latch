package dev.latch.rule.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.latch.rule.R

/** Posts a single "rules drifted" notification; silently no-ops without permission. */
class DriftNotifier(private val context: Context) {

    fun notifyDrift(drifted: Int, failed: Int) {
        if (drifted + failed == 0) return
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (needsPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "规则漂移", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = buildString {
            if (drifted > 0) append("$drifted 条漂移")
            if (failed > 0) append(if (isEmpty()) "" else "，").append("$failed 条失败")
            append("。打开 Latch 查看并修复。")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle("规则需要处理")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "latch-drift"
        const val NOTIFICATION_ID = 1001
    }
}
