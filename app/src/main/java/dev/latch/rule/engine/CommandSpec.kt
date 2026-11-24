package dev.latch.rule.engine

import dev.latch.rule.core.model.PermissionMode

/** Extracts a comparable value from a command's stdout, or `null` when it cannot be determined. */
fun interface OutputParser {
    fun extract(output: String): String?
}

/**
 * How to apply one capability and how to read it back.
 *
 * Applying is a list of steps because some capabilities need more than one command
 * (for example a permission change also clears the AppOps override left by the
 * opposite rule). Steps run independently so one failure is never masked by a
 * later success.
 */
data class CommandSpec(
    val applyCommands: List<String>,
    val verifyCommand: String,
    val expected: String,
    val parser: OutputParser,
) {
    constructor(
        applyCommand: String,
        verifyCommand: String,
        expected: String,
        parser: OutputParser,
    ) : this(listOf(applyCommand), verifyCommand, expected, parser)
}

/**
 * Reads the effective AppOps mode for the package.
 *
 * The system resolves overrides in a fixed order: UID-level, then package-level,
 * then the system default. `cmd appops get` prints whichever of these exists, so the
 * parser follows the same precedence. Scope only decides where a rule *writes*, not
 * which value is in force.
 *
 * When no override exists the value is reported as `default(<resolved>)`, which
 * satisfies a rule whose expected mode is `default` while remaining informative.
 */
class AppOpParser(op: String) : OutputParser {

    private val uidMode = Regex("""Uid mode:\s*${Regex.escape(op)}:\s*(\w+)""")
    private val packageMode = Regex("""(?m)^\s*${Regex.escape(op)}:\s*(\w+)""")
    private val defaultMode = Regex("""Default mode:\s*(\w+)""")

    override fun extract(output: String): String? {
        uidMode.find(output)?.groupValues?.getOrNull(1)?.let { return it }
        packageMode.find(output)?.groupValues?.getOrNull(1)?.let { return it }
        val resolved = defaultMode.find(output)?.groupValues?.getOrNull(1) ?: return null
        return "default($resolved)"
    }
}

/**
 * Reads a runtime permission and, when the permission maps to an AppOp, the AppOps
 * state that can still deny it after the permission itself was granted.
 */
class PermissionStateParser(
    private val permission: String,
    private val op: String?,
    private val mode: PermissionMode,
) : OutputParser {

    private val opIgnore = op?.let { Regex("""(?:Uid mode:\s*)?${Regex.escape(it)}:\s*(\w+)""") }
    private val grantedFlag = Regex("""${Regex.escape(permission)}:\s*granted=(true|false)""")

    override fun extract(output: String): String? {
        val granted = checkPermission(output) ?: return null
        return when (mode) {
            PermissionMode.ALLOW -> when {
                !granted -> "denied"
                isOpIgnored(output) -> "blocked"
                else -> "allow"
            }
            PermissionMode.DENY -> if (granted) "granted" else "deny"
        }
    }

    /** `dumpsys package check-permission` prints `0` when granted and `-1` when not. */
    private fun checkPermission(output: String): Boolean? =
        when (output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()) {
            "0" -> true
            "-1" -> false
            else -> grantedFlag.find(output)?.groupValues?.getOrNull(1)?.let { it == "true" }
        }

    private fun isOpIgnored(output: String): Boolean {
        val matcher = opIgnore ?: return false
        return matcher.find(output)?.groupValues?.getOrNull(1) == "ignore"
    }
}

/** Reads whether the app appears in the enabled or disabled package list of user 0. */
class EnabledStateParser(private val packageName: String) : OutputParser {

    override fun extract(output: String): String? {
        val sections = output.split(SECTION_SEPARATOR)
        if (sections.size < 2) return null
        val enabled = sections[0].containsPackage()
        val disabled = sections[1].containsPackage()
        return when {
            disabled -> "disabled"
            enabled -> "enabled"
            else -> null
        }
    }

    private fun String.containsPackage(): Boolean =
        lineSequence().any { it.trim() == "package:$packageName" }

    companion object {
        const val SECTION_SEPARATOR = "---LATCH---"
    }
}

/**
 * Reads whether a process for the package is running.
 *
 * `ps` output is parsed here rather than through `grep` because the shell command
 * line itself contains the package name and would otherwise be reported as a match.
 */
class ProcessStoppedParser(private val packageName: String) : OutputParser {

    override fun extract(output: String): String? {
        val running = output.lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any(::matchesProcess)
        return if (running) "running" else "stopped"
    }

    private fun matchesProcess(line: String): Boolean {
        val columns = line.split(WHITESPACE, limit = 3)
        val name = columns.getOrNull(1) ?: return false
        if (name == packageName) return true
        // The verifying shell itself must never count as a running app process.
        if (name in SHELL_PROGRAMS) return false
        val args = columns.getOrNull(2).orEmpty()
        return args.split(WHITESPACE).any { it == packageName || it.startsWith("$packageName:") }
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val SHELL_PROGRAMS = setOf("sh", "ps", "grep", "toybox")
    }
}
