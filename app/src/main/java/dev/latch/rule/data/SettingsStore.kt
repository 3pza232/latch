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
    )

    override fun save(settings: LatchSettings) {
        prefs.edit {
            putBoolean(KEY_OPEN, settings.autoVerifyOnOpen)
            putBoolean(KEY_BOOT, settings.autoVerifyOnBoot)
            putInt(KEY_PERIODIC, settings.periodicMinutes)
            putBoolean(KEY_NOTIFY, settings.notifyOnDrift)
            putBoolean(KEY_HIDE_RECENTS, settings.hideFromRecents)
        }
    }

    private companion object {
        const val FILE_NAME = "latch_settings"
        const val KEY_OPEN = "auto_verify_on_open"
        const val KEY_BOOT = "auto_verify_on_boot"
        const val KEY_PERIODIC = "periodic_minutes"
        const val KEY_NOTIFY = "notify_on_drift"
        const val KEY_HIDE_RECENTS = "hide_from_recents"
    }
}
