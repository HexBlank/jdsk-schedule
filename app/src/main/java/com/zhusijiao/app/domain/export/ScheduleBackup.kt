package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.CourseAdjustment
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.DayHoliday
import com.zhusijiao.app.domain.DayHolidayDraft
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.DayMakeupDraft
import com.zhusijiao.app.domain.EamsParser
import com.zhusijiao.app.domain.ExtraCourse
import com.zhusijiao.app.domain.ExtraCourses
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.LeaveDraft
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.ParsedSchedule
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.PersonalEventDraft
import com.zhusijiao.app.domain.PersonalEventValidator
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleValidator
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.domain.toAdjustmentList
import com.zhusijiao.app.domain.toDayHolidayList
import com.zhusijiao.app.domain.toDayMakeupList
import com.zhusijiao.app.domain.toLeaveList
import com.zhusijiao.app.domain.toPersonalEventList
import com.zhusijiao.app.domain.toStringMap
import com.zhusijiao.app.domain.toTimeSlotList
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/**
 * 课表 JSON 备份（`zhusijiao-schedule` v2）：导出一份课表的全部内容，能原样导回来。
 *
 * 顶层保留 v1 的 `name / semesterStart / totalWeeks / timeSlots / courses`，旧版本 App
 * （`EamsParser.normalizeJsonPackage`）读到 v2 文件照样能导入课程；v2 只是在旁边加字段：
 * 调课、停课、补课、课表配色，以及只在本机的自己加的课、日程、请假（各由导出开关决定写不写）。
 *
 * 还原一律新建一份本机课表（见 docs/DECISIONS.md D26）：现有的「覆盖」会清空调课，
 * 订阅来的课表覆盖后再同步还会冲突。还原前每一类数据都按现有校验规则过一遍，
 * 不合法的、超出（用户改小后的）学期范围的直接跳过，而不是整份拒绝。
 */
object ScheduleBackup {

    const val FORMAT = "zhusijiao-schedule"
    const val VERSION = 2

    data class Backup(
        val version: Int,
        val name: String,
        val school: String,
        val semesterStart: String,
        val totalWeeks: Int,
        val timeSlots: List<TimeSlot>,
        val courses: List<Course>,
        val adjustments: List<CourseAdjustment>,
        val holidays: List<DayHoliday>,
        val makeups: List<DayMakeup>,
        val courseColors: Map<String, String>,
        val extras: List<ExtraCourse>,
        val events: List<PersonalEvent>,
        val leaves: List<Leave>
    ) {
        /** 课程以外还有没有要还原的东西；没有时导入页不必多说一句。 */
        val hasExtraData: Boolean
            get() = adjustments.isNotEmpty() || holidays.isNotEmpty() || makeups.isNotEmpty() ||
                courseColors.isNotEmpty() || extras.isNotEmpty() || events.isNotEmpty() || leaves.isNotEmpty()

        /** 给导入页的课表包：课程保留原 id（调课记录按 id 指向课程），不再走教务的合并去重。 */
        fun toParsedSchedule(): ParsedSchedule = ParsedSchedule(
            name = name,
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            timeSlots = timeSlots,
            courses = courses,
            school = school
        )
    }

    // ===== 导出 =====

    /**
     * [stored] 是课表本身（未并入自己加的课，[Schedule.courseColors] 也是课表自己的那份）。
     * 自己加的课、日程、请假按 [options] 的三个开关决定写不写；请假只写与本学期有交集的。
     */
    fun encode(
        stored: Schedule,
        extras: List<ExtraCourse>,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        options: ExportOptions,
        appVersion: String,
        exportedAt: String
    ): JSONObject {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("appVersion", appVersion)
            .put("school", stored.school)
            .put("name", stored.name)
            .put("semesterStart", stored.semesterStart)
            .put("totalWeeks", stored.totalWeeks)
            .put("timeSlots", JSONArray(stored.timeSlots.map { it.toJson() }))
            .put("courses", JSONArray(stored.courses.map { it.toJson() }))
            .put("adjustments", JSONArray(stored.adjustments.map { it.toJson() }))
            .put("holidays", JSONArray(stored.holidays.map { it.toJson() }))
            .put("makeups", JSONArray(stored.makeups.map { it.toJson() }))
            .put("courseColors", JSONObject(stored.courseColors))
        if (options.includeExtras) {
            root.put("extras", JSONArray(extras.map { extra ->
                JSONObject().put("course", extra.course.toJson()).put("color", extra.color ?: JSONObject.NULL)
            }))
        }
        if (options.includeEvents) root.put("events", JSONArray(events.map { it.toJson() }))
        if (options.markLeaves) root.put("leaves", JSONArray(leavesInSemester(stored, leaves).map { it.toJson() }))
        return root
    }

