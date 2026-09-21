package com.zhusijiao.app.domain

/**
 * 作息与时间换算。纯 Kotlin、不依赖 android.*，可直接用 JVM 单元测试覆盖。
 *
 * 课表网格的行是「节次」而不是钟点：行等高，但节次并不等时长——午休（12:15→14:00）
 * 105 分钟、晚饭（17:40→19:15）95 分钟在网格里高度为 0，全天 840 分钟有 36% 不落在
 * 任何节次内。所以自定义时间只能用来算「占哪几行」和显示文案，**不能按真实时间比例
 * 定位**：那会让 18:30 与 19:15 在网格上无法区分，12:30–13:30 的安排更会被压成零高度
 * 根本画不出来。见 docs/DECISIONS.md D18。
 */
object ScheduleTime {

    /** 课表最多 12 节，与 ScheduleValidator / backend validation.js 一致。 */
    const val MAX_SECTION = 12

    /** 用课表自带作息覆盖默认作息（缺失的节次回退到默认值），结果恒为 12 节且按 number 升序。 */
    fun slotsOf(timeSlots: List<TimeSlot>?): List<TimeSlot> {
        val provided = (timeSlots ?: emptyList()).associateBy { it.number }
        return EamsParser.DEFAULT_TIME_SLOTS.map { fallback ->
            val slot = provided[fallback.number]
            TimeSlot(
                number = fallback.number,
                startTime = slot?.startTime?.takeIf { it.isNotBlank() } ?: fallback.startTime,
                endTime = slot?.endTime?.takeIf { it.isNotBlank() } ?: fallback.endTime
            )
        }
    }

    /** "18:30" → 1110（当天第几分钟）；格式非法返回 null。手工解析，不用正则。 */
    fun minutesOf(value: String?): Int? {
        val text = value?.trim() ?: return null
        if (text.length != 5 || text[2] != ':') return null
        val hour = text.substring(0, 2).toIntOrNull() ?: return null
        val minute = text.substring(3, 5).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun isValidTime(value: String?): Boolean = minutesOf(value) != null

    fun formatTime(minutes: Int): String {
        val clamped = minutes.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(clamped / 60, clamped % 60)
    }

    /** 起止节的上下课时间，如「19:15–20:55」；作息缺失时返回 null。 */
    fun rangeText(slots: List<TimeSlot>, startSection: Int, endSection: Int): String? {
        val start = slots.find { it.number == startSection }?.startTime?.takeIf { it.isNotBlank() } ?: return null
        val end = slots.find { it.number == endSection }?.endTime?.takeIf { it.isNotBlank() } ?: return null
        return "$start–$end"
    }

    /**
     * 自定义时间在网格里应当占据的节次范围（渲染坐标）。
     *
     * - 起始节取第一个「结束时间 > 开始时间」的节次，全都不满足（晚于最后一节）时取最后一节；
     * - 结束节取最后一个「开始时间 < 结束时间」的节次，全都不满足（早于第一节）时取第一节；
     * - 若算出结束节 < 起始节，说明整段时间都落在两节之间的空档里（如 12:30–13:30 在午休），
     *   塌缩成一格并取**空档前**那一节：午休的安排画在「上午最后一节」比画在「下午第一节」
     *   更贴合发生顺序，也避开了下午第一节通常有课。
     */
    fun sectionSpanFor(startTime: String?, endTime: String?, slots: List<TimeSlot>): IntRange {
        val usable = slots.filter { minutesOf(it.startTime) != null && minutesOf(it.endTime) != null }
        val first = usable.firstOrNull()?.number ?: 1
        val start = minutesOf(startTime) ?: return first..first
        val end = minutesOf(endTime) ?: return first..first
        if (usable.isEmpty()) return first..first
        val last = usable.last().number
        val startSection = usable.firstOrNull { minutesOf(it.endTime)!! > start }?.number ?: last
        val endSection = usable.lastOrNull { minutesOf(it.startTime)!! < end }?.number ?: first
        if (endSection < startSection) {
            val collapsed = (startSection - 1).coerceAtLeast(first)
            return collapsed..collapsed
        }
        return startSection..endSection
    }
}
