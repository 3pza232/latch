package dev.latch.rule.data

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupSnapshot
import java.io.File

/** Persistence for backup snapshots. */
interface BackupStore {
    fun list(): List<BackupMeta>
    fun load(id: String): BackupSnapshot?
    fun save(snapshot: BackupSnapshot)
    fun delete(id: String)
}

/**
 * Stores one self-contained JSON file per snapshot inside the app's private files.
 * Files are written through a temporary file so an interrupted write cannot replace
 * a good snapshot with a partial one.
 */
class FileBackupStore(private val directory: File) : BackupStore {

    override fun list(): List<BackupMeta> = snapshotFiles()
        .mapNotNull { file -> runCatching { BackupCodec.decode(file.readText()) }.getOrNull() }
        .map { it.meta }
        .sortedByDescending { it.createdAt }

    override fun load(id: String): BackupSnapshot? {
        if (!BackupCodec.isValidId(id)) return null
        val file = File(directory, "$id.json")
        if (!file.exists()) return null
        return runCatching { BackupCodec.decode(file.readText()) }.getOrNull()
    }

    override fun save(snapshot: BackupSnapshot) {
        require(BackupCodec.isValidId(snapshot.meta.id)) { "invalid snapshot id" }
        directory.mkdirs()
        val target = File(directory, "${snapshot.meta.id}.json")
        val temp = File(directory, "${snapshot.meta.id}.json.tmp")
        temp.writeText(BackupCodec.encode(snapshot))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    override fun delete(id: String) {
        if (!BackupCodec.isValidId(id)) return
        runCatching { File(directory, "$id.json").delete() }
    }

    private fun snapshotFiles(): List<File> =
        directory.listFiles { file -> file.name.endsWith(".json") }?.toList().orEmpty()
}
