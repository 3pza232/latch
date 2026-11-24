package dev.latch.rule.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.latch.rule.R

/** Compact relative time for "last verified" labels. */
@Composable
fun relativeTimeText(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.time_never_checked)
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000L -> stringResource(R.string.time_just_checked)
        delta < 3_600_000L ->
            stringResource(R.string.time_minutes_ago, (delta / 60_000L).toInt())
        delta < 86_400_000L ->
            stringResource(R.string.time_hours_ago, (delta / 3_600_000L).toInt())
        else ->
            stringResource(R.string.time_days_ago, (delta / 86_400_000L).toInt())
    }
}