    /** 与学期（第 1 周周一 00:00 到最后一周周日 24:00）有交集的请假；没有开学日期时一条都不算。 */
    fun leavesInSemester(schedule: Schedule, leaves: List<Leave>): List<Leave> {
        if (!DateUtils.hasSemesterStart(schedule.semesterStart)) return emptyList()
        val weeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        val first = DateUtils.datesForWeek(schedule.semesterStart, 1).first().iso
        val last = DateUtils.datesForWeek(schedule.semesterStart, weeks).last().iso
        // "YYYY-MM-DD HH:mm" 定长，字符串比较即时间先后
        return leaves.filter { it.end > "$first 00:00" && it.start <= "$last 23:59" }
    }

    // ===== 解析 =====

    /**
     * 解析备份文件。不是 v2 备份（v1 课表包、教务网页、别的 JSON）时返回 null，交给原来的导入解析；
     * 是 v2 备份但内容坏了时抛 [IllegalArgumentException]，message 即用户可读文案。
     */
    fun decode(content: String?): Backup? {
        val text = content?.trim().orEmpty()
        if (!text.startsWith("{")) return null
        val root = runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull() ?: return null
        if (root.optString("format") != FORMAT || root.optInt("version", 1) < VERSION) return null

        val courses = root.optJSONArray("courses").objects().map(Course::fromJson).filter { course ->
            course.name.isNotBlank() && course.day in 1..7 && course.startSection >= 1 &&
                course.endSection in course.startSection..ScheduleTime.MAX_SECTION && course.weeks.isNotEmpty()
        }
        require(courses.isNotEmpty()) { "备份里没有有效课程" }
        val maxWeek = courses.flatMap { it.weeks }.maxOrNull() ?: 0
        val slots = root.optJSONArray("timeSlots").toTimeSlotList().take(ScheduleTime.MAX_SECTION)
        val extras = root.optJSONArray("extras").objects().mapNotNull { item ->
            val course = Course.fromJson(item.optJSONObject("course") ?: return@mapNotNull null)
            val color = if (item.isNull("color")) null
            else runCatching { ExtraCourses.normalizeColor(item.optString("color")) }.getOrNull()
            ExtraCourse(course, color)
        }
        return Backup(
            version = root.optInt("version"),
            name = root.optString("name").trim().ifEmpty { "我的课表" },
            school = root.optString("school").trim(),
            semesterStart = root.optString("semesterStart").trim(),
            totalWeeks = root.optInt("totalWeeks").takeIf { it > 0 } ?: maxWeek.takeIf { it > 0 } ?: 20,
            timeSlots = slots.ifEmpty { EamsParser.DEFAULT_TIME_SLOTS },
            courses = courses,
            adjustments = root.optJSONArray("adjustments").toAdjustmentList(),
            holidays = root.optJSONArray("holidays").toDayHolidayList(),
            makeups = root.optJSONArray("makeups").toDayMakeupList(),
            courseColors = root.optJSONObject("courseColors").toStringMap(),
            extras = extras,
            events = root.optJSONArray("events").toPersonalEventList(),
            leaves = root.optJSONArray("leaves").toLeaveList()
        )
    }

    // ===== 还原 =====

    /** 还原结果：留下来的数据，以及因不合法或超出学期范围被跳过的条数（导入后提示一句）。 */
    data class Restored<T>(val items: T, val skipped: Int)

