package dev.latch.rule.data

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.BackupSnapshot
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBackupStoreTest {

    @get:JvmRule
    val folder = TemporaryFolder()

    private val group = RuleGroup(
        id = "g",
        name = "组",
        description = "",
        rules = listOf(
            Rule("r", "相机", "com.example.app", Capability.AppOp("CAMERA", OpMode.IGNORE)),
        ),
    )

    @Test
    fun `snapshots round trip and list newest first`() {
        val store = FileBackupStore(File(folder.root, "backups"))
        store.save(snapshot("b-1", 1_000))
        store.save(snapshot("b-2", 2_000))

        assertEquals(listOf("b-2", "b-1"), store.list().map { it.id })
        assertEquals(listOf(group), store.load("b-1")?.groups)
    }

    @Test
    fun `delete removes the snapshot`() {
        val store = FileBackupStore(File(folder.root, "backups"))
        store.save(snapshot("b-1", 1_000))

        store.delete("b-1")

        assertNull(store.load("b-1"))
    }

    @Test
    fun `unsafe ids are ignored`() {
        val store = FileBackupStore(File(folder.root, "backups"))

        assertNull(store.load("../rules"))
        store.delete("../../rules")
    }

    @Test
    fun `a corrupt snapshot file does not break listing`() {
        val directory = File(folder.root, "backups").apply { mkdirs() }
        File(directory, "bad.json").writeText("{ not json")
        val store = FileBackupStore(directory)
        store.save(snapshot("b-1", 1_000))

        assertEquals(listOf("b-1"), store.list().map { it.id })
    }

    private fun snapshot(id: String, createdAt: Long) = BackupSnapshot(
        meta = BackupMeta(
            id = id,
            createdAt = createdAt,
            reason = BackupReason.MANUAL,
            groups = 1,
            rules = 1,
            docHash = "hash-$id",
        ),
        document = RuleDocument(groups = listOf(group)),
    )
}
