package dev.latch.rule.engine

import dev.latch.rule.core.model.PermissionMode

/** Extracts a comparable value from a command's stdout. */
fun interface OutputParser {
    fun extract(output: String): String?
}

/** Apply/verify command pair for one capability, plus how to compare the result. */
data class CommandSpec(
    val applyCommand: String,
    val verifyCommand: String,
    val expected: String,
    val parser: OutputParser,
)

class AppOpParser(private val op: String) : OutputParser {

    private val uidMode = Regex("Uid mode:\\s*$op:\\s*([a-zA-Z_]+)")
    private val packageMode = Regex("(?:^|\\n)\\s*$op:\\s*([a-zA-Z_]+)")
    private val defaultMode = Regex("Default mode:\\s*([a-zA-Z_]+)")

    override fun extract(output: String): String? =
        uidMode.find(output)?.groupValues?.getOrNull(1)
            ?: packageMode.find(output)?.groupValues?.getOrNull(1)
            ?: if (defaultMode.containsMatchIn(output)) "default" else null
}

/** Compares the permission grant state (and AppOps ignore where relevant). */
class PermissionStateParser(
    private val permission: String,
    private val mode: PermissionMode,
) : OutputParser {

    override fun extract(output: String): String {
        val granted = grantOf(output, permission).first
        return when (mode) {
            PermissionMode.ALLOW -> if (granted == true) "allow" else "denied"
            PermissionMode.DENY -> if (granted == false) "deny" else "granted"
        }
    }

    private fun grantOf(output: String, permission: String): Pair<Boolean?, String> {
        val match = Regex(Regex.escape(permission) + ": granted=(true|false)(?:, flags=\\[([^\\]]*)\\])?")
            .find(output) ?: return null to ""
        return (match.groupValues[1] == "true") to match.groupValues.getOrNull(2).orEmpty()
    }
}

object ProcessStoppedParser : OutputParser {
    override fun extract(output: String): String? = if (output.isBlank()) "stopped" else "running"
}

object EnabledStateParser : OutputParser {

    private val enabledValue = Regex("User 0:.*?enabled=(\\d)")

    override fun extract(output: String): String? =
        enabledValue.find(output)?.groupValues?.getOrNull(1)?.let { if (it == "1") "enabled" else "disabled" }
}
