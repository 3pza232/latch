package dev.latch.rule.data

import android.content.Context
import androidx.core.content.edit

/** User-tunable behaviour for background verification. */
data class LatchSettings(
    val autoVerifyOnOpen: Boolean = true,
    val autoVerifyOnBoot: Boolean = false,
    val periodicMinutes: Int = 0,
    val notifyOnDrift: Boolean = false,
    val hideFromRecents: Boolean = false,
    /** Days of event log to keep; 0 means keep indefinitely. */
    val logRetentionDays: Int = 30,
    /** Re-apply rules that become active again when an override is lifted. */
    val autoReapplyOnUnsuppress: Boolean = true,
    /** Let a paired LAN client execute shell commands. */
    val allowRemoteShell: Boolean = true,
)

interface SettingsStore {
    fun load(): LatchSettings
    fun save(settings: LatchSettings)
}

class PreferencesSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): LatchSettings = LatchSettings(
        autoVerifyOnOpen = prefs.getBoolean(KEY_OPEN, true),
        autoVerifyOnBoot = prefs.getBoolean(KEY_BOOT, false),
        periodicMinutes = prefs.getInt(KEY_PERIODIC, 0),
        notifyOnDrift = prefs.getBoolean(KEY_NOTIFY, false),
        hideFromRecents = prefs.getBoolean(KEY_HIDE_RECENTS, false),
        logRetentionDays = prefs.getInt(KEY_LOG_RETENTION, 30),
        autoReapplyOnUnsuppress = prefs.getBoolean(KEY_AUTO_REAPPLY, true),
        allowRemoteShell = prefs.getBoolean(KEY_REMOTE_SHELL, true),
    )

    override fun save(settings: LatchSettings) {
        prefs.edit {
            putBoolean(KEY_OPEN, settings.autoVerifyOnOpen)
            putBoolean(KEY_BOOT, settings.autoVerifyOnBoot)
            putInt(KEY_PERIODIC, settings.periodicMinutes)
            putBoolean(KEY_NOTIFY, settings.notifyOnDrift)
            putBoolean(KEY_HIDE_RECENTS, settings.hideFromRecents)
            putInt(KEY_LOG_RETENTION, settings.logRetentionDays)
            putBoolean(KEY_AUTO_REAPPLY, settings.autoReapplyOnUnsuppress)
            putBoolean(KEY_REMOTE_SHELL, settings.allowRemoteShell)
        }
    }

    private companion object {
        const val FILE_NAME = "latch_settings"
        const val KEY_OPEN = "auto_verify_on_open"
        const val KEY_BOOT = "auto_verify_on_boot"
        const val KEY_PERIODIC = "periodic_minutes"
        const val KEY_NOTIFY = "notify_on_drift"
        const val KEY_HIDE_RECENTS = "hide_from_recents"
        const val KEY_LOG_RETENTION = "log_retention_days"
        const val KEY_AUTO_REAPPLY = "auto_reapply_after_unsuppress"
        const val KEY_REMOTE_SHELL = "allow_remote_shell"
    }
}
