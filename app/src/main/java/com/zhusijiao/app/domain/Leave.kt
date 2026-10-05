package com.zhusijiao.app.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 请假类别：自己请的假（事假、病假）与学校安排的公假。只影响显示的字样，规则完全一样。 */
enum class LeaveType(val key: String, val label: String) {
    PERSONAL("personal", "请假"),
    OFFICIAL("official", "公假");

    companion object {
        fun of(key: String?): LeaveType = entries.firstOrNull { it.key == key } ?: PERSONAL
    }
}

/**
 * 一次请假：从 [start] 到 [end] 的一段连续时间，精确到分钟，可以跨天。
 *
 * 只存在本机（[com.zhusijiao.app.data.LeaveStore]），**不进课表、不随课表同步**：请假是个人的事，
 * 同一份课表的其他同学不该看到。也不按课表分组——请假说的是「我这段时间不在」，
 * 换一份课表看，这段时间里的课同样不上。
 *
 * 时间存成本机墙上时间 "YYYY-MM-DD HH:mm"，与课程「日期 + 上下课时间」同一套口径，
 * 换时区或夏令时变化都不会让请假和课错位。
 */
data class Leave(
    val id: String,
    val type: LeaveType,
    val start: String,
    val end: String,
    val note: String,
    val createdAt: String,
    val updatedAt: String
) {
    val startAtMillis: Long? get() = Leaves.parseMoment(start)
    val endAtMillis: Long? get() = Leaves.parseMoment(end)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.key)
        .put("start", start)
        .put("end", end)
        .put("note", note)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Leave(
            id = o.optString("id"),
            type = LeaveType.of(o.optString("type")),
            start = o.optString("start"),
            end = o.optString("end"),
            note = o.optString("note"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt")
        )
    }
}

/** 新建或修改请假时提交的可编辑字段。 */
data class LeaveDraft(
    val type: LeaveType,
    val start: String,
    val end: String,
    val note: String = ""
) {
    companion object {
        fun from(leave: Leave) = LeaveDraft(leave.type, leave.start, leave.end, leave.note)
    }
}

/** 请假时段里的一节课。 */
data class LeaveAffectedClass(
    val dateIso: String,
    val week: Int,
    val day: Int,
    val course: Course
)

/**
 * 请假的时间换算与「哪些课算请假」的判定。纯 Kotlin、不依赖 android.*，JVM 单元测试可直接覆盖。
 *
 * 判定口径只有一条：**课的上课时间与请假时段有重叠就算**，哪怕只重叠一分钟——
 * 请假到 08:30、课是 07:50–09:30，这节课同样去不了。两段时间只是首尾相接
 * （请假到 09:40、课 09:40 开始）不算重叠。
 */
object Leaves {

    const val MAX_NOTE = 60

    /** 一次请假最长这么多天，防止手滑选到明年；真有更长的假可以分两条记。 */
    const val MAX_SPAN_DAYS = 120

    /** "YYYY-MM-DD HH:mm" → 本机时区毫秒时间戳；格式非法返回 null。 */
    fun parseMoment(value: String?): Long? {
        val text = value?.trim() ?: return null
        if (text.length != 16 || text[10] != ' ') return null
        return ScheduleTime.atMillis(text.substring(0, 10), text.substring(11))
    }

