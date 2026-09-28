package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 课表页「下一节课」提示条。开学日 2026-09-07 是周一，作息用默认作息：
 * 第 1 节 07:50，第 3 节 09:50，第 6 节 14:00。
 */
class NextClassTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun course(name: String, day: Int = 1, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) =
        Course(name = name, teacher = "李老师", position = "A101", day = day, startSection = start, endSection = end, weeks = weeks)

    private fun schedule(courses: List<Course>, semesterStart: String = "2026-09-07", holidays: List<DayHoliday> = emptyList()) = Schedule(
        id = "s1", name = "测试课表", school = "", semesterStart = semesterStart, totalWeeks = 16,
        shareCode = null, revision = 1, createdAt = "", updatedAt = "", subscriberCount = 0, role = "owner",
        courseCount = courses.size, timeSlots = emptyList(), courses = courses, holidays = holidays
    )

    private val twoClasses = schedule(listOf(course("高等数学", start = 1, end = 2), course("大学英语", start = 3, end = 4)))

    @Test
    fun beforeFirstClass_returnsItWithMinutes() {
        val info = NextClass.find(twoClasses, emptyList(), at(2026, 9, 7, 7, 20))!!
        assertEquals("高等数学", info.item.title)
        assertFalse(info.ongoing)
        assertEquals(30, info.minutesUntil)
        assertEquals(1, info.week)
        assertEquals(1, info.day)
    }

    @Test
    fun duringClass_isOngoing() {
        val info = NextClass.find(twoClasses, emptyList(), at(2026, 9, 7, 8, 0))!!
        assertEquals("高等数学", info.item.title)
        assertTrue(info.ongoing)
        assertEquals(0, info.minutesUntil)
    }

    @Test
    fun betweenClasses_returnsNextOne() {
        val info = NextClass.find(twoClasses, emptyList(), at(2026, 9, 7, 9, 40))!!
        assertEquals("大学英语", info.item.title)
        assertEquals(10, info.minutesUntil)
    }

    @Test
    fun afterLastClass_returnsNull() {
        assertNull(NextClass.find(twoClasses, emptyList(), at(2026, 9, 7, 13, 0)))
    }

    @Test
    fun dayWithoutClasses_returnsNull() {
        assertNull(NextClass.find(twoClasses, emptyList(), at(2026, 9, 8, 7, 0)))
    }

    @Test
    fun suspendedDay_returnsNull() {
        val s = schedule(twoClasses.courses, holidays = listOf(DayHoliday(id = "h1", week = 1, day = 1, createdAt = "", updatedAt = "")))
        assertNull(NextClass.find(s, emptyList(), at(2026, 9, 7, 7, 0)))
    }

    @Test
    fun outsideSemester_returnsNull() {
        assertNull(NextClass.find(twoClasses, emptyList(), at(2026, 9, 6, 7, 0)))
        assertNull(NextClass.find(twoClasses, emptyList(), at(2027, 3, 1, 7, 0)))
    }

    @Test
    fun laterWeek_usesThatWeeksDay() {
        val info = NextClass.find(schedule(listOf(course("体育", day = 3, start = 6, end = 7))), emptyList(), at(2026, 9, 16, 13, 0))!!
        assertEquals("体育", info.item.title)
        assertEquals(2, info.week)
        assertEquals(3, info.day)
        assertEquals(60, info.minutesUntil)
    }
}
