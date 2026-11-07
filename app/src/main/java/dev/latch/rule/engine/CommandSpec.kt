package dev.latch.rule.engine

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

class PermissionGrantParser(permission: String) : OutputParser {

    private val shortName = permission.substringAfterLast('.')
    private val granted = Regex("$shortName: granted=(true|false)")

    override fun extract(output: String): String? =
        granted.find(output)?.let { "granted=${it.groupValues[1]}" }
}

object ProcessStoppedParser : OutputParser {
    override fun extract(output: String): String? = if (output.isBlank()) "stopped" else "running"
}

object EnabledStateParser : OutputParser {

    private val enabledValue = Regex("User 0:.*?enabled=(\\d)")

    override fun extract(output: String): String? =
        enabledValue.find(output)?.groupValues?.getOrNull(1)?.let { if (it == "1") "enabled" else "disabled" }
}
