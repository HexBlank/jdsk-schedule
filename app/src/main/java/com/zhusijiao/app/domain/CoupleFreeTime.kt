package com.zhusijiao.app.domain

/**
 * 情侣课表的「都有空」：两个人都没课（我这边还要没日程）的时段。
 *
 * - 按实际安排算：调课、停课、补课之后的结果（调用方用 [CoupleDay] 取占用），停课的课不算占用；
 * - 只看学校作息的第一节开始到最后一节结束，至少 [MIN_MINUTES] 分钟才算——课间十来分钟不值得标；
 * - 文案：中间的空闲写「都有空 2小时30分」；贴着一天开头写「09:50 前都有空」，贴着结尾写
 *   「20:55 后都有空」（晚上并不是最后一节下课就结束，结尾段的时长没有意义）。
 *
 * 纯函数、不依赖 Android。
 */
object CoupleFreeTime {

    const val MIN_MINUTES = 45

    /** 一段占用时间（当天第几分钟，左闭右开）。 */
    data class Span(val start: Int, val end: Int)

    /** 一段都有空的时间（当天第几分钟，左闭右开）。 */
    data class Gap(val start: Int, val end: Int, val atDayStart: Boolean, val atDayEnd: Boolean) {
        val minutes: Int get() = end - start
    }

    /**
     * [busy] 是两个人所有占用时段（可重叠、可乱序），[dayStart]–[dayEnd] 是作息范围。
     * 一整天都没有占用时返回覆盖全天的一段。
     */
    fun gaps(
        busy: List<Span>,
        dayStart: Int,
        dayEnd: Int,
        minMinutes: Int = MIN_MINUTES
    ): List<Gap> {
        if (dayEnd <= dayStart) return emptyList()
        val clipped = busy
            .map { maxOf(it.start, dayStart) to minOf(it.end, dayEnd) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        val result = mutableListOf<Gap>()
        var cursor = dayStart
        clipped.forEach { (start, end) ->
            if (start - cursor >= minMinutes) result += Gap(cursor, start, cursor == dayStart, false)
            cursor = maxOf(cursor, end)
        }
        if (dayEnd - cursor >= minMinutes) result += Gap(cursor, dayEnd, cursor == dayStart, true)
        return result
    }

    /** 时间轴上的文案。 */
    fun label(gap: Gap): String = when {
        gap.atDayStart && gap.atDayEnd -> "全天都有空"
        gap.atDayStart -> "${ScheduleTime.formatTime(gap.end)} 前都有空"
        gap.atDayEnd -> "${ScheduleTime.formatTime(gap.start)} 后都有空"
        else -> "都有空 ${duration(gap.minutes)}"
    }

    /** 150 → 「2小时30分」，180 → 「3小时」，55 → 「55分钟」。 */
    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        if (hours == 0) return "${rest}分钟"
        return if (rest == 0) "${hours}小时" else "${hours}小时${rest}分"
    }

    /**
     * 周视图用：[busySections] 是两个人被占用的节次，返回连续的都有空节次段（节次从 1 到 [maxSection]）。
     * 周视图的格子放不下时长，只铺底色，所以按节次算、不设最短时长。
     */
    fun freeSectionRuns(busySections: Set<Int>, maxSection: Int = ScheduleTime.MAX_SECTION): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var section = 1
        while (section <= maxSection) {
            if (section in busySections) {
                section += 1
                continue
            }
            val start = section
            while (section <= maxSection && section !in busySections) section += 1
            result += start until section
        }
        return result
    }
}
