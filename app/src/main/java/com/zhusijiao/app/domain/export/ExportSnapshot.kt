package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.ExtraCourse
import com.zhusijiao.app.domain.ExtraCourses
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.LeaveType
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.domain.WeekendDisplay
import com.zhusijiao.app.domain.WeekendDisplayMode

/** 导出时用户在面板上选的东西。 */
data class ExportOptions(
    /** 导出哪几周（可以是单双周、零散的几周）；null 为整个学期。JSON 备份不看这一项，总是完整的。 */
    val weeks: List<Int>? = null,
    val includeExtras: Boolean = true,
    val includeEvents: Boolean = true,
    /** 请假是隐私，导出的文件常常会发给别人，所以默认不标。 */
    val markLeaves: Boolean = false,
    /** ICS 提前多少分钟提醒；null 为不提醒。 */
    val alarmMinutes: Int? = null
)

/**
 * 把一份课表展开成「每周实际发生的课与日程」，Excel 与 ICS 共用。纯 Kotlin，可直接 JVM 单测。
 *
 * 口径与课表页完全一致：
 * - 哪天上什么课走 [ScheduleOccurrences.occurrencesOn]（调课、停课、补课都已算进去）；
 * - 哪节课算请假走 [Leaves.leaveFor]；
 * - 周六日列走 [WeekendDisplay.dayCount]，跟随 App 里的周末显示设置；
 * - 课程颜色走 [ScheduleView.buildCoursePaletteMap]，并且**总是**按并入自己加的课之后的整份课表来配，
 *   不导出自己加的课时其他课的颜色也不会变，和 App 里看到的一样。
 */
object ExportSnapshot {

    /** 与 BlockStyles.DEFAULT_EVENT_COLOR 一致：日程没有自选颜色时的默认色。 */
    const val DEFAULT_EVENT_COLOR = 0xFF2F7D68.toInt()

    /** 与 TimetableView.MIN_SECTIONS 一致：课表至少显示这么多节，课少时也不至于只有半张表。 */
    private const val MIN_SECTIONS = 8

    enum class Kind { COURSE, ADJUSTED, MAKEUP, EVENT }

    /** 某一周某一天的一个块：一次课或一条日程。 */
    data class Item(
        /** 这一次发生的稳定标识：ICS 的 UID 用它，重复导入日历时更新而不是新增。 */
        val uid: String,
        val kind: Kind,
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val startTime: String?,
        val endTime: String?,
        /** 日程自定义了与节次作息不同的时间：格子里要单独写一行时间。 */
        val customTime: Boolean,
        val title: String,
        val position: String,
        val teacher: String,
        val note: String,
        /** 课程：底色；日程：日程色（浅底、描边由排版方按它推出来）。ARGB。 */
        val tone: Int,
        /** 课程块上的字色（ARGB）；日程不用。 */
        val ink: Int,
        /** 这节课落在哪种请假里；没开「标注请假」或不在请假时段时为 null。日程不标。 */
        val leave: LeaveType?
    ) {
        val isEvent: Boolean get() = kind == Kind.EVENT
    }

    data class Day(
        val day: Int,
        /** "YYYY-MM-DD"；课表没填开学日期时为 null。 */
        val dateIso: String?,
        val month: Int,
        val dayOfMonth: Int,
        /** 整天停课。 */
        val suspended: Boolean,
        /** 这天是补课日：补的是哪天的课。 */
        val makeupOf: DayMakeup?,
        /** 这天的课已经补到别的日子去了。 */
        val movedTo: DayMakeup?
    )

    data class Week(val week: Int, val days: List<Day>, val items: List<Item>)

    data class Snapshot(
        val scheduleName: String,
        val hasDates: Boolean,
        /** 表格要画的节次（含作息），与课表页同一套「至少 8 节、用到第几节就到第几节」规则。 */
        val sections: List<TimeSlot>,
        val weeks: List<Week>
    )

    /**
     * [stored] 是课表本身（未并入自己加的课），[extras]、[events]、[leaves] 是本机的私人数据。
     */
    fun build(
        stored: Schedule,
        extras: List<ExtraCourse>,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        weekendMode: WeekendDisplayMode,
        options: ExportOptions
    ): Snapshot {
        val merged = ExtraCourses.merge(stored, extras)
        val schedule = if (options.includeExtras) merged else stored
        val usedEvents = if (options.includeEvents) events else emptyList()
        val usedLeaves = if (options.markLeaves) leaves else emptyList()
        val palette = ScheduleView.buildCoursePaletteMap(
            merged.courses + merged.adjustments.map { it.courseSnapshot },
            merged.courseColors
        )
        val slots = ScheduleTime.slotsOf(schedule.timeSlots)
        val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        val hasDates = DateUtils.hasSemesterStart(schedule.semesterStart)
        val weeks = selectedWeeks(options, totalWeeks).map { week ->
            buildWeek(schedule, week, slots, palette, usedEvents, usedLeaves, weekendMode, hasDates)
        }
        return Snapshot(
            scheduleName = schedule.name,
            hasDates = hasDates,
            sections = visibleSections(schedule, usedEvents, slots),
            weeks = weeks
        )
    }

