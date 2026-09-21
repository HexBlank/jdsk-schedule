package com.zhusijiao.app.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 自定义日程：社团活动、兼职、实验室这类学生自己的安排。
 *
 * 只存在本机（[com.zhusijiao.app.data.PersonalEventStore]），**不进 [Schedule.courses]、
 * 不随课表同步给加入的同学**；发布者和订阅者都能添加。理由见 docs/DECISIONS.md D18。
 *
 * [startSection]/[endSection] 是渲染坐标，永远有值，决定画在哪几行、点哪里命中；
 * [startTime]/[endTime] 可空，只影响显示文案，null 表示跟随该节次的作息
 * （重新导入课表、作息变了时自动跟随；填了自定义时间的则保持原值）。
 */
data class PersonalEvent(
    val id: String,
    val title: String,
    val position: String,
    val note: String,
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: String?,
    val endTime: String?,
    val weeks: List<Int>,
    val color: String?,
    val createdAt: String,
    val updatedAt: String
) {
    val span: Int get() = endSection - startSection + 1

    fun occursIn(week: Int): Boolean = weeks.contains(week)

    /**
     * 课表块上单独占一行显示的时间文案。
     * 未自定义时间、或自定义时间与占位节次的作息完全一致时返回 null——那一行没有新信息，
     * 格子的行很贵，不为冗余花。
     */
    fun blockTimeText(slots: List<TimeSlot>): String? {
        val start = startTime ?: return null
        val end = endTime ?: return null
        val slotStart = slots.find { it.number == startSection }?.startTime
        val slotEnd = slots.find { it.number == endSection }?.endTime
        if (start == slotStart && end == slotEnd) return null
        return "$start–$end"
    }

    /** 详情抽屉主行的时间：自定义优先，否则回退到占位节次的作息。 */
    fun timeText(slots: List<TimeSlot>): String? {
        if (startTime != null && endTime != null) return "$startTime–$endTime"
        return ScheduleTime.rangeText(slots, startSection, endSection)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("position", position)
        .put("note", note)
        .put("day", day)
        .put("startSection", startSection)
        .put("endSection", endSection)
        .put("startTime", startTime)
        .put("endTime", endTime)
        .put("weeks", JSONArray(weeks))
        .put("color", color)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): PersonalEvent {
            val startSection = o.optInt("startSection", 1)
            return PersonalEvent(
                id = o.optString("id"),
                title = o.optString("title"),
                position = o.optString("position"),
                note = o.optString("note"),
                day = o.optInt("day", 1),
                startSection = startSection,
                endSection = o.optInt("endSection", startSection),
                startTime = if (o.isNull("startTime")) null else o.optString("startTime").takeIf { it.isNotBlank() },
                endTime = if (o.isNull("endTime")) null else o.optString("endTime").takeIf { it.isNotBlank() },
                weeks = o.optJSONArray("weeks").toIntList(),
                color = if (o.isNull("color")) null else o.optString("color").takeIf { it.isNotBlank() },
                createdAt = o.optString("createdAt"),
                updatedAt = o.optString("updatedAt")
            )
        }
    }
}

/** 新建或修改日程时提交的可编辑字段。 */
data class PersonalEventDraft(
    val title: String,
    val position: String = "",
    val note: String = "",
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: String? = null,
    val endTime: String? = null,
    val weeks: List<Int>,
    val color: String? = null
) {
    companion object {
        fun from(event: PersonalEvent) = PersonalEventDraft(
            title = event.title,
            position = event.position,
            note = event.note,
            day = event.day,
            startSection = event.startSection,
            endSection = event.endSection,
            startTime = event.startTime,
            endTime = event.endTime,
            weeks = event.weeks,
            color = event.color
        )
    }
}

/**
 * 日程校验。
 *
 * 刻意**不并入 [ScheduleValidator]**：后者与 `backend/src/validation.js` 是一对必须逐条
 * 保持一致的契约，而日程没有服务端对应物，混进去会让那条约束失去意义。
 */
object PersonalEventValidator {

    const val MAX_TITLE = 40
    const val MAX_POSITION = 40
    const val MAX_NOTE = 200

    private val HEX_COLOR = Regex("^#[0-9A-F]{6}$")

    /**
     * 校验并规范化；不合法时抛 [IllegalArgumentException]，message 即用户可读文案。
     *
     * 占位节次**以调用方给的为准**：节次是用户在网格上亲手点的，块画在哪一格必须与他
     * 点的一致。自定义时间只是显示文案，不反过来改占位——早期版本用
     * [ScheduleTime.sectionSpanFor] 覆盖节次，会出现「我选了第 10 节、块却自己跑了」。
     * 那个换算现在只用来在编辑器里给一条可点的建议。
     */
    fun normalize(
        draft: PersonalEventDraft,
        totalWeeks: Int,
        slots: List<TimeSlot>
    ): PersonalEventDraft {
        val title = draft.title.trim()
        require(title.isNotEmpty()) { "日程名称不能为空" }
        require(title.length <= MAX_TITLE) { "日程名称不能超过 $MAX_TITLE 个字符" }
        val position = draft.position.trim()
        require(position.length <= MAX_POSITION) { "地点不能超过 $MAX_POSITION 个字符" }
        val note = draft.note.trim()
        require(note.length <= MAX_NOTE) { "备注不能超过 $MAX_NOTE 个字符" }
        require(draft.day in 1..7) { "日程星期无效" }

        val hasStart = !draft.startTime.isNullOrBlank()
        val hasEnd = !draft.endTime.isNullOrBlank()
        require(hasStart == hasEnd) { "开始时间和结束时间要一起填写" }
        var startTime: String? = null
        var endTime: String? = null
        if (hasStart) {
            val start = ScheduleTime.minutesOf(draft.startTime)
            val end = ScheduleTime.minutesOf(draft.endTime)
            require(start != null && end != null) { "时间格式必须为 HH:mm" }
            // 跨夜（23:00–01:00）在按节次排布的网格里画不出来，直接拒绝而不是悄悄截断。
            require(end > start) { "结束时间要晚于开始时间" }
            startTime = ScheduleTime.formatTime(start)
            endTime = ScheduleTime.formatTime(end)
        }
        require(draft.startSection in 1..ScheduleTime.MAX_SECTION) { "日程节次无效" }
        require(draft.endSection in draft.startSection..ScheduleTime.MAX_SECTION) { "日程节次无效" }

        val weeks = draft.weeks.filter { it > 0 }.distinct().sorted()
        require(weeks.isNotEmpty()) { "请至少选择一个周次" }
        require(weeks.all { it in 1..totalWeeks }) { "日程周次超出学期范围" }

        val color = draft.color?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        require(color == null || HEX_COLOR.matches(color)) { "颜色值无效" }

        return draft.copy(
            title = title,
            position = position,
            note = note,
            startTime = startTime,
            endTime = endTime,
            weeks = weeks,
            color = color
        )
    }
}

fun JSONArray?.toPersonalEventList(): List<PersonalEvent> {
    val a = this ?: return emptyList()
    return (0 until a.length()).mapNotNull { index ->
        a.optJSONObject(index)?.let(PersonalEvent::fromJson)
    }.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.day in 1..7 && it.weeks.isNotEmpty() }
}
