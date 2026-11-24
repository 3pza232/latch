package dev.latch.rule.data

import android.content.Context
import dev.latch.rule.core.model.RuleDocument
import java.io.File

/** Outcome of reading the rule document, including the cases that must not be treated as "no rules". */
sealed interface RuleLoadResult {

    data class Success(val document: RuleDocument) : RuleLoadResult

    /** First run, or the document was removed by a deliberate reset. */
    data object Missing : RuleLoadResult

    /** The document could not be parsed; the original file is preserved, never overwritten. */
    data class Corrupt(val message: String, val preservedAs: String?) : RuleLoadResult
}

/** Persistence boundary for the rule document. */
interface RuleStore {
    fun load(): RuleLoadResult
    fun save(document: RuleDocument)
}

/**
 * Stores rules as a single JSON document inside the app's private files directory.
 * Upgrades of the app keep the data directory, so rules survive overwrite installs.
 */
class FileRuleStore(private val directory: File) : RuleStore {

    constructor(context: Context) : this(context.filesDir)

    private val file: File
        get() = File(directory, FILE_NAME)

    override fun load(): RuleLoadResult {
        val source = file
        if (!source.exists()) return RuleLoadResult.Missing
        return try {
            RuleLoadResult.Success(RuleCodec.decode(source.readText()))
        } catch (e: Exception) {
            // Renaming keeps the unreadable document recoverable instead of letting the
            // next save replace it with an empty group list.
            val preserved = File(source.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
            val moved = runCatching { source.renameTo(preserved) }.getOrDefault(false)
            RuleLoadResult.Corrupt(
                message = e.message ?: "无法解析规则文件",
                preservedAs = if (moved) preserved.name else null,
            )
        }
    }

    override fun save(document: RuleDocument) {
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(RuleCodec.encode(document))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "rules.json"
    }
}
