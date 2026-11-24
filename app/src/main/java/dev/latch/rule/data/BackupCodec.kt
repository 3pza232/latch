package dev.latch.rule.data

import dev.latch.rule.core.backup.BackupMeta
import dev.latch.rule.core.backup.BackupReason
import dev.latch.rule.core.backup.BackupSnapshot
import dev.latch.rule.core.model.RuleGroup
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Self-contained snapshot format: metadata plus the full rule document.
 * A snapshot can be restored without any other file, and exported as-is.
 */
object BackupCodec {

    const val SCHEMA = "latch.backup"
    const val VERSION = 1

    fun hash(groups: List<RuleGroup>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(RuleCodec.encode(groups).toByteArray(Charsets.UTF_8))
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
        put("document", JSONObject(RuleCodec.encode(snapshot.groups)))
    }.toString(2)

    fun decode(text: String): BackupSnapshot? = runCatching {
        val json = JSONObject(text)
        if (json.optString("schema") != SCHEMA) return@runCatching null
        val groups = RuleCodec.decode(json.getJSONObject("document").toString())
        val summary = json.optJSONObject("summary")
        BackupSnapshot(
            meta = BackupMeta(
                id = json.getString("id"),
                createdAt = json.getLong("createdAt"),
                reason = BackupReason.entries.firstOrNull { it.wire == json.optString("reason") }
                    ?: BackupReason.AUTO_CHANGE,
                groups = summary?.optInt("groups") ?: groups.size,
                rules = summary?.optInt("rules") ?: groups.sumOf { it.rules.size },
                docHash = json.optString("docHash"),
            ),
            groups = groups,
        )
    }.getOrNull()

    /** Snapshot ids become file names, so only a safe alphabet is accepted. */
    fun isValidId(id: String): Boolean = id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' }
}
