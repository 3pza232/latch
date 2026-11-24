package dev.latch.rule.domain

import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.data.FileBackupStore
import dev.latch.rule.data.RuleLoadResult
import dev.latch.rule.data.RuleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupServiceTest {

    @get:JvmRule
    val folder = TemporaryFolder()

    private lateinit var store: FileBackupStore
    private lateinit var ruleStore: InMemoryRuleStore
    private var now = 1_700_000_000_000L
    private lateinit var service: BackupService

    @Before
    fun setUp() {
        store = FileBackupStore(File(folder.root, "backups"))
        ruleStore = InMemoryRuleStore()
        ruleStore.save(listOf(group("相机")))
        service = BackupService(store, ruleStore, clock = { now })
    }

    @Test
    fun `an automatic snapshot is created for the current document`() = runBlocking {
        val meta = service.snapshotNow(BackupReason.AUTO_CHANGE)

        assertEquals(1, meta?.groups)
        assertEquals(listOf(meta!!.id), store.list().map { it.id })
    }

    @Test
    fun `unchanged content is not saved twice`() = runBlocking {
        service.snapshotNow(BackupReason.AUTO_CHANGE)
        now += 60_000

        assertNull(service.snapshotNow(BackupReason.AUTO_CHANGE))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a manual snapshot is created even when nothing changed`() = runBlocking {
        service.snapshotNow(BackupReason.AUTO_CHANGE)

        assertEquals(BackupReason.MANUAL, service.snapshotNow(BackupReason.MANUAL)?.reason)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `rapid edits are coalesced but later edits are captured`() = runBlocking {
        service.onDocumentChanged()
        now += 10_000
        ruleStore.save(listOf(group("通知")))

        service.onDocumentChanged()
        assertEquals(1, store.list().size)

        now += 60_000
        service.onDocumentChanged()
        assertEquals(2, store.list().size)
    }

    @Test
    fun `prune removes old automatic snapshots and keeps manual ones`() = runBlocking {
        service.snapshotNow(BackupReason.AUTO_CHANGE)
        now += 500L * 24 * 60 * 60 * 1000
        service.snapshotNow(BackupReason.MANUAL)

        val removed = service.prune()

        assertEquals(1, removed)
        assertEquals(listOf(BackupReason.MANUAL), store.list().map { it.reason })
        assertTrue(service.list().size == 1)
    }

    private fun group(name: String) = RuleGroup(
        id = "g",
        name = name,
        description = "",
        rules = listOf(
            Rule("r", "相机", "com.example.app", Capability.AppOp("CAMERA", OpMode.IGNORE)),
        ),
    )

    private class InMemoryRuleStore : RuleStore {
        private var groups: List<RuleGroup> = emptyList()

        override fun load(): RuleLoadResult = RuleLoadResult.Success(groups)

        override fun save(groups: List<RuleGroup>) {
            this.groups = groups
        }
    }
}
