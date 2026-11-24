package dev.latch.rule.domain

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.BackupSnapshot
import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleDocument
import dev.latch.rule.core.model.RuleGroup
import dev.latch.rule.data.FileBackupStore
import dev.latch.rule.data.RuleLoadResult
import dev.latch.rule.data.RuleStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        ruleStore.save(RuleDocument(groups = listOf(group("相机"))))
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
    fun `first activity of the day creates only the day boundary`() = runBlocking {
        service.onDocumentChanged()

        // The boundary already captured the current state, so no duplicate change snapshot.
        assertEquals(listOf(BackupReason.DAILY), store.list().map { it.reason })
    }

    @Test
    fun `a later change after the boundary creates a change snapshot`() = runBlocking {
        service.ensureBoundaries()
        ruleStore.save(RuleDocument(groups = listOf(group("通知"))))

        service.onDocumentChanged()

        val reasons = store.list().map { it.reason }
        assertTrue(reasons.contains(BackupReason.DAILY))
        assertTrue(reasons.contains(BackupReason.AUTO_CHANGE))
        assertEquals(2, store.list().size)
    }

    @Test
    fun `boundaries are not duplicated within the same hour or day`() = runBlocking {
        service.ensureBoundaries()
        service.ensureBoundaries()
        now += 10 * 60_000
        service.ensureBoundaries()

        assertEquals(1, store.list().count { it.reason == BackupReason.DAILY })
        assertEquals(0, store.list().count { it.reason == BackupReason.HOURLY })

        // The next clock hour has no daily snapshot, so an hourly one is captured.
        now += 60 * 60_000
        service.ensureBoundaries()

        assertEquals(1, store.list().count { it.reason == BackupReason.DAILY })
        assertEquals(1, store.list().count { it.reason == BackupReason.HOURLY })
    }

    @Test
    fun `a new hour replaces the previous hourly snapshot`() = runBlocking {
        service.ensureBoundaries()
        now += 60 * 60_000
        service.ensureBoundaries()
        now += 60 * 60_000
        service.ensureBoundaries()

        assertEquals(1, store.list().count { it.reason == BackupReason.HOURLY })
    }

    @Test
    fun `rapid edits are coalesced but later edits are captured`() = runBlocking {
        service.ensureBoundaries()
        ruleStore.save(RuleDocument(groups = listOf(group("通知"))))
        service.onDocumentChanged()
        val afterFirstChange = store.list().size

        now += 10_000
        ruleStore.save(RuleDocument(groups = listOf(group("短信"))))
        service.onDocumentChanged()
        assertEquals(afterFirstChange, store.list().size)

        now += 60_000
        ruleStore.save(RuleDocument(groups = listOf(group("定位"))))
        service.onDocumentChanged()
        assertEquals(afterFirstChange + 1, store.list().size)
    }

    @Test
    fun `prune keeps the newest change snapshots and pinned ones`() = runBlocking {
        repeat(105) { index ->
            val meta = BackupMeta(
                id = "b-change-$index",
                createdAt = 1_000L + index,
                reason = BackupReason.AUTO_CHANGE,
                groups = 1,
                rules = 1,
                docHash = "hash-$index",
            )
            store.save(BackupSnapshot(meta, RuleDocument()))
        }
        val manual = BackupMeta(
            id = "b-manual",
            createdAt = 2_000L,
            reason = BackupReason.MANUAL,
            groups = 0,
            rules = 0,
            docHash = "manual",
        )
        store.save(BackupSnapshot(manual, RuleDocument()))

        val removed = service.prune()

        assertEquals(5, removed)
        assertEquals(101, store.list().size)
        assertTrue(store.list().any { it.id == "b-manual" })
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
        private var document: RuleDocument = RuleDocument()

        override fun load(): RuleLoadResult = RuleLoadResult.Success(document)

        override fun save(document: RuleDocument) {
            this.document = document
        }
    }
}
