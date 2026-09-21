package com.zhusijiao.app.domain

/** 周六日列的显示方式。 */
enum class WeekendDisplayMode {
    /** 仅当前周存在周末课程、调课或调休安排时显示（默认，对应「当周周六日有课时才显示」开关开启）。 */
    AUTO,

    /** 所有周始终显示（对应开关关闭）。 */
    ALWAYS
}

/** 纯函数形式的周末显示规则，便于独立回归测试。 */
object WeekendDisplay {
    const val WEEKDAY_COUNT = 5
    const val FULL_WEEK_COUNT = 7

    fun dayCount(
        schedule: Schedule,
        week: Int,
        mode: WeekendDisplayMode,
        events: List<PersonalEvent> = emptyList()
    ): Int = when (mode) {
        WeekendDisplayMode.ALWAYS -> FULL_WEEK_COUNT
        WeekendDisplayMode.AUTO ->
            if (hasWeekendArrangement(schedule, week, events)) FULL_WEEK_COUNT else WEEKDAY_COUNT
    }

    /** 自定义日程同样算「周末有安排」，否则周六的社团活动所在列根本不显示。 */
    fun hasWeekendArrangement(
        schedule: Schedule,
        week: Int,
        events: List<PersonalEvent> = emptyList()
    ): Boolean =
        schedule.courses.any { course -> course.day > WEEKDAY_COUNT && week in course.weeks } ||
            schedule.adjustments.any { adjustment ->
                adjustment.targetWeek == week && adjustment.targetDay > WEEKDAY_COUNT
            } ||
            schedule.holidays.any { holiday ->
                holiday.week == week && holiday.day > WEEKDAY_COUNT
            } ||
            schedule.makeups.any { makeup ->
                (makeup.targetWeek == week && makeup.targetDay > WEEKDAY_COUNT) ||
                    (makeup.sourceWeek == week && makeup.sourceDay > WEEKDAY_COUNT)
            } ||
            events.any { event -> event.day > WEEKDAY_COUNT && event.occursIn(week) }
}
