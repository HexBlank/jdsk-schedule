package com.zhusijiao.app.domain

/** 调休界面的日期选择与分组，不改变停课、补课的校验规则。 */
object HolidayPlanner {
    fun index(week: Int, day: Int) = (week - 1) * 7 + day - 1

    /** 来源日真正要搬走的课程：停课不抹掉原课程，已有补课目标不作链式搬运。 */
    fun sourceCourses(schedule: Schedule, source: Int): List<Course> {
        if (source !in 0 until schedule.totalWeeks * 7) return emptyList()
        return ScheduleOccurrences.coursesOn(schedule.copy(
            holidays = schedule.holidays.filterNot { index(it.week, it.day) == source },
            makeups = schedule.makeups.filterNot { index(it.targetWeek, it.targetDay) == source }
        ), source / 7 + 1, source % 7 + 1)
    }

    fun makeupTargetDays(schedule: Schedule): Set<Int> {
        val occupied = schedule.holidays.map { index(it.week, it.day) }.toSet() +
            schedule.makeups.map { index(it.targetWeek, it.targetDay) }
        return (0 until schedule.totalWeeks * 7).filterNot { it in occupied }.toSet()
    }

    /** 补课无需先设停课；仍按原校验排除同日、已补走的来源与无效目标。 */
    fun makeupSourceDays(schedule: Schedule, target: Int): Set<Int> {
        if (target !in makeupTargetDays(schedule)) return emptySet()
        return (0 until schedule.totalWeeks * 7).filter { source ->
            val draft = DayMakeupDraft(source / 7 + 1, source % 7 + 1, target / 7 + 1, target % 7 + 1)
            runCatching { ScheduleValidator.validateMakeup(schedule, draft) }.isSuccess &&
                sourceCourses(schedule, source).isNotEmpty()
        }.toSet()
    }

    fun range(schedule: Schedule, first: Int, last: Int): List<DayHolidayDraft> {
        if (first !in 0 until schedule.totalWeeks * 7 || last !in 0 until schedule.totalWeeks * 7) return emptyList()
        val occupied = schedule.holidays.map { index(it.week, it.day) }.toSet() +
            schedule.makeups.map { index(it.targetWeek, it.targetDay) }
        return (minOf(first, last)..maxOf(first, last)).filterNot { it in occupied }
            .map { DayHolidayDraft(it / 7 + 1, it % 7 + 1) }
    }

    fun groups(holidays: List<DayHoliday>): List<List<DayHoliday>> {
        val groups = mutableListOf<MutableList<DayHoliday>>()
        holidays.sortedBy { index(it.week, it.day) }.forEach { day ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (previous == null || index(day.week, day.day) != index(previous.week, previous.day) + 1) {
                groups += mutableListOf(day)
            } else groups.last().add(day)
        }
        return groups
    }

    fun makeupSources(schedule: Schedule, target: Int): List<DayHoliday> {
        if (target !in 0 until schedule.totalWeeks * 7) return emptyList()
        val week = target / 7 + 1
        val day = target % 7 + 1
        if (ScheduleOccurrences.coursesOn(schedule, week, day).isNotEmpty()) return emptyList()
        return schedule.holidays.filter { source ->
            val draft = DayMakeupDraft(source.week, source.day, week, day)
            runCatching { ScheduleValidator.validateMakeup(schedule, draft) }.isSuccess &&
                ScheduleOccurrences.coursesOn(
                    schedule.copy(holidays = schedule.holidays.filterNot { it.id == source.id }),
                    source.week, source.day
                ).isNotEmpty()
        }.sortedBy { index(it.week, it.day) }
    }
}

/** 修改起止日期时自动保持有效区间，不让用户修复颠倒的日期。 */
data class HolidayDateRange(val start: Int, val end: Int) {
    init { require(start >= 0 && end >= start) }
    fun startingAt(day: Int) = HolidayDateRange(day, maxOf(day, end))
    fun endingAt(day: Int) = HolidayDateRange(minOf(start, day), day)
}

/** 一次删除或撤销只修改指定记录，保留这期间的其他编辑；整组校验成功才写入。 */
data class CalendarEdit(
    val removeHolidayIds: Set<String> = emptySet(),
    val removeMakeupIds: Set<String> = emptySet(),
    val restoreHolidays: List<DayHoliday> = emptyList(),
    val restoreMakeups: List<DayMakeup> = emptyList()
) {
    fun applyTo(schedule: Schedule): Schedule {
        require(schedule.holidays.map { it.id }.containsAll(removeHolidayIds)) { "停课记录已变化" }
        require(schedule.makeups.map { it.id }.containsAll(removeMakeupIds)) { "补课记录已变化" }
        var state = schedule.copy(
            holidays = schedule.holidays.filterNot { it.id in removeHolidayIds },
            makeups = schedule.makeups.filterNot { it.id in removeMakeupIds }
        )
        restoreHolidays.forEach { holiday ->
            require(state.holidays.none { it.id == holiday.id }) { "停课记录已存在" }
            ScheduleValidator.validateHoliday(state, DayHolidayDraft(holiday.week, holiday.day))
            state = state.copy(holidays = state.holidays + holiday)
        }
        restoreMakeups.forEach { makeup ->
            require(state.makeups.none { it.id == makeup.id }) { "补课记录已存在" }
            ScheduleValidator.validateMakeup(state, DayMakeupDraft(
                makeup.sourceWeek, makeup.sourceDay, makeup.targetWeek, makeup.targetDay
            ))
            state = state.copy(makeups = state.makeups + makeup)
        }
        return state
    }
}
