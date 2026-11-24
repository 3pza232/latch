package dev.latch.rule.core.rules

import dev.latch.rule.core.model.RuleDocument

/** Structural checks applied before imported rules are written. */
object RuleValidation {

    private val PACKAGE = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

    /** Returns human-readable problems; an empty list means the document is acceptable. */
    fun validate(document: RuleDocument, policy: TargetPolicy): List<String> {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        document.rules().forEach { rule ->
            if (rule.id.isBlank()) {
                errors += "规则缺少 id（${rule.name}）"
            } else if (!seen.add(rule.id)) {
                errors += "重复的规则 id：${rule.id}"
            }
            if (!PACKAGE.matches(rule.targetPackage)) {
                errors += "无效的包名：${rule.targetPackage}"
            }
            policy.blockReason(rule)?.let { errors += "${rule.name}：$it" }
        }
        return errors
    }

    fun isValidPackage(packageName: String): Boolean = PACKAGE.matches(packageName)
}
