package com.zhusijiao.app.domain

import java.util.Calendar

/**
 * 情侣日视图的一天：某个人在某个真实日期实际要上的课和（仅本人的）日程，按真实时间排。
 *
 * 两个人各按自己课表的开学日期换算「这天是第几周星期几」，不假设两边一致——
 * 同一所学校也可能有人把开学日期设错了一周。课程口径与课表页一致（[ScheduleOccurrences]）：
 * 调出的不画、调入和补课搬来的照常画；停课日的课照样画出来但标成停课，不算占用时间。
 *
 * 纯函数、不依赖 Android。
 */
object CoupleDay {

    enum class Kind { COURSE, EVENT }

    data class Item(
        val kind: Kind,
        val title: String,
        val position: String,
        /** 当天第几分钟，左闭右开。 */
        val startMinutes: Int,
        val endMinutes: Int,
        val startSection: Int,
        val endSection: Int,
        /** 停课日被停掉的课：变淡、标「停课」，不算占用时间。 */
        val suspended: Boolean = false,
        val course: Course? = null,
        val event: PersonalEvent? = null
    ) {
        val busy: Boolean get() = !suspended
    }

    data class Plan(val week: Int, val day: Int, val items: List<Item>, val holiday: Boolean)

    /** 「YYYY-MM-DD」是星期几（1 = 周一 … 7 = 周日）；非法返回 null。 */
    fun dayOfWeek(dateIso: String): Int? {
        val millis = ScheduleTime.atMillis(dateIso, "12:00") ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    }

    /** 这份课表里 [dateIso] 是第几周（不夹取，开学前为 0 或负数）；日期非法返回 null。 */
    fun weekOf(schedule: Schedule, dateIso: String): Int? {
        val millis = ScheduleTime.atMillis(dateIso, "12:00") ?: return null
        return DateUtils.teachingWeekAt(schedule.semesterStart, millis)
    }

    fun plan(schedule: Schedule, events: List<PersonalEvent>, dateIso: String): Plan? {
        val week = weekOf(schedule, dateIso) ?: return null
        val day = dayOfWeek(dateIso) ?: return null
        val slots = ScheduleTime.slotsOf(schedule.timeSlots)
        val items = mutableListOf<Item>()

        ScheduleOccurrences.coursesOn(schedule, week, day).forEach { course ->
            courseItem(course, slots, suspended = false)?.let { items += it }
        }
        val holiday = schedule.holidays.any { it.week == week && it.day == day }
        if (holiday) {
            // 停课日本来要上的课（与 ScheduleOccurrences 同一口径，只是这天不上）
            val movedOut = schedule.adjustments.map { it.courseId to it.sourceWeek }.toSet()
            schedule.courses
                .filter { it.day == day && it.weeks.contains(week) && (it.id to week) !in movedOut }
                .forEach { course -> courseItem(course, slots, suspended = true)?.let { items += it } }
        }
        events.filter { it.day == day && it.occursIn(week) }.forEach { event ->
            val start = ScheduleTime.minutesOf(event.startTime)
                ?: ScheduleTime.minutesOf(slots.find { it.number == event.startSection }?.startTime)
            val end = ScheduleTime.minutesOf(event.endTime)
                ?: ScheduleTime.minutesOf(slots.find { it.number == event.endSection }?.endTime)
            if (start != null && end != null && end > start) {
                items += Item(
                    kind = Kind.EVENT,
                    title = event.title,
                    position = event.position,
                    startMinutes = start,
                    endMinutes = end,
                    startSection = event.startSection,
                    endSection = event.endSection,
                    event = event
                )
            }
        }
        return Plan(week, day, items.sortedWith(compareBy({ it.startMinutes }, { -it.endMinutes })), holiday)
    }

    private fun courseItem(course: Course, slots: List<TimeSlot>, suspended: Boolean): Item? {
        val start = ScheduleTime.minutesOf(slots.find { it.number == course.startSection }?.startTime) ?: return null
        val end = ScheduleTime.minutesOf(slots.find { it.number == course.endSection }?.endTime) ?: return null
        if (end <= start) return null
        return Item(
            kind = Kind.COURSE,
            title = course.name,
            position = course.position,
            startMinutes = start,
            endMinutes = end,
            startSection = course.startSection,
            endSection = course.endSection,
            suspended = suspended,
            course = course
        )
    }

    /** 作息的第一节开始与最后一节结束（分钟）；作息为空时退回默认作息。 */
    fun dayRange(schedule: Schedule?): Pair<Int, Int> {
        val slots = ScheduleTime.slotsOf(schedule?.timeSlots)
        val start = slots.mapNotNull { ScheduleTime.minutesOf(it.startTime) }.minOrNull() ?: 8 * 60
        val end = slots.mapNotNull { ScheduleTime.minutesOf(it.endTime) }.maxOrNull() ?: 22 * 60
        return start to end
    }

    /**
     * 名字下面那一行状态。今天看实时：上课中 / 空闲并预告下一节 / 都上完了；
     * 其他日子看概况：几门课、从几点到几点。[nowMinutes] 只在 [isToday] 时有意义。
     */
    fun statusText(plan: Plan?, isToday: Boolean, nowMinutes: Int): String {
        val dayWord = if (isToday) "今天" else "这天"
        if (plan == null) return "还没有课表"
        val courses = plan.items.filter { it.kind == Kind.COURSE && !it.suspended }
        if (courses.isEmpty()) return if (plan.holiday) "${dayWord}停课" else "${dayWord}没课"
        if (isToday) {
            courses.firstOrNull { it.startMinutes <= nowMinutes && nowMinutes < it.endMinutes }?.let {
                return "上课中 · ${ScheduleTime.formatTime(it.endMinutes)} 下课"
            }
            courses.filter { it.startMinutes > nowMinutes }.minByOrNull { it.startMinutes }?.let {
                return "空闲 · ${ScheduleTime.formatTime(it.startMinutes)} ${it.title}"
            }
            return "今天的课都上完了"
        }
        val count = courses.map { it.title }.distinct().size
        val first = courses.minOf { it.startMinutes }
        val last = courses.maxOf { it.endMinutes }
        return "$count 门课 · ${ScheduleTime.formatTime(first)}–${ScheduleTime.formatTime(last)}"
    }

    /** 两个人这一天的「都有空」：我的课和日程、TA 的课都算占用，停课的不算。 */
    fun freeGaps(mine: Plan?, partner: Plan?, dayStart: Int, dayEnd: Int): List<CoupleFreeTime.Gap> {
        val busy = listOfNotNull(mine, partner)
            .flatMap { it.items }
            .filter { it.busy }
            .map { CoupleFreeTime.Span(it.startMinutes, it.endMinutes) }
        return CoupleFreeTime.gaps(busy, dayStart, dayEnd)
    }
}
