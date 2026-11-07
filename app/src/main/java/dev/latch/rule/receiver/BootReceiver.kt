package dev.latch.rule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.latch.rule.LatchApplication
import dev.latch.rule.work.VerifyScheduler

/** Re-runs verification after boot when the user enabled it. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = (context.applicationContext as LatchApplication).container.settingsStore.load()
        if (settings.autoVerifyOnBoot) {
            VerifyScheduler.verifyOnce(context)
        }
    }
}
