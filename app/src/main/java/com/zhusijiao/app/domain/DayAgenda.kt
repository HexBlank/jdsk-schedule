package com.zhusijiao.app.domain

import java.util.Calendar

/**
 * 桌面小部件要显示的内容：今天的课和日程、下一节是哪节、今天没课时之后最近哪天有课，
 * 以及这些内容下一次会变的时刻（供小部件定时刷新）。
 *
 * 口径与课表页、上课提醒一致（[ClassReminderPlanner.occurrencesOn]）：调课、停课、补课、
 * 请假都已算进去，连着上的同一门课合成一段。纯 Kotlin，JVM 单元测试可直接覆盖。
 */
object DayAgenda {

    enum class State { DONE, ONGOING, UPCOMING }

    data class Item(
        val occurrence: ReminderOccurrence,
        /** 这节课在请假时段内（不去上）；正常要上的为 null。 */
        val leave: LeaveType?,
        val state: State
    )

    data class Day(
        val dateIso: String,
        val week: Int,
        /** 星期，1 = 周一。 */
        val day: Int,
        /** 距今天几天：0 今天、1 明天…… */
        val offsetDays: Int,
        val items: List<Item>
    ) {
        /** 真正要去上的（不含请假的课）。 */
        val attending: List<Item> get() = items.filter { it.leave == null }
    }

    enum class Blocker {
        /** 课表没有开学日期，推算不出今天是第几周周几。 */
        NO_SEMESTER_START,
        /** 学期已经结束。 */
        SEMESTER_OVER
    }

    data class Snapshot(
        val blocker: Blocker?,
        /** 今天；还没开学或已放假时为 null。 */
        val today: Day?,
        /** 今天还没下课的第一节课或日程（正在上的也算，请假的不算）。 */
        val next: Item?,
        /** 今天之后最近一个有课要上的日子（往后找两周）。 */
        val upcoming: Day?,
        /** 内容下一次会变的时刻：今天某一项开始或结束，或者到了第二天。 */
        val refreshAtMillis: Long
    )

    /** 往后最多找这么多天。 */
    const val LOOKAHEAD_DAYS = 14

    fun snapshot(
        schedule: Schedule,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        nowMillis: Long
    ): Snapshot {
        val midnight = nextMidnight(nowMillis)
        if (!DateUtils.hasSemesterStart(schedule.semesterStart)) {
            return Snapshot(Blocker.NO_SEMESTER_START, null, null, null, midnight)
        }
        val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        if (DateUtils.teachingWeekAt(schedule.semesterStart, nowMillis) > totalWeeks) {
            return Snapshot(Blocker.SEMESTER_OVER, null, null, null, midnight)
        }
        val today = dayAt(schedule, events, leaves, nowMillis, 0, totalWeeks)
        val upcoming = (1..LOOKAHEAD_DAYS).firstNotNullOfOrNull { offset ->
            dayAt(schedule, events, leaves, nowMillis, offset, totalWeeks)?.takeIf { it.attending.isNotEmpty() }
        }
        val boundaries = today?.items.orEmpty()
            .flatMap { listOf(it.occurrence.startAtMillis, it.occurrence.endAtMillis) }
            .filter { it > nowMillis }
        return Snapshot(
            blocker = null,
            today = today,
            next = today?.attending?.firstOrNull { it.state != State.DONE },
            upcoming = upcoming,
            refreshAtMillis = minOf(boundaries.minOrNull() ?: midnight, midnight)
        )
    }

    /** 今天往后第 [offsetDays] 天的安排；那天不在学期内返回 null。 */
    private fun dayAt(
        schedule: Schedule,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        nowMillis: Long,
        offsetDays: Int,
        totalWeeks: Int
    ): Day? {
        val date = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offsetDays)
        }
        val week = DateUtils.teachingWeekAt(schedule.semesterStart, date.timeInMillis)
        if (week < 1 || week > totalWeeks) return null
        val day = DateUtils.dayOfWeek(date)
        val dateIso = DateUtils.formatDate(date)
        val attending = ClassReminderPlanner.occurrencesOn(schedule, events, includeEvents = true, week, day, dateIso, leaves)
            .map { Item(it, null, stateOf(it, nowMillis)) }
        val onLeave = ClassReminderPlanner.leaveOccurrencesOn(schedule, week, day, dateIso, leaves)
            .map { (item, type) -> Item(item, type, stateOf(item, nowMillis)) }
        val items = (attending + onLeave).sortedWith(
            compareBy({ it.occurrence.startAtMillis }, { it.occurrence.isEvent }, { it.occurrence.title })
        )
        return Day(dateIso, week, day, offsetDays, items)
    }

    private fun stateOf(item: ReminderOccurrence, nowMillis: Long): State = when {
        item.endAtMillis <= nowMillis -> State.DONE
        item.startAtMillis <= nowMillis -> State.ONGOING
        else -> State.UPCOMING
    }

    private fun nextMidnight(nowMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
