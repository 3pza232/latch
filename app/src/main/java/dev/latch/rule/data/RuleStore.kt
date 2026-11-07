package dev.latch.rule.data

import android.content.Context
import dev.latch.rule.core.model.RuleGroup
import java.io.File

/** Persistence boundary for rule groups. */
interface RuleStore {
    fun load(): List<RuleGroup>
    fun save(groups: List<RuleGroup>)
}

/**
 * Stores rules as a single JSON document inside the app's private files directory.
 * Upgrades of the app keep the data directory, so rules survive overwrite installs.
 */
class FileRuleStore(private val context: Context) : RuleStore {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    override fun load(): List<RuleGroup> = runCatching {
        val source = file
        if (!source.exists()) emptyList() else RuleCodec.decode(source.readText())
    }.getOrDefault(emptyList())

    override fun save(groups: List<RuleGroup>) {
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(RuleCodec.encode(groups))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "rules.json"
    }
}
