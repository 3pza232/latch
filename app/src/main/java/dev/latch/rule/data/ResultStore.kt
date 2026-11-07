package dev.latch.rule.data

import android.content.Context
import dev.latch.rule.core.model.RuleState
import org.json.JSONObject
import java.io.File

/** Last known verification outcome for one rule. */
data class StoredResult(
    val ruleId: String,
    val state: RuleState,
    val expected: String,
    val actual: String,
    val message: String?,
    val checkedAt: Long,
)

/** Verification snapshot persisted across app restarts. */
data class VerificationSnapshot(
    val results: Map<String, StoredResult> = emptyMap(),
    val verifiedAt: Long = 0L,
)

interface ResultStore {
    fun load(): VerificationSnapshot
    fun save(snapshot: VerificationSnapshot)
}

/** Persists the last verification snapshot so the UI never falls back to a blank state. */
class FileResultStore(private val context: Context) : ResultStore {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    override fun load(): VerificationSnapshot = runCatching {
        val source = file
        if (!source.exists()) VerificationSnapshot() else decode(source.readText())
    }.getOrDefault(VerificationSnapshot())

    override fun save(snapshot: VerificationSnapshot) {
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(encode(snapshot))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun encode(snapshot: VerificationSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("verifiedAt", snapshot.verifiedAt)
        put("results", JSONObject().apply {
            snapshot.results.forEach { (id, result) ->
                put(id, JSONObject().apply {
                    put("state", result.state.name)
                    put("expected", result.expected)
                    put("actual", result.actual)
                    put("message", result.message)
                    put("checkedAt", result.checkedAt)
                })
            }
        })
    }.toString(2)

    private fun decode(text: String): VerificationSnapshot {
        val root = JSONObject(text)
        val resultsJson = root.optJSONObject("results") ?: JSONObject()
        val results = buildMap {
            resultsJson.keys().forEach { id ->
                val item = resultsJson.getJSONObject(id)
                put(
                    id,
                    StoredResult(
                        ruleId = id,
                        state = RuleState.entries.firstOrNull { it.name == item.optString("state") }
                            ?: RuleState.PENDING,
                        expected = item.optString("expected"),
                        actual = item.optString("actual"),
                        message = item.optString("message").takeIf { it.isNotBlank() },
                        checkedAt = item.optLong("checkedAt"),
                    ),
                )
            }
        }
        return VerificationSnapshot(results, root.optLong("verifiedAt"))
    }

    private companion object {
        const val FILE_NAME = "results.json"
    }
}
