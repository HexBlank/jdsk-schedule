package com.zhusijiao.app.domain

import java.util.Calendar

/**
 * 课表页顶部「下一节课」提示条的数据：今天还没下课的第一节课或日程（正在上的也算）。
 * 口径与上课提醒一致（[ClassReminderPlanner.occurrencesOn]），调课、停课、补课都已算进去，
 * 连着上的同一门课合成一段。纯 Kotlin，JVM 单元测试可直接覆盖。
 */
object NextClass {

    data class Info(
        val item: ReminderOccurrence,
        /** 教学周与星期（1 = 周一），点提示条时用来找到课表里对应的块。 */
        val week: Int,
        val day: Int,
        /** 已经开始、还没下课。 */
        val ongoing: Boolean,
        /** 距离上课还有几分钟；正在上时为 0。 */
        val minutesUntil: Int
    )

    fun find(schedule: Schedule, events: List<PersonalEvent>, nowMillis: Long): Info? {
        if (!DateUtils.hasSemesterStart(schedule.semesterStart)) return null
        val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        val week = DateUtils.teachingWeekAt(schedule.semesterStart, nowMillis)
        if (week < 1 || week > totalWeeks) return null
        val todayIso = DateUtils.formatDate(Calendar.getInstance().apply { timeInMillis = nowMillis })
        val dates = DateUtils.datesForWeek(schedule.semesterStart, week)
        val day = dates.indexOfFirst { it.iso == todayIso } + 1
        if (day < 1) return null
        val item = ClassReminderPlanner.occurrencesOn(schedule, events, includeEvents = true, week, day, todayIso)
            .firstOrNull { it.endAtMillis > nowMillis }
            ?: return null
        val ongoing = item.startAtMillis <= nowMillis
        return Info(
            item = item,
            week = week,
            day = day,
            ongoing = ongoing,
            minutesUntil = if (ongoing) 0 else ClassReminderPlanner.minutesUntil(item.startAtMillis, nowMillis)
        )
    }
}
