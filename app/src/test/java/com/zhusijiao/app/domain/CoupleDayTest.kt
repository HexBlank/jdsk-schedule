package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleDayTest {

    // 2026-09-07 是周一，2026-09-25 是第 3 周周五
    private val friday = "2026-09-25"

    private fun course(name: String, day: Int, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) = Course(
        name = name,
        teacher = "老师",
        position = "A101",
        day = day,
        startSection = start,
        endSection = end,
        weeks = weeks
    )

    private fun schedule(
        courses: List<Course>,
        semesterStart: String = "2026-09-07",
        holidays: List<DayHoliday> = emptyList()
    ) = Schedule(
        id = "s1",
        name = "课表",
        school = "",
        semesterStart = semesterStart,
        totalWeeks = 20,
        shareCode = null,
        revision = 1,
        createdAt = "",
        updatedAt = "",
        subscriberCount = 0,
        role = "owner",
        courseCount = courses.size,
        timeSlots = emptyList(),
        courses = courses,
        holidays = holidays
    )

    private fun event(day: Int, startTime: String?, endTime: String?, weeks: List<Int> = listOf(3)) = PersonalEvent(
        id = "e1",
        title = "社团例会",
        position = "学生活动中心",
        note = "",
        day = day,
        startSection = 10,
        endSection = 10,
        startTime = startTime,
        endTime = endTime,
        weeks = weeks,
        color = null,
        createdAt = "",
        updatedAt = ""
    )

    private fun t(value: String) = ScheduleTime.minutesOf(value)!!

    @Test
    fun `按真实日期换算周次和星期`() {
        assertEquals(5, CoupleDay.dayOfWeek(friday))
        assertEquals(3, CoupleDay.weekOf(schedule(emptyList()), friday))
        // 另一个人把开学日期设成了下一周：同一天对 TA 来说是第 2 周
        assertEquals(2, CoupleDay.weekOf(schedule(emptyList(), semesterStart = "2026-09-14"), friday))
    }

    @Test
    fun `一天的课按作息换成真实时间并排好序`() {
        val plan = CoupleDay.plan(schedule(listOf(course("大学英语", 5, 6, 7), course("高等数学", 5, 1, 2))), emptyList(), friday)!!
        assertEquals(listOf("高等数学", "大学英语"), plan.items.map { it.title })
        assertEquals(t("07:50"), plan.items[0].startMinutes)
        assertEquals(t("09:30"), plan.items[0].endMinutes)
        assertEquals(t("14:00"), plan.items[1].startMinutes)
    }

    @Test
    fun `停课日的课照样列出但标成停课`() {
        val holiday = DayHoliday(id = "h1", week = 3, day = 5, createdAt = "", updatedAt = "")
        val plan = CoupleDay.plan(schedule(listOf(course("现代汉语", 5, 3, 4)), holidays = listOf(holiday)), emptyList(), friday)!!
        assertTrue(plan.holiday)
        assertEquals(1, plan.items.size)
        assertTrue(plan.items[0].suspended)
        assertEquals("这天停课", CoupleDay.statusText(plan, isToday = false, nowMinutes = 0))
    }

    @Test
    fun `日程用自定义时间，没填时跟随节次作息`() {
        val custom = CoupleDay.plan(schedule(emptyList()), listOf(event(5, "18:30", "19:30")), friday)!!
        assertEquals(t("18:30"), custom.items.single().startMinutes)
        val bySection = CoupleDay.plan(schedule(emptyList()), listOf(event(5, null, null)), friday)!!
        assertEquals(t("19:15"), bySection.items.single().startMinutes)
        val otherWeek = CoupleDay.plan(schedule(emptyList()), listOf(event(5, null, null, weeks = listOf(4))), friday)!!
        assertTrue(otherWeek.items.isEmpty())
    }

    @Test
    fun `今天的状态：上课中、空闲并预告下一节、都上完了`() {
        val plan = CoupleDay.plan(schedule(listOf(course("现代汉语", 5, 3, 4), course("心理学", 5, 6, 7))), emptyList(), friday)!!
        assertEquals("上课中 · 11:30 下课", CoupleDay.statusText(plan, isToday = true, nowMinutes = t("10:20")))
        assertEquals("空闲 · 14:00 心理学", CoupleDay.statusText(plan, isToday = true, nowMinutes = t("12:00")))
        assertEquals("今天的课都上完了", CoupleDay.statusText(plan, isToday = true, nowMinutes = t("20:00")))
    }

    @Test
    fun `其他日子显示几门课和时间范围`() {
        val plan = CoupleDay.plan(
            schedule(listOf(course("高等数学", 5, 1, 2), course("高等数学", 5, 6, 7), course("体育", 5, 8, 9))),
            emptyList(),
            friday
        )!!
        assertEquals("2 门课 · 07:50–17:40", CoupleDay.statusText(plan, isToday = false, nowMinutes = 0))
        assertEquals("今天没课", CoupleDay.statusText(CoupleDay.plan(schedule(emptyList()), emptyList(), friday), true, 0))
        assertEquals("还没有课表", CoupleDay.statusText(null, isToday = true, nowMinutes = 0))
    }

    @Test
    fun `都有空：两个人的课和我的日程都算占用，停课的不算`() {
        val holiday = DayHoliday(id = "h1", week = 3, day = 5, createdAt = "", updatedAt = "")
        val mine = CoupleDay.plan(
            schedule(listOf(course("高等数学", 5, 1, 2))),
            listOf(event(5, "18:30", "19:30")),
            friday
        )
        val partner = CoupleDay.plan(schedule(listOf(course("现代汉语", 5, 3, 4)), holidays = listOf(holiday)), emptyList(), friday)
        val (start, end) = CoupleDay.dayRange(null)
        val gaps = CoupleDay.freeGaps(mine, partner, start, end)
        assertEquals(listOf("都有空 9小时", "19:30 后都有空"), gaps.map(CoupleFreeTime::label))
    }

    @Test
    fun `日期非法时没有计划`() {
        assertNull(CoupleDay.plan(schedule(emptyList()), emptyList(), "not-a-date"))
    }
}