    /** 实际导出的周次：升序、去重、裁到学期内。 */
    fun selectedWeeks(options: ExportOptions, totalWeeks: Int): List<Int> =
        (options.weeks ?: (1..totalWeeks).toList()).filter { it in 1..totalWeeks }.distinct().sorted()

    private fun buildWeek(
        schedule: Schedule,
        week: Int,
        slots: List<TimeSlot>,
        palette: Map<String, ScheduleView.Palette>,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        weekendMode: WeekendDisplayMode,
        hasDates: Boolean
    ): Week {
        val dayCount = WeekendDisplay.dayCount(schedule, week, weekendMode, events)
        val dates = if (hasDates) DateUtils.datesForWeek(schedule.semesterStart, week) else emptyList()
        val days = (1..dayCount).map { day ->
            val info = dates.getOrNull(day - 1)
            Day(
                day = day,
                dateIso = info?.iso,
                month = info?.month ?: 0,
                dayOfMonth = info?.day ?: 0,
                suspended = schedule.holidays.any { it.week == week && it.day == day },
                makeupOf = schedule.makeups.firstOrNull { it.targetWeek == week && it.targetDay == day },
                movedTo = schedule.makeups.firstOrNull { it.sourceWeek == week && it.sourceDay == day }
            )
        }
        val items = mutableListOf<Item>()
        val usedUids = mutableSetOf<String>()
        fun uniqueUid(base: String): String {
            var uid = base
            var n = 2
            while (!usedUids.add(uid)) uid = "$base-${n++}"
            return uid
        }
        days.forEach { info ->
            val day = info.day
            ScheduleOccurrences.occurrencesOn(schedule, week, day).forEach { occurrence ->
                val course = occurrence.course
                val colors = palette[course.name] ?: ScheduleView.COURSE_PALETTES[0]
                val leave = if (leaves.isEmpty()) null
                else Leaves.leaveFor(leaves, schedule, info.dateIso, course)?.type
                items += Item(
                    uid = uniqueUid("${course.id}-w$week-d$day-s${course.startSection}-${course.endSection}"),
                    kind = when (occurrence.source) {
                        ScheduleOccurrences.Source.REGULAR -> Kind.COURSE
                        ScheduleOccurrences.Source.ADJUSTED -> Kind.ADJUSTED
                        ScheduleOccurrences.Source.MAKEUP -> Kind.MAKEUP
                    },
                    day = day,
                    startSection = course.startSection,
                    endSection = course.endSection,
                    startTime = slots.find { it.number == course.startSection }?.startTime,
                    endTime = slots.find { it.number == course.endSection }?.endTime,
                    customTime = false,
                    title = course.name,
                    position = course.position,
                    teacher = course.teacher,
                    note = "",
                    tone = colors.background,
                    ink = colors.foreground,
                    leave = leave
                )
            }
            events.filter { it.day == day && it.occursIn(week) }.forEach { event ->
                items += Item(
                    uid = uniqueUid("${event.id}-w$week"),
                    kind = Kind.EVENT,
                    day = day,
                    startSection = event.startSection,
                    endSection = event.endSection,
                    startTime = event.startTime ?: slots.find { it.number == event.startSection }?.startTime,
                    endTime = event.endTime ?: slots.find { it.number == event.endSection }?.endTime,
                    customTime = event.blockTimeText(slots) != null,
                    title = event.title,
                    position = event.position,
                    teacher = "",
                    note = event.note,
                    tone = ScheduleView.manualPalette(event.color)?.background ?: DEFAULT_EVENT_COLOR,
                    ink = 0,
                    leave = null
                )
            }
        }
        items.sortWith(compareBy({ it.day }, { it.startSection }, { it.endSection }, { it.isEvent }))
        return Week(week, days, items)
    }

    private fun visibleSections(schedule: Schedule, events: List<PersonalEvent>, slots: List<TimeSlot>): List<TimeSlot> {
        val lastUsed = maxOf(
            schedule.courses.maxOfOrNull { it.endSection } ?: 0,
            schedule.adjustments.maxOfOrNull { it.targetEndSection } ?: 0,
            events.maxOfOrNull { it.endSection } ?: 0
        )
        return slots.take(minOf(slots.size, maxOf(MIN_SECTIONS, lastUsed)))
    }
}
