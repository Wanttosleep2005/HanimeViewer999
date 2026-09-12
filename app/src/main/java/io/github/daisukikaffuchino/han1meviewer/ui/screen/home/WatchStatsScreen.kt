package io.github.daisukikaffuchino.han1meviewer.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.WatchStats
import io.github.daisukikaffuchino.han1meviewer.ui.component.CardContainerSurface
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeScaffold
import io.github.daisukikaffuchino.han1meviewer.ui.component.lazy.LazyColumn
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeDefaults
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 观看统计面板。
 *
 * 数据来自本机观看历史，**纯本地计算**，不联网、不读账号 —— 和「在线历史」不是一回事，
 * 所以这里能给出「连续天数 / 活跃时段」这类在线接口根本不会提供的指标。
 */
@Composable
fun WatchStatsScreen(
    stats: WatchStats,
    onBack: () -> Unit,
) {
    HanimeScaffold(
        title = stringResource(R.string.watch_stats),
        onBack = onBack,
    ) { paddingValues ->
        if (stats.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.watch_stats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@HanimeScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = stats.total.toString(),
                        label = stringResource(R.string.watch_stats_total),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = stats.thisMonth.toString(),
                        label = stringResource(R.string.watch_stats_this_month),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = stats.today.toString(),
                        label = stringResource(R.string.watch_stats_today),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = stats.currentStreak.toString(),
                        label = stringResource(R.string.watch_stats_streak_current),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = stats.longestStreak.toString(),
                        label = stringResource(R.string.watch_stats_streak_longest),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = stats.activeDays.toString(),
                        label = stringResource(R.string.watch_stats_active_days),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                StatsCard(title = stringResource(R.string.watch_stats_last7)) {
                    DailyBarChart(stats.dailyBuckets)
                }
            }

            item {
                StatsCard(title = stringResource(R.string.watch_stats_habit)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(
                                R.string.watch_stats_avg_per_day,
                                String.format(Locale.getDefault(), "%.1f", stats.averagePerActiveDay),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        stats.busiestHour?.let { hour ->
                            Text(
                                text = stringResource(
                                    R.string.watch_stats_busiest_hour,
                                    formatHourRange(hour),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        stats.busiestWeekday?.let { weekday ->
                            Text(
                                text = stringResource(
                                    R.string.watch_stats_busiest_weekday,
                                    weekdayName(weekday),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val span = formatSpan(stats.firstWatchAt, stats.lastWatchAt)
                        if (span != null) {
                            Text(
                                text = stringResource(R.string.watch_stats_span, span),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    CardContainerSurface(
        modifier = modifier,
        shape = HanimeDefaults.Corners.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    CardContainerSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = HanimeDefaults.Corners.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

/**
 * 近 7 天柱状图。
 *
 * 刻意**不引入图表库** —— 七个柱子用高度比例画就够了，为这点需求拖一个几百 KB 的依赖
 * 不值当，而且自己画才能跟着主题色走。
 */
@Composable
private fun DailyBarChart(buckets: List<Int>) {
    val maxValue = buckets.maxOrNull()?.coerceAtLeast(1) ?: 1
    val dayLabels = remember {
        val formatter = SimpleDateFormat("E", Locale.getDefault())
        val cal = Calendar.getInstance()
        // 索引 0 是 6 天前，最后一个是今天。
        (0..6).map { offset ->
            cal.timeInMillis = System.currentTimeMillis() - (6 - offset) * 86_400_000L
            formatter.format(Date(cal.timeInMillis))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEachIndexed { index, value ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (value > 0) value.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((6 + 46 * value / maxValue).dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        color = if (value > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                }
                Text(
                    text = dayLabels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 把整点格式化成「21:00 ~ 22:00」。 */
private fun formatHourRange(hour: Int): String =
    String.format(Locale.getDefault(), "%02d:00 ~ %02d:00", hour, (hour + 1) % 24)

/** 1=周日 … 7=周六（Calendar 约定）→ 本地化星期名。 */
private fun weekdayName(weekday: Int): String {
    val text = String.format(Locale.getDefault(), "%d", weekday)
    val formatter = SimpleDateFormat("EEEE", Locale.getDefault())
    val cal = Calendar.getInstance()
    // 用一个确定含该星期几的日期反查名字，避开手写各语言的星期表。
    cal.set(Calendar.DAY_OF_WEEK, weekday)
    return formatter.format(Date(cal.timeInMillis)).ifBlank { text }
}

private fun formatSpan(first: Long?, last: Long?): String? {
    if (first == null || last == null) return null
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return "${formatter.format(Date(first))} ~ ${formatter.format(Date(last))}"
}

@Preview(showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun WatchStatsScreenPreview() {
    ComponentPreview {
        WatchStatsScreen(
            stats = WatchStats(
                total = 128,
                today = 3,
                last7Days = 17,
                thisMonth = 42,
                activeDays = 36,
                currentStreak = 5,
                longestStreak = 12,
                busiestWeekday = Calendar.FRIDAY,
                busiestHour = 22,
                dailyBuckets = listOf(1, 4, 2, 0, 3, 4, 3),
                firstWatchAt = System.currentTimeMillis() - 40L * 86_400_000,
                lastWatchAt = System.currentTimeMillis(),
            ),
            onBack = {},
        )
    }
}
