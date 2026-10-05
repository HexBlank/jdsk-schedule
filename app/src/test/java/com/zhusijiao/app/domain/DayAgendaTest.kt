package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 桌面小部件的内容。开学日 2026-09-07 是周一，作息用默认作息：
 * 1–2 节 07:50–09:30，3–4 节 09:50–11:30。周一两节课，周三一节，其余没课。
 */
class DayAgendaTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun course(name: String, day: Int = 1, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) =
        Course(name = name, teacher = "李老师", position = "A101", day = day, startSection = start, endSection = end, weeks = weeks)

    private fun schedule(courses: List<Course>, semesterStart: String = "2026-09-07") = Schedule(
        id = "s1", name = "测试课表", school = "", semesterStart = semesterStart, totalWeeks = 16,
        shareCode = null, revision = 1, createdAt = "", updatedAt = "", subscriberCount = 0, role = "owner",
        courseCount = courses.size, timeSlots = emptyList(), courses = courses
    )

    private val s = schedule(
        listOf(
            course("高等数学", start = 1, end = 2),
            course("大学英语", start = 3, end = 4),
            course("体育", day = 3, start = 1, end = 2)
        )
    )

    private fun leave(start: String, end: String) =
        Leave(id = "l1", type = LeaveType.PERSONAL, start = start, end = end, note = "", createdAt = "", updatedAt = "")

    @Test
    fun beforeFirstClass_nextIsFirst_refreshAtItsStart() {
        val snap = DayAgenda.snapshot(s, emptyList(), emptyList(), at(2026, 9, 7, 7, 0))
        assertNull(snap.blocker)
        assertEquals(listOf("高等数学", "大学英语"), snap.today!!.items.map { it.occurrence.title })
        assertEquals("高等数学", snap.next!!.occurrence.title)
        assertEquals(DayAgenda.State.UPCOMING, snap.next!!.state)
        assertEquals(at(2026, 9, 7, 7, 50), snap.refreshAtMillis)
    }

    @Test
    fun duringClass_isOngoing_refreshAtItsEnd() {
        val snap = DayAgenda.snapshot(s, emptyList(), emptyList(), at(2026, 9, 7, 8, 0))
        assertEquals(DayAgenda.State.ONGOING, snap.next!!.state)
        assertEquals(at(2026, 9, 7, 9, 30), snap.refreshAtMillis)
    }

    @Test
    fun afterLastClass_noNext_upcomingIsWednesday_refreshAtMidnight() {
        val snap = DayAgenda.snapshot(s, emptyList(), emptyList(), at(2026, 9, 7, 13, 0))
        assertNull(snap.next)
        assertTrue(snap.today!!.items.all { it.state == DayAgenda.State.DONE })
        assertEquals(2, snap.upcoming!!.offsetDays)
        assertEquals(3, snap.upcoming!!.day)
        assertEquals("体育", snap.upcoming!!.attending.single().occurrence.title)
        assertEquals(at(2026, 9, 8, 0, 0), snap.refreshAtMillis)
    }

    @Test
    fun dayWithoutClasses_todayEmpty_upcomingTomorrow() {
        val snap = DayAgenda.snapshot(s, emptyList(), emptyList(), at(2026, 9, 8, 10, 0))
        assertTrue(snap.today!!.items.isEmpty())
        assertNull(snap.next)
        assertEquals(1, snap.upcoming!!.offsetDays)
    }

    @Test
    fun leaveClass_listedButNotNext() {
        val leaves = listOf(leave("2026-09-07 07:00", "2026-09-07 09:40"))
        val snap = DayAgenda.snapshot(s, emptyList(), leaves, at(2026, 9, 7, 7, 0))
        val items = snap.today!!.items
        assertEquals(listOf("高等数学", "大学英语"), items.map { it.occurrence.title })
        assertEquals(LeaveType.PERSONAL, items[0].leave)
        assertNull(items[1].leave)
        assertEquals("大学英语", snap.next!!.occurrence.title)
    }

    @Test
    fun wholeDayLeave_upcomingSkipsThatDay() {
        // 周一没课要上（全请假），之后最近有课要上的是周三
        val leaves = listOf(leave("2026-09-07 00:00", "2026-09-07 23:59"))
        val snap = DayAgenda.snapshot(s, emptyList(), leaves, at(2026, 9, 6 + 1, 6, 0))
        assertNull(snap.next)
        assertTrue(snap.today!!.attending.isEmpty())
        assertEquals(3, snap.upcoming!!.day)
    }

    @Test
    fun beforeSemester_todayNull_upcomingWithinTwoWeeks() {
        val snap = DayAgenda.snapshot(s, emptyList(), emptyList(), at(2026, 9, 5, 10, 0))
        assertNull(snap.blocker)
        assertNull(snap.today)
        assertEquals("2026-09-07", snap.upcoming!!.dateIso)
        assertEquals(2, snap.upcoming!!.offsetDays)
    }

    @Test
    fun semesterOver_andMissingSemesterStart_areBlockers() {
        assertEquals(
            DayAgenda.Blocker.SEMESTER_OVER,
            DayAgenda.snapshot(s, emptyList(), emptyList(), at(2027, 3, 1, 10, 0)).blocker
        )
        assertEquals(
            DayAgenda.Blocker.NO_SEMESTER_START,
            DayAgenda.snapshot(schedule(s.courses, semesterStart = ""), emptyList(), emptyList(), at(2026, 9, 7, 7, 0)).blocker
        )
    }
}