    /**
     * 把调课、停课、补课、课表配色放进刚建好的课表 [base]（课程、学期已按用户在导入页的修改定下来）。
     * 停课与补课逐条走 [ScheduleValidator]，互相冲突的后来者被跳过。
     */
    fun applyArrangements(base: Schedule, backup: Backup): Restored<Schedule> {
        var skipped = 0
        val weeks = 1..base.totalWeeks
        val seenSources = mutableSetOf<Pair<String, Int>>()
        val adjustments = backup.adjustments.filter { adjustment ->
            val ok = adjustment.sourceWeek in weeks && adjustment.targetWeek in weeks &&
                adjustment.targetDay in 1..7 && adjustment.targetStartSection >= 1 &&
                adjustment.targetEndSection in adjustment.targetStartSection..ScheduleTime.MAX_SECTION &&
                adjustment.targetPosition.orEmpty().length <= 80 &&
                seenSources.add(adjustment.courseId to adjustment.sourceWeek)
            if (!ok) skipped++
            ok
        }
        var state = base.copy(adjustments = adjustments, holidays = emptyList(), makeups = emptyList())
        backup.holidays.forEach { holiday ->
            try {
                ScheduleValidator.validateHoliday(state, DayHolidayDraft(holiday.week, holiday.day))
                state = state.copy(holidays = state.holidays + holiday)
            } catch (_: IllegalArgumentException) {
                skipped++
            }
        }
        backup.makeups.forEach { makeup ->
            try {
                ScheduleValidator.validateMakeup(
                    state,
                    DayMakeupDraft(makeup.sourceWeek, makeup.sourceDay, makeup.targetWeek, makeup.targetDay)
                )
                state = state.copy(makeups = state.makeups + makeup)
            } catch (_: IllegalArgumentException) {
                skipped++
            }
        }
        val colors = backup.courseColors.mapNotNull { (name, color) ->
            runCatching { ExtraCourses.normalizeColor(color) }.getOrNull()?.let { name to it }
        }.toMap()
        return Restored(state.copy(courseColors = colors), skipped)
    }

    /** 自己加的课：逐门校验、裁到上限，换上新 id（和原课表的那几门互不牵连）。 */
    fun restoreExtras(
        backup: Backup,
        totalWeeks: Int,
        newId: () -> String = { "${ExtraCourses.ID_PREFIX}${UUID.randomUUID()}" }
    ): Restored<List<ExtraCourse>> {
        val kept = backup.extras.mapNotNull { extra ->
            runCatching {
                val weeks = extra.course.weeks.filter { it in 1..totalWeeks }
                ExtraCourse(ExtraCourses.normalize(extra.course.copy(weeks = weeks), totalWeeks).copy(id = newId()), extra.color)
            }.getOrNull()
        }.take(ExtraCourses.MAX_PER_SCHEDULE)
        return Restored(kept, backup.extras.size - kept.size)
    }

    /** 日程：逐条校验，同一时段已有日程的后来者跳过（与编辑器同一规则），换上新 id。 */
    fun restoreEvents(
        backup: Backup,
        totalWeeks: Int,
        slots: List<TimeSlot>,
        maxCount: Int,
        newId: () -> String = { "event-${UUID.randomUUID()}" }
    ): Restored<List<PersonalEvent>> {
        val kept = mutableListOf<PersonalEvent>()
        backup.events.forEach { event ->
            if (kept.size >= maxCount) return@forEach
            val draft = runCatching {
                val source = PersonalEventDraft.from(event)
                PersonalEventValidator.normalize(source.copy(weeks = source.weeks.filter { it in 1..totalWeeks }), totalWeeks, slots)
            }.getOrNull() ?: return@forEach
            if (ScheduleOccurrences.overlappingEvent(kept, draft) != null) return@forEach
            kept += event.copy(
                id = newId(),
                title = draft.title,
                position = draft.position,
                note = draft.note,
                startTime = draft.startTime,
                endTime = draft.endTime,
                weeks = draft.weeks,
                color = draft.color
            )
        }
        return Restored(kept, backup.events.size - kept.size)
    }

    /**
     * 请假是全局的（不按课表分组）：按 id 去重并进 [existing]，本机已有的那条保留不动，
     * 不合法的、超出条数上限的跳过。返回要新增的那些。
     */
    fun restoreLeaves(backup: Backup, existing: List<Leave>, maxCount: Int): Restored<List<Leave>> {
        val ids = existing.mapTo(mutableSetOf()) { it.id }
        var room = maxCount - existing.size
        var skipped = 0
        val added = mutableListOf<Leave>()
        backup.leaves.forEach { leave ->
            if (leave.id in ids) return@forEach
            val normalized = runCatching { Leaves.normalize(LeaveDraft.from(leave)) }.getOrNull()
            if (normalized == null || room <= 0) {
                skipped++
                return@forEach
            }
            ids += leave.id
            room--
            added += leave.copy(start = normalized.start, end = normalized.end, note = normalized.note)
        }
        return Restored(added, skipped)
    }

    /** 导入页结果区的一句话：「另含调课 3、停课 2……将一并恢复」里的那串条目。 */
    fun summaryParts(backup: Backup): List<Pair<String, Int>> = listOf(
        "调课" to backup.adjustments.size,
        "停课" to backup.holidays.size,
        "补课" to backup.makeups.size,
        "自加课" to backup.extras.size,
        "日程" to backup.events.size,
        "请假" to backup.leaves.size
    ).filter { it.second > 0 }

    private fun JSONArray?.objects(): List<JSONObject> {
        val a = this ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
}
