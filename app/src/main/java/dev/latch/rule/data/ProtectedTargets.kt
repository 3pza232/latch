package dev.latch.rule.data

import android.content.Context
import android.content.Intent
import dev.latch.rule.core.rules.TargetPolicy

/**
 * Resolves the current launcher at runtime instead of hard-coding vendor package
 * names, so the protection follows whatever home app the user actually uses.
 */
class ProtectedTargets(private val context: Context) {

    fun packages(): Set<String> {
        val launchers = runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .queryIntentActivities(home, 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return TargetPolicy.ALWAYS_PROTECTED + launchers
    }
}
