package io.github.daisukikaffuchino.han1meviewer.logic.model

import io.github.daisukikaffuchino.han1meviewer.logic.entity.WatchHistoryEntity
import java.util.Calendar

/**
 * 本地观看记录的统计结果。
 *
 * 全是**纯计算**，不碰数据库也不碰网络 —— 输入一份观看历史，输出一堆能直接上屏的数字。
 * 这样统计面板就只是个渲染器，逻辑可以单独测。
 *
 * ## 为什么按「天」而不是按条数
 *
 * 观看历史每部作品只有一行（同 videoCode 会 `insertOrUpdate` 覆盖），所以「总条数」
 * 其实是「看过的作品数」，而不是「看了多少次」。真正反映习惯的是**时间维度**：
 * 哪天看的、一天里几点看、连续看了多久。所以这里的主指标是活跃天数、连续天数和时段分布。
 */
data class WatchStats(
    val total: Int,
    val today: Int,
    val last7Days: Int,
    val thisMonth: Int,
    val activeDays: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    /** 最常观看的星期，`Calendar.DAY_OF_WEEK` 约定：1=周日 … 7=周六。没记录则为 null。 */
    val busiestWeekday: Int?,
    /** 最常观看的整点，0..23。没记录则为 null。 */
    val busiestHour: Int?,
    /** 最近 7 天每天的条数，索引 0 = 6 天前，索引 6 = 今天。 */
    val dailyBuckets: List<Int>,
    val firstWatchAt: Long?,
    val lastWatchAt: Long?,
) {
    /** 平均每个活跃日看几部。 */
    val averagePerActiveDay: Double
        get() = if (activeDays <= 0) 0.0 else total.toDouble() / activeDays

    val isEmpty: Boolean get() = total == 0

    val maxDailyBucket: Int get() = dailyBuckets.maxOrNull() ?: 0

    companion object {

        /**
         * 时间戳归一化。
         *
         * ⚠️ 历史库里混着**秒**和**毫秒**两种量级：早期版本写入的是秒，后来改成毫秒，
         * 迁移时没有统一。卡片上一直是用 `if (ts < 9999999999L) ts * 1000` 兜的，
         * 这里照抄同一套判据，保证统计和列表显示不会各算各的。
         */
        private fun normalize(ts: Long): Long = if (ts < 9_999_999_999L) ts * 1000 else ts

        /** 某时刻所在自然日的 00:00。 */
        private fun startOfDay(cal: Calendar, ts: Long): Long {
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** 自然日的整数序号（相对排序用，不做时区换算，只要能单调比较即可）。 */
        private fun dayIndex(cal: Calendar, ts: Long): Long =
            Math.floorDiv(startOfDay(cal, ts), 86_400_000L)

        fun from(histories: List<WatchHistoryEntity>, now: Long = System.currentTimeMillis()): WatchStats {
            if (histories.isEmpty()) {
                return WatchStats(
                    total = 0, today = 0, last7Days = 0, thisMonth = 0,
                    activeDays = 0, currentStreak = 0, longestStreak = 0,
                    busiestWeekday = null, busiestHour = null,
                    dailyBuckets = List(7) { 0 },
                    firstWatchAt = null, lastWatchAt = null,
                )
            }

            val cal = Calendar.getInstance()
            val nowDay = dayIndex(cal, now)

            val timestamps = histories.map { normalize(it.watchDate) }

            // 按天聚合。dayCount 兼作活跃天数，键有序，后面推连续天数直接用。
            val perDay = HashMap<Long, Int>()
            val perWeekday = HashMap<Int, Int>()
            val perHour = HashMap<Int, Int>()

            for (ts in timestamps) {
                val day = dayIndex(cal, ts)
                perDay[day] = (perDay[day] ?: 0) + 1

                cal.timeInMillis = ts
                val weekday = cal.get(Calendar.DAY_OF_WEEK)
                perWeekday[weekday] = (perWeekday[weekday] ?: 0) + 1
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                perHour[hour] = (perHour[hour] ?: 0) + 1
            }

            // 当前连续：今天有记录就从今天往回数；今天没有但昨天有，说明连击还没断，
            // 从昨天往回数 —— 否则「今天还没看」会把好不容易攒的连续天数清零，很打击人。
            val anchor = when {
                perDay.containsKey(nowDay) -> nowDay
                perDay.containsKey(nowDay - 1) -> nowDay - 1
                else -> null
            }
            var currentStreak = 0
            if (anchor != null) {
                var cursor = anchor
                while (perDay.containsKey(cursor)) {
                    currentStreak++
                    cursor--
                }
            }

            // 历史最长连续：键排好后一次线性扫。
            var longestStreak = 0
            var run = 0
            var previous: Long? = null
            for (day in perDay.keys.sorted()) {
                run = if (previous != null && day == previous + 1) run + 1 else 1
                if (run > longestStreak) longestStreak = run
                previous = day
            }

            // 本月：同一年同一月。
            val nowCal = Calendar.getInstance().apply { timeInMillis = now }
            val nowYear = nowCal.get(Calendar.YEAR)
            val nowMonth = nowCal.get(Calendar.MONTH)
            var thisMonth = 0
            for (ts in timestamps) {
                cal.timeInMillis = ts
                if (cal.get(Calendar.YEAR) == nowYear && cal.get(Calendar.MONTH) == nowMonth) thisMonth++
            }

            val buckets = List(7) { offset -> perDay[nowDay - (6 - offset)] ?: 0 }

            return WatchStats(
                total = histories.size,
                today = perDay[nowDay] ?: 0,
                last7Days = buckets.sum(),
                thisMonth = thisMonth,
                activeDays = perDay.size,
                currentStreak = currentStreak,
                longestStreak = longestStreak,
                busiestWeekday = perWeekday.maxByOrNull { it.value }?.key,
                busiestHour = perHour.maxByOrNull { it.value }?.key,
                dailyBuckets = buckets,
                firstWatchAt = timestamps.minOrNull(),
                lastWatchAt = timestamps.maxOrNull(),
            )
        }
    }
}
