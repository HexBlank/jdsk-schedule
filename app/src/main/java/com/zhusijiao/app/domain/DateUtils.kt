package com.zhusijiao.app.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 日期与相对时间工具。用 Calendar 实现，避免 java.time 脱糖依赖。 */
object DateUtils {

    private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    private val isoUtcMillis = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val isoUtcSeconds = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    data class DayInfo(val name: String, val day: Int, val month: Int, val iso: String, val today: Boolean)

    /** 解析 "YYYY-MM-DD" 为当天正午的日历；非法则返回当前时间。 */
    private fun parseLocalDate(value: String?): Calendar {
        val cal = Calendar.getInstance()
        val parts = (value ?: "").split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) {
            return cal
        }
        cal.clear()
        cal.set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
        return cal
    }

    /**
     * 开学日期是否可用。[parseLocalDate] 对非法值会退化成「今天」，
     * 这样推算出的日期是错的；界面应先问一句，再决定显示真实日期还是「第 X 周」。
     */
    fun hasSemesterStart(value: String?): Boolean {
        val parts = (value ?: "").split("-").mapNotNull { it.toIntOrNull() }
        return parts.size == 3
    }

    /**
     * 教学周锚点：开学日期所在自然周的周一。
     *
     * 全 App 约定 day 1 = 周一，但用户填的开学日期未必是周一（学校常把报到日写成开学日，
     * 例如 2026-09-12 是周六）。若直接拿它当第 1 天，此后每一天的星期都会整体偏移：
     * 调休日历会把 10 月 10 日（周六）标成周一，用户照着那一格停课就停错了天。
     * 所以日期推算一律先回退到那一周的周一。开学日期本就是周一时，这里是恒等变换。
     */
    private fun weekAnchor(semesterStart: String?): Calendar {
        val date = parseLocalDate(semesterStart)
        // Calendar 里 SUNDAY=1 … SATURDAY=7；+5 取模把周一映射为 0，得到「距本周一几天」
        val backDays = (date.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return if (backDays == 0) date else addDays(date, -backDays)
    }

    /** 开学日期所在周的周一（YYYY-MM-DD）；导入页用它把用户选的日期规整到第 1 周周一。 */
    fun weekStartOf(semesterStart: String?): String = formatDate(weekAnchor(semesterStart))

    /** 这一天是否就是周一。 */
    fun isMonday(value: String?): Boolean =
        hasSemesterStart(value) && parseLocalDate(value).get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY

    private fun addDays(date: Calendar, amount: Int): Calendar =
        (date.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, amount) }

    fun formatDate(date: Calendar): String {
        val m = (date.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = date.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "${date.get(Calendar.YEAR)}-$m-$d"
    }

    /** 依据开学日期与总周数推算当前教学周（1..totalWeeks）。
     *  按「日历天数差」计算，与当天几点打开无关——
     *  此前按「开学日正午」作锚点，跨周日上午仍会显示上一周，已修复。 */
    fun currentWeek(semesterStart: String?, totalWeeks: Int, nowMillis: Long = System.currentTimeMillis()): Int {
        val limit = if (totalWeeks > 0) totalWeeks else 20
        return Math.max(1, Math.min(limit, teachingWeekAt(semesterStart, nowMillis)))
    }

    /**
     * 不夹取的教学周：开学前为 0 或负数，学期结束后大于总周数。
     * [currentWeek] 为了界面总有一周可看会夹到 1..totalWeeks，上课提醒不能用它——
     * 否则开学前一周会按第 1 周提醒、放假后会一直按最后一周提醒。
     */
    fun teachingWeekAt(semesterStart: String?, nowMillis: Long): Int {
        val startMid = midnightOf(weekAnchor(semesterStart))
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val diffDays = Math.round((midnightOf(now).timeInMillis - startMid.timeInMillis) / 86400000.0)
        return Math.floor(diffDays / 7.0).toInt() + 1
    }

    /** 归一到当天 0 点，消除时刻差异；用 round 吸收夏令时带来的 ±1 小时偏移。 */
    private fun midnightOf(cal: Calendar): Calendar =
        (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    /** 某教学周（week 从 1 开始）的周一至周日日期信息；第 1 天恒为真实的周一（见 [weekAnchor]）。 */
    fun datesForWeek(semesterStart: String?, week: Int): List<DayInfo> {
        val start = addDays(weekAnchor(semesterStart), (week - 1) * 7)
        val today = formatDate(Calendar.getInstance())
        return dayNames.mapIndexed { index, name ->
            val date = addDays(start, index)
            val iso = formatDate(date)
            DayInfo(
                name = name,
                day = date.get(Calendar.DAY_OF_MONTH),
                month = date.get(Calendar.MONTH) + 1,
                iso = iso,
                today = iso == today
            )
        }
    }

    /** 相对时间描述，对应 relativeTime()。 */
    fun relativeTime(value: String?): String {
        val t = parseIso(value) ?: return "刚刚更新"
        val diff = System.currentTimeMillis() - t
        if (diff < 0) return "刚刚更新"
        val minutes = Math.floor(diff / 60000.0).toLong()
        if (minutes < 1) return "刚刚更新"
        if (minutes < 60) return "$minutes 分钟前更新"
        val hours = Math.floor(minutes / 60.0).toLong()
        if (hours < 24) return "$hours 小时前更新"
        val days = Math.floor(hours / 24.0).toLong()
        return "$days 天前更新"
    }

    private fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            isoUtcMillis.parse(value)?.time
        } catch (_: Exception) {
            try {
                isoUtcSeconds.parse(value)?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
