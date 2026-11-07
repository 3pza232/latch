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

/** Posts a notification only when automatic repair leaves rules broken. */
class DriftNotifier(private val context: Context) {

    fun notifyUnrepaired(drifted: Int, failed: Int) {
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
            NotificationChannel(CHANNEL_ID, "规则状态", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = buildString {
            if (drifted > 0) append("$drifted 条漂移")
            if (failed > 0) append(if (isEmpty()) "" else "，").append("$failed 条失败")
            append("；自动修复未成功，请打开 Latch 处理。")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle("规则自动修复失败")
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
