package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 上课提醒排期。开学日 2026-09-07 是周一，作息用默认作息：
 * 第 1 节 07:50，第 3 节 09:50，第 5 节 11:31–12:15，第 6 节 14:00，第 10 节 19:15。
 */
class ClassReminderPlannerTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun course(
        name: String = "高等数学",
        position: String = "A101",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        weeks: List<Int> = (1..16).toList()
    ) = Course(name = name, teacher = "李老师", position = position, day = day, startSection = start, endSection = end, weeks = weeks)

    private fun schedule(
        courses: List<Course>,
        semesterStart: String = "2026-09-07",
        totalWeeks: Int = 16,
        adjustments: List<CourseAdjustment> = emptyList(),
        holidays: List<DayHoliday> = emptyList(),
        makeups: List<DayMakeup> = emptyList()
    ) = Schedule(
        id = "s1",
        name = "测试课表",
        school = "",
        semesterStart = semesterStart,
        totalWeeks = totalWeeks,
        shareCode = null,
        revision = 1,
        createdAt = "",
        updatedAt = "",
        subscriberCount = 0,
        role = "owner",
        courseCount = courses.size,
        timeSlots = emptyList(),
        courses = courses,
        adjustments = adjustments,
        holidays = holidays,
        makeups = makeups
    )

    private fun event(
        title: String = "社团例会",
        day: Int = 1,
        startTime: String? = "19:00",
        endTime: String? = "20:30",
        weeks: List<Int> = listOf(1, 2)
    ) = PersonalEvent(
        id = "e1", title = title, position = "学生活动中心", note = "", day = day,
        startSection = 10, endSection = 11, startTime = startTime, endTime = endTime,
        weeks = weeks, color = null, createdAt = "", updatedAt = ""
    )

    private val on = ClassReminderSettings(enabled = true, leadMinutes = 10)

    private fun plan(
        s: Schedule,
        now: Long,
        settings: ClassReminderSettings = on,
        events: List<PersonalEvent> = emptyList(),
        notifiedUpTo: Long = 0L,
        tolerance: Long = 0L
    ) = ClassReminderPlanner.plan(s, events, settings, now, notifiedUpTo, tolerance)

    @Test
    fun `下一次提醒在第一节课前N分钟`() {
        val result = plan(schedule(listOf(course())), at(2026, 9, 7, 6, 0))
        assertEquals(at(2026, 9, 7, 7, 40), result.nextTriggerAtMillis)
        assertEquals(listOf("高等数学"), result.next.map { it.title })
        assertEquals("07:50", result.next[0].startTime)
        assertEquals("09:30", result.next[0].endTime)
        assertTrue(result.due.isEmpty())
        assertNull(result.blocker)
    }

    @Test
    fun `提前量按设置计算，非法值回退到默认10分钟`() {
        val s = schedule(listOf(course()))
        val now = at(2026, 9, 7, 6, 0)
        assertEquals(at(2026, 9, 7, 7, 20), plan(s, now, on.copy(leadMinutes = 30)).nextTriggerAtMillis)
        assertEquals(at(2026, 9, 7, 7, 40), plan(s, now, on.copy(leadMinutes = 7)).nextTriggerAtMillis)
    }

    @Test
    fun `同一门课同一教室连着上只提醒第一段`() {
        val s = schedule(listOf(course(start = 1, end = 2), course(start = 3, end = 4)))
        val items = ClassReminderPlanner.occurrencesOn(s, emptyList(), false, 1, 1, "2026-09-07")
        assertEquals(1, items.size)
        assertEquals("07:50", items[0].startTime)
        assertEquals("11:30", items[0].endTime)
    }

    @Test
    fun `同一门课换了教室两段都提醒`() {
        val s = schedule(listOf(course(start = 1, end = 2), course(position = "B202", start = 3, end = 4)))
        val items = ClassReminderPlanner.occurrencesOn(s, emptyList(), false, 1, 1, "2026-09-07")
        assertEquals(listOf("A101", "B202"), items.map { it.position })
    }

    @Test
    fun `不同的课连着上两门都提醒`() {
        val s = schedule(listOf(course(start = 1, end = 2), course(name = "大学英语", start = 3, end = 4)))
        val items = ClassReminderPlanner.occurrencesOn(s, emptyList(), false, 1, 1, "2026-09-07")
        assertEquals(listOf("高等数学", "大学英语"), items.map { it.title })
    }

    @Test
    fun `同一门课同一教室被午休隔开两段各提醒一次`() {
        val s = schedule(listOf(course(start = 4, end = 5), course(start = 6, end = 7)))
        val items = ClassReminderPlanner.occurrencesOn(s, emptyList(), false, 1, 1, "2026-09-07")
        assertEquals(listOf("10:45", "14:00"), items.map { it.startTime })
    }

    @Test
    fun `已提醒过的课不再提醒，改排下一节`() {
        val s = schedule(listOf(course(start = 1, end = 2), course(name = "大学英语", start = 6, end = 7)))
        val now = at(2026, 9, 7, 7, 45)
        val first = plan(s, now)
        assertEquals(listOf("高等数学"), first.due.map { it.title })
        assertEquals(at(2026, 9, 7, 13, 50), first.nextTriggerAtMillis)

        val again = plan(s, now, notifiedUpTo = first.due.maxOf { it.startAtMillis })
        assertTrue(again.due.isEmpty())
        assertEquals(at(2026, 9, 7, 13, 50), again.nextTriggerAtMillis)
    }

    @Test
    fun `错过提醒时刻但还没上课时立即补发，已经上课的不补`() {
        val s = schedule(listOf(course(start = 1, end = 2)))
        assertEquals(1, plan(s, at(2026, 9, 7, 7, 49)).due.size)
        val started = plan(s, at(2026, 9, 7, 7, 50))
        assertTrue(started.due.isEmpty())
        assertEquals(at(2026, 9, 14, 7, 40), started.nextTriggerAtMillis)
    }

    @Test
    fun `系统时间被往回调时作废已提醒记录`() {
        val s = schedule(listOf(course()))
        val result = plan(s, at(2026, 9, 7, 7, 45), notifiedUpTo = at(2026, 9, 21, 7, 50))
        assertEquals(1, result.due.size)
    }

    @Test
    fun `不精确定时提前送达时在容差内算到点`() {
        val s = schedule(listOf(course()))
        val now = at(2026, 9, 7, 7, 32)
        assertTrue(plan(s, now).due.isEmpty())
        assertEquals(1, plan(s, now, tolerance = 10 * 60_000L).due.size)
    }

    @Test
    fun `停课日不提醒，顺延到下一次有课`() {
        val holiday = DayHoliday(id = "h1", week = 1, day = 1, createdAt = "", updatedAt = "")
        val result = plan(schedule(listOf(course()), holidays = listOf(holiday)), at(2026, 9, 7, 6, 0))
        assertEquals(at(2026, 9, 14, 7, 40), result.nextTriggerAtMillis)
    }

    @Test
    fun `调课后按调到的时间和教室提醒`() {
        val base = course(weeks = listOf(1, 2))
        val adjustment = CourseAdjustment(
            id = "a1", courseId = base.id,
            sourceWeek = 1, sourceDay = 1, sourceStartSection = 1, sourceEndSection = 2,
            targetWeek = 1, targetDay = 3, targetStartSection = 6, targetEndSection = 7,
            targetPosition = "C303", courseSnapshot = base, createdAt = "", updatedAt = ""
        )
        val result = plan(schedule(listOf(base), adjustments = listOf(adjustment)), at(2026, 9, 7, 6, 0))
        assertEquals(at(2026, 9, 9, 13, 50), result.nextTriggerAtMillis)
        assertEquals("C303", result.next.single().position)
    }

    @Test
    fun `补课日按来源日的课提醒`() {
        val makeup = DayMakeup(
            id = "m1", sourceWeek = 2, sourceDay = 1, targetWeek = 1, targetDay = 6, createdAt = "", updatedAt = ""
        )
        val s = schedule(listOf(course(weeks = listOf(2))), makeups = listOf(makeup))
        val result = plan(s, at(2026, 9, 7, 6, 0))
        assertEquals(at(2026, 9, 12, 7, 40), result.nextTriggerAtMillis)
        // 来源日当天课已经搬走，不能再提醒一次
        val afterMakeup = plan(s, at(2026, 9, 12, 12, 0))
        assertEquals(ClassReminderPlanner.Blocker.NOTHING_UPCOMING, afterMakeup.blocker)
    }

    @Test
    fun `开学前提醒第1周的第一节课`() {
        val result = plan(schedule(listOf(course())), at(2026, 8, 30, 20, 0))
        assertEquals(at(2026, 9, 7, 7, 40), result.nextTriggerAtMillis)
    }

    @Test
    fun `学期结束后不再提醒`() {
        val result = plan(schedule(listOf(course()), totalWeeks = 16), at(2027, 1, 20, 7, 0))
        assertNull(result.nextTriggerAtMillis)
        assertEquals(ClassReminderPlanner.Blocker.SEMESTER_OVER, result.blocker)
    }

    @Test
    fun `没有开学日期时无法推算`() {
        val result = plan(schedule(listOf(course()), semesterStart = ""), at(2026, 9, 7, 6, 0))
        assertEquals(ClassReminderPlanner.Blocker.NO_SEMESTER_START, result.blocker)
    }

    @Test
    fun `日程默认不提醒，打开开关后按自定义时间提醒`() {
        val s = schedule(emptyList())
        val now = at(2026, 9, 7, 12, 0)
        val events = listOf(event())
        assertEquals(ClassReminderPlanner.Blocker.NOTHING_UPCOMING, plan(s, now, events = events).blocker)

        val result = plan(s, now, on.copy(includeEvents = true), events)
        assertEquals(at(2026, 9, 7, 18, 50), result.nextTriggerAtMillis)
        assertTrue(result.next.single().isEvent)
    }

    @Test
    fun `日程没填时间时跟随节次作息`() {
        val s = schedule(emptyList())
        val result = plan(s, at(2026, 9, 7, 12, 0), on.copy(includeEvents = true), listOf(event(startTime = null, endTime = null)))
        assertEquals("19:15", result.next.single().startTime)
        assertEquals("20:55", result.next.single().endTime)
    }

    @Test
    fun `同一时刻开始的课和日程一起提醒`() {
        val s = schedule(listOf(course(start = 10, end = 11)))
        val result = plan(s, at(2026, 9, 7, 12, 0), on.copy(includeEvents = true), listOf(event(startTime = "19:15")))
        assertEquals(2, result.next.size)
        assertEquals(at(2026, 9, 7, 19, 5), result.nextTriggerAtMillis)
    }

    @Test
    fun `距离开始的分钟数向上取整`() {
        val start = at(2026, 9, 7, 7, 50)
        assertEquals(10, ClassReminderPlanner.minutesUntil(start, start - 10 * 60_000L))
        assertEquals(10, ClassReminderPlanner.minutesUntil(start, start - 9 * 60_000L - 5_000L))
        assertEquals(0, ClassReminderPlanner.minutesUntil(start, start + 1_000L))
    }

    @Test
    fun `提醒时刻文案区分今天明天和具体日期`() {
        val now = at(2026, 9, 7, 6, 0)
        assertEquals("今天 07:40", ClassReminderPlanner.formatMoment(at(2026, 9, 7, 7, 40), now))
        assertEquals("明天 07:40", ClassReminderPlanner.formatMoment(at(2026, 9, 8, 7, 40), now))
        assertEquals("9月14日 周一 07:40", ClassReminderPlanner.formatMoment(at(2026, 9, 14, 7, 40), now))
    }

    @Test
    fun `教学周不夹取`() {
        assertEquals(0, DateUtils.teachingWeekAt("2026-09-07", at(2026, 9, 1, 12, 0)))
        assertEquals(1, DateUtils.teachingWeekAt("2026-09-07", at(2026, 9, 13, 23, 0)))
        assertEquals(21, DateUtils.teachingWeekAt("2026-09-07", at(2027, 1, 25, 8, 0)))
    }
}
