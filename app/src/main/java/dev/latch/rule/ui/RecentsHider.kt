package dev.latch.rule.ui

import android.app.Activity
import android.app.ActivityManager
import android.os.Build

/** Applies the user's "hide from recents" preference to the current task. */
object RecentsHider {

    fun apply(activity: Activity?, hide: Boolean) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = activity.getSystemService(ActivityManager::class.java) ?: return
        manager.appTasks
            .firstOrNull { it.taskInfo.taskId == activity.taskId }
            ?.setExcludeFromRecents(hide)
    }
}
