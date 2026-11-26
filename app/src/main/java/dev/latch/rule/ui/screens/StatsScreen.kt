package dev.latch.rule.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.latch.rule.R
import dev.latch.rule.core.model.RuleState
import dev.latch.rule.core.stats.DayStats
import dev.latch.rule.core.stats.StatsSnapshot
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.components.relativeTimeText
import dev.latch.rule.ui.theme.LatchDimens
import dev.latch.rule.ui.theme.LatchPalette

/** Counters aggregated from the event log, plus a seven-day chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    state: LatchViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val stats = state.stats

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LatchDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                stats == null && state.statsLoading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                stats == null -> Text(
                    stringResource(R.string.stats_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    SectionTitle(stringResource(R.string.stats_overview))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi(
                            stringResource(R.string.stats_active_days),
                            "${stats.activeDays}",
                            stringResource(R.string.stats_total_days, stats.totalDays),
                            Modifier.weight(1f),
                        )
                        Kpi(
                            stringResource(R.string.stats_streak),
                            "${stats.streakDays}",
                            stringResource(R.string.stats_streak_sub),
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi(
                            stringResource(R.string.stats_apply_runs),
                            "${stats.applyRuns}",
                            stringResource(R.string.stats_apply_runs_sub),
                            Modifier.weight(1f),
                        )
                        Kpi(
                            stringResource(R.string.stats_verify_runs),
                            "${stats.verifyRuns}",
                            stringResource(R.string.stats_verify_runs_sub),
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi(
                            stringResource(R.string.stats_repaired),
                            "${stats.repaired}",
                            stringResource(R.string.stats_rule_unit),
                            Modifier.weight(1f),
                        )
                        Kpi(
                            stringResource(R.string.stats_failed),
                            "${stats.failed}",
                            stringResource(R.string.stats_rule_unit),
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi(
                            stringResource(R.string.stats_repair_rate),
                            stats.repairSuccessRate?.let { "$it%" } ?: "—",
                            stringResource(R.string.stats_rate_sub),
                            Modifier.weight(1f),
                        )
                        Kpi(
                            stringResource(R.string.stats_drifted),
                            "${stats.drifted}",
                            stringResource(R.string.stats_rule_unit),
                            Modifier.weight(1f),
                        )
                    }

                    Text(
                        stringResource(R.string.stats_scope),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SectionTitle(stringResource(R.string.stats_last_7_days))
                    WeekChart(stats.recentDays)

                    SectionTitle(stringResource(R.string.stats_rule_status))
                    RuleDistribution(state)

                    SectionTitle(stringResource(R.string.stats_other))
                    InfoRow(stringResource(R.string.stats_last_check), relativeTimeText(state.verifiedAt))
                    InfoRow(
                        stringResource(R.string.stats_first_record),
                        stats.firstEventAt?.let(::formatDate) ?: stringResource(R.string.stats_none),
                    )
                    Text(
                        stringResource(R.string.stats_footnote),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { confirmReset = true }) {
                        Text(stringResource(R.string.stats_reset), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.stats_reset_title)) },
            text = { Text(stringResource(R.string.stats_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text(stringResource(R.string.stats_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun Kpi(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One bar per day, divided into 24 hourly cells stacked from 0:00 at the bottom
 * to 23:00 at the top. A cell is green when the hour had rule activity without
 * issues, red when it contained a failure or drift, and neutral without activity.
 */
@Composable
private fun WeekChart(days: List<DayStats>) {
    val dark = isSystemInDarkTheme()
    val goodColor = if (dark) LatchPalette.DarkSuccess else LatchPalette.Success
    val badColor = if (dark) LatchPalette.DarkDanger else LatchPalette.Danger
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val hasData = days.any { it.activeHours.isNotEmpty() }

    Column {
        if (!hasData) {
            Text(
                stringResource(R.string.stats_no_records),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Column(
                            modifier = Modifier
                                .width(18.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        ) {
                            (23 downTo 0).forEach { hour ->
                                val color = when {
                                    hour in day.badHours -> badColor
                                    hour in day.activeHours -> goodColor
                                    else -> emptyColor
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .background(color),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Text(
                        day.date.takeLast(5),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Legend(goodColor, stringResource(R.string.stats_legend_ok))
                Spacer(Modifier.width(12.dp))
                Legend(badColor, stringResource(R.string.stats_legend_bad))
                Spacer(Modifier.width(12.dp))
                Legend(emptyColor, stringResource(R.string.stats_legend_empty))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.stats_chart_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuleDistribution(state: LatchViewModel.UiState) {
    var total = 0
    var applied = 0
    var drifted = 0
    var failed = 0
    var pending = 0
    var disabled = 0
    state.groups.forEach { group ->
        group.rules.forEach { rule ->
            total++
            if (!group.enabled || !rule.enabled) {
                disabled++
                return@forEach
            }
            when (state.results[rule.id]?.state) {
                RuleState.APPLIED -> applied++
                RuleState.DRIFTED -> drifted++
                RuleState.FAILED -> failed++
                else -> pending++
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow(stringResource(R.string.stats_rules_total), "$total")
        InfoRow(stringResource(R.string.stats_applied), "$applied")
        InfoRow(stringResource(R.string.stats_drifted_count), "$drifted")
        InfoRow(stringResource(R.string.stats_failed_count), "$failed")
        InfoRow(stringResource(R.string.stats_pending), "$pending")
        InfoRow(stringResource(R.string.stats_disabled), "$disabled")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

private fun formatDate(timestamp: Long): String =
    java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
