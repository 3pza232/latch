package dev.latch.rule.data

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.BackupSnapshot
import dev.latch.rule.core.model.RuleDocument
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Self-contained snapshot format: metadata plus the full rule document.
 * A snapshot can be restored without any other file, and exported as-is.
 */
object BackupCodec {

    const val SCHEMA = "latch.backup"
    const val VERSION = 1

    /** Content hash that ignores the write counter, so a no-op save is not a new snapshot. */
    fun hash(document: RuleDocument): String {
        val canonical = RuleCodec.encode(document.copy(revision = 0))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encode(snapshot: BackupSnapshot): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("version", VERSION)
        put("id", snapshot.meta.id)
        put("createdAt", snapshot.meta.createdAt)
        put("reason", snapshot.meta.reason.wire)
        put("docHash", snapshot.meta.docHash)
        put(
            "summary",
            JSONObject()
                .put("groups", snapshot.meta.groups)
                .put("rules", snapshot.meta.rules),
        )
        put("document", JSONObject(RuleCodec.encode(snapshot.document)))
    }.toString(2)

    fun decode(text: String): BackupSnapshot? = runCatching {
        val json = JSONObject(text)
        if (json.optString("schema") != SCHEMA) return@runCatching null
        val document = RuleCodec.decode(json.getJSONObject("document").toString())
        val summary = json.optJSONObject("summary")
        BackupSnapshot(
            meta = BackupMeta(
                id = json.getString("id"),
                createdAt = json.getLong("createdAt"),
                reason = BackupReason.entries.firstOrNull { it.wire == json.optString("reason") }
                    ?: BackupReason.AUTO_CHANGE,
                groups = summary?.optInt("groups") ?: document.groups.size,
                rules = summary?.optInt("rules") ?: document.rules().size,
                docHash = json.optString("docHash"),
            ),
            document = document,
        )
    }.getOrNull()

    /** Snapshot ids become file names, so only a safe alphabet is accepted. */
    fun isValidId(id: String): Boolean = id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' }
}
