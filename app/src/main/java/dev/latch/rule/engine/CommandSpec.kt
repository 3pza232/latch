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

/** Compares the permission grant state (and AppOps foreground where relevant). */
class PermissionStateParser(
    private val permission: String,
    private val mode: PermissionMode,
    private val op: String?,
) : OutputParser {

    override fun extract(output: String): String {
        val granted = grantOf(output, permission).first
        val foreground = op != null &&
            Regex("Uid mode:\\s*${Regex.escape(op)}\\s*:\\s*foreground").containsMatchIn(output)
        return when (mode) {
            PermissionMode.ALLOW ->
                if (granted == true) "allow" else "denied"

            PermissionMode.DENY ->
                if (granted == false) "deny" else "granted"

            PermissionMode.WHILE_IN_USE ->
                when {
                    granted == true && foreground -> "foreground"
                    granted == true -> "allow"
                    else -> "denied"
                }

            PermissionMode.APPROXIMATE -> {
                val coarse = grantOf(output, "android.permission.ACCESS_COARSE_LOCATION").first
                val fine = grantOf(output, "android.permission.ACCESS_FINE_LOCATION").first
                when {
                    coarse == true && fine == false -> "approximate"
                    coarse == true -> "precise"
                    else -> "denied"
                }
            }

            PermissionMode.SELECTED -> {
                val visual = grantOf(output, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                val images = grantOf(output, "android.permission.READ_MEDIA_IMAGES")
                val video = grantOf(output, "android.permission.READ_MEDIA_VIDEO")
                val userSet = listOf(visual, images, video).all { it.second.contains("USER_SET") }
                if (visual.first == true && images.first == false && video.first == false && userSet) {
                    "selected"
                } else {
                    "denied"
                }
            }
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
