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

    private fun addDays(date: Calendar, amount: Int): Calendar =
        (date.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, amount) }

    fun formatDate(date: Calendar): String {
        val m = (date.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = date.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "${date.get(Calendar.YEAR)}-$m-$d"
    }

    /** 依据开学日期与总周数推算当前教学周（1..totalWeeks）。 */
    fun currentWeek(semesterStart: String?, totalWeeks: Int): Int {
        val start = parseLocalDate(semesterStart)
        val diffDays = Math.floor((System.currentTimeMillis() - start.timeInMillis) / 86400000.0).toLong()
        val limit = if (totalWeeks > 0) totalWeeks else 20
        return Math.max(1, Math.min(limit, Math.floor(diffDays / 7.0).toInt() + 1))
    }

    /** 某教学周（week 从 1 开始）的周一至周日日期信息。 */
    fun datesForWeek(semesterStart: String?, week: Int): List<DayInfo> {
        val start = addDays(parseLocalDate(semesterStart), (week - 1) * 7)
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
