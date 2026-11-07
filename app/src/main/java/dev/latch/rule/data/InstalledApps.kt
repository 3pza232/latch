package dev.latch.rule.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/** Lists launchable or installed apps so the rule editor can pick a target. */
class InstalledApps(private val context: Context) {

    fun all(): List<InstalledApp> = runCatching {
        val manager = context.packageManager
        manager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.enabled }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(manager).toString().takeIf { it.isNotBlank() }
                        ?: info.packageName,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
