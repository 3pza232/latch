package dev.latch.rule.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.latch.rule.MainActivity
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
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_drift),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val text = when {
            drifted > 0 && failed > 0 ->
                context.getString(R.string.notif_drift_both, drifted, failed)
            drifted > 0 -> context.getString(R.string.notif_drift_only, drifted)
            else -> context.getString(R.string.notif_failed_only, failed)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle(context.getString(R.string.notif_drift_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "latch-drift"
        const val NOTIFICATION_ID = 1001
    }
}