    /** 毫秒时间戳 → "YYYY-MM-DD HH:mm"（秒以下舍去）。 */
    fun formatMoment(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return moment(DateUtils.formatDate(cal), cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
    }

    fun moment(dateIso: String, minutes: Int): String = "$dateIso ${ScheduleTime.formatTime(minutes)}"

    fun dateOf(moment: String): String = moment.take(10)

    fun minutesOf(moment: String): Int = ScheduleTime.minutesOf(moment.drop(11)) ?: 0

    /**
     * 校验并规范化；不合法时抛 [IllegalArgumentException]，message 即用户可读文案。
     */
    fun normalize(draft: LeaveDraft): LeaveDraft {
        val start = parseMoment(draft.start)
        val end = parseMoment(draft.end)
        require(start != null && end != null) { "请假时间无效" }
        require(end > start) { "结束时间要晚于开始时间" }
        require(end - start <= MAX_SPAN_DAYS * 86_400_000L) { "一次请假最长 $MAX_SPAN_DAYS 天，更长的假可以分成两条" }
        val note = draft.note.trim()
        require(note.length <= MAX_NOTE) { "事由不能超过 $MAX_NOTE 个字符" }
        return draft.copy(start = formatMoment(start), end = formatMoment(end), note = note)
    }

    /** 一段时间（毫秒，[startAtMillis] 到 [endAtMillis]）落在哪条请假里；没有返回 null。 */
    fun leaveCovering(leaves: List<Leave>, startAtMillis: Long, endAtMillis: Long): Leave? =
        leaves.firstOrNull { leave ->
            val from = leave.startAtMillis ?: return@firstOrNull false
            val to = leave.endAtMillis ?: return@firstOrNull false
            startAtMillis < to && from < endAtMillis
        }

    /** [dateIso] 那天 [startTime]–[endTime] 的一节课落在哪条请假里；作息缺失或没有请假返回 null。 */
    fun leaveFor(leaves: List<Leave>, dateIso: String?, startTime: String?, endTime: String?): Leave? {
        if (leaves.isEmpty()) return null
        val start = ScheduleTime.atMillis(dateIso, startTime) ?: return null
        val end = ScheduleTime.atMillis(dateIso, endTime) ?: return null
        return leaveCovering(leaves, start, maxOf(end, start + 60_000L))
    }

    /**
     * 第 [week] 周周 [day]（日期 [dateIso]）的这节课是否在请假时段内。
     * 课表没有开学日期时日期是推算不出来的，一律不算请假。
     */
    fun leaveFor(leaves: List<Leave>, schedule: Schedule, dateIso: String?, course: Course): Leave? {
        if (leaves.isEmpty() || !DateUtils.hasSemesterStart(schedule.semesterStart)) return null
        val slots = ScheduleTime.slotsOf(schedule.timeSlots)
        return leaveFor(
            leaves,
            dateIso,
            slots.find { it.number == course.startSection }?.startTime,
            slots.find { it.number == course.endSection }?.endTime
        )
    }

    /**
     * 一段请假时间里实际会上的课（调课、停课、补课都已算进去），按时间顺序。
     * 编辑请假时用它预览「这次请假涉及哪几节课」。
     */
    fun affectedClasses(schedule: Schedule, start: String, end: String): List<LeaveAffectedClass> {
        if (!DateUtils.hasSemesterStart(schedule.semesterStart)) return emptyList()
        val from = parseMoment(start) ?: return emptyList()
        val to = parseMoment(end) ?: return emptyList()
        if (to <= from) return emptyList()
        val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        val slots = ScheduleTime.slotsOf(schedule.timeSlots)
        val probe = listOf(Leave("probe", LeaveType.PERSONAL, start, end, "", "", ""))
        val result = mutableListOf<LeaveAffectedClass>()
        val cursor = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val lastDate = dateOf(formatMoment(to))
        for (step in 0..MAX_SPAN_DAYS + 1) {
            val dateIso = DateUtils.formatDate(cursor)
            if (dateIso > lastDate) break
            val week = DateUtils.teachingWeekAt(schedule.semesterStart, cursor.timeInMillis)
            if (week in 1..totalWeeks) {
                val day = DateUtils.dayOfWeek(cursor)
                ScheduleOccurrences.coursesOn(schedule, week, day)
                    .sortedBy { it.startSection }
                    .forEach { course ->
                        val hit = leaveFor(
                            probe,
                            dateIso,
                            slots.find { it.number == course.startSection }?.startTime,
                            slots.find { it.number == course.endSection }?.endTime
                        )
                        if (hit != null) result += LeaveAffectedClass(dateIso, week, day, course)
                    }
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    /**
     * 选请假日期时的候选日（YYYY-MM-DD，升序）：整个学期，再往今天前后放宽一点
     * （开学前、放假后偶尔也要记一笔），并保证 [include]（正在编辑的起止日）一定在里面。
     */
    fun candidateDates(
        semesterStart: String?,
        totalWeeks: Int,
        nowMillis: Long,
        include: List<String> = emptyList()
    ): List<String> {
        val today = DateUtils.formatDate(Calendar.getInstance().apply { timeInMillis = nowMillis })
        val anchors = mutableListOf(shiftDate(today, -7), shiftDate(today, 60))
        if (DateUtils.hasSemesterStart(semesterStart)) {
            val weeks = if (totalWeeks > 0) totalWeeks else 20
            DateUtils.datesForWeek(semesterStart, 1).firstOrNull()?.let { anchors += it.iso }
            DateUtils.datesForWeek(semesterStart, weeks).lastOrNull()?.let { anchors += it.iso }
        }
        anchors += include.filter { ScheduleTime.atMillis(it, "12:00") != null }
        val first = anchors.min()
        val last = anchors.max()
        val result = mutableListOf<String>()
        var cursor = first
        while (cursor <= last && result.size < MAX_CANDIDATE_DAYS) {
            result += cursor
            cursor = shiftDate(cursor, 1)
        }
        return result
    }

    private const val MAX_CANDIDATE_DAYS = 500

    private fun shiftDate(dateIso: String, days: Int): String {
        val millis = ScheduleTime.atMillis(dateIso, "12:00") ?: return dateIso
        return DateUtils.formatDate(
            Calendar.getInstance().apply {
                timeInMillis = millis
                add(Calendar.DAY_OF_YEAR, days)
            }
        )
    }

    private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun dayName(day: Int): String = dayNames.getOrElse(day - 1) { "" }

    /** 时刻的显示文案：「10月8日 周四 08:00」。 */
    fun displayMoment(moment: String): String {
        val millis = parseMoment(moment) ?: return moment
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return "${displayDate(cal)} ${moment.drop(11)}"
    }

    fun displayDate(cal: Calendar): String =
        "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 ${dayName(DateUtils.dayOfWeek(cal))}"

    /**
     * 起止时间的紧凑文案：同一天写「10月8日 周四 08:00–09:35」，
     * 跨天写「10月8日 周四 08:00 – 10月9日 周五 17:30」。
     */
    fun displayRange(start: String, end: String): String {
        if (dateOf(start) == dateOf(end)) return "${displayMoment(start)}–${end.drop(11)}"
        return "${displayMoment(start)} – ${displayMoment(end)}"
    }
}

fun JSONArray?.toLeaveList(): List<Leave> {
    val a = this ?: return emptyList()
    return (0 until a.length()).mapNotNull { index ->
        a.optJSONObject(index)?.let(Leave::fromJson)
    }.filter { leave ->
        val start = leave.startAtMillis
        val end = leave.endAtMillis
        leave.id.isNotBlank() && start != null && end != null && end > start
    }
}
