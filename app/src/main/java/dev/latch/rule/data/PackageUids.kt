package dev.latch.rule.data

import android.content.Context
import dev.latch.rule.core.rules.UidLookup

/**
 * Reads package uids from the package manager and caches them, including misses,
 * so conflict detection does not query the system for the same package repeatedly.
 */
class PackageUids(private val context: Context) : UidLookup {

    private val cache = HashMap<String, Int?>()

    override fun uidOf(packageName: String): Int? = synchronized(cache) {
        if (cache.containsKey(packageName)) {
            cache[packageName]
        } else {
            val uid = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).uid
            }.getOrNull()
            cache[packageName] = uid
            uid
        }
    }
}
