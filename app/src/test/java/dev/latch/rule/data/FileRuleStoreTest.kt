package dev.latch.rule.data

import dev.latch.rule.core.model.Capability
import dev.latch.rule.core.model.OpMode
import dev.latch.rule.core.model.Rule
import dev.latch.rule.core.model.RuleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JvmRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRuleStoreTest {

    @get:JvmRule
    val folder = TemporaryFolder()

    @Test
    fun `missing document is reported, not treated as an empty rule set`() {
        val result = FileRuleStore(folder.root).load()

        assertTrue(result is RuleLoadResult.Missing)
    }

    @Test
    fun `saved document round trips`() {
        val store = FileRuleStore(folder.root)
        val group = RuleGroup(
            id = "g",
            name = "组",
            description = "",
            rules = listOf(
                Rule("r", "相机", "com.example.app", Capability.AppOp("CAMERA", OpMode.IGNORE)),
            ),
        )

        store.save(listOf(group))

        assertEquals(RuleLoadResult.Success(listOf(group)), store.load())
    }

    @Test
    fun `corrupt document is preserved instead of being silently dropped`() {
        val rulesFile = File(folder.root, "rules.json").apply { writeText("{ not json") }
        val store = FileRuleStore(folder.root)

        val result = store.load()

        assertTrue(result is RuleLoadResult.Corrupt)
        assertTrue(rulesFile.exists().not())
        val preserved = File(folder.root, (result as RuleLoadResult.Corrupt).preservedAs!!)
        assertEquals("{ not json", preserved.readText())
    }

    @Test
    fun `a corrupt document is never overwritten by the next save`() {
        File(folder.root, "rules.json").writeText("{ not json")
        val store = FileRuleStore(folder.root)
        store.load()

        store.save(emptyList())

        val preserved = folder.root.listFiles()!!.filter { it.name.startsWith("rules.json.corrupt-") }
        assertEquals(1, preserved.size)
        assertEquals("{ not json", preserved.single().readText())
    }
}
