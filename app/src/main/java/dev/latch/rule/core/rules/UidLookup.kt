package dev.latch.rule.core.rules

/**
 * Resolves the Linux uid a package runs as. Two packages can share a uid (for
 * example an app and its companion), which makes a UID-scoped AppOps rule affect
 * both. Implementations should cache; pure tests use [None].
 */
fun interface UidLookup {
    fun uidOf(packageName: String): Int?

    companion object {
        val None: UidLookup = UidLookup { null }
    }
}
