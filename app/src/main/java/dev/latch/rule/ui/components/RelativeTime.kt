package dev.latch.rule.ui.components

/** Compact relative time for "last verified" labels. */
object RelativeTime {

    fun text(timestamp: Long): String {
        if (timestamp <= 0L) return "尚未校验"
        val delta = System.currentTimeMillis() - timestamp
        return when {
            delta < 60_000L -> "刚刚校验"
            delta < 3_600_000L -> "${delta / 60_000L} 分钟前校验"
            delta < 86_400_000L -> "${delta / 3_600_000L} 小时前校验"
            else -> "${delta / 86_400_000L} 天前校验"
        }
    }
}
