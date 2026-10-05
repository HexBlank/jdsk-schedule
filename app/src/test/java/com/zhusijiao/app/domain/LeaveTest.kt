package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar

/**
 * 请假：时间换算、哪些课算请假、提醒与「下一节课」是否跳过。
 * 开学日 2026-09-07 是周一，作息用默认作息：
 * 1–2 节 07:50–09:30，3–4 节 09:50–11:30，6–7 节 14:00–15:40。
 */
class LeaveTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun course(name: String, day: Int = 1, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) =
        Course(name = name, teacher = "李老师", position = "A101", day = day, startSection = start, endSection = end, weeks = weeks)

    private fun schedule(
        courses: List<Course>,
        semesterStart: String = "2026-09-07",
        holidays: List<DayHoliday> = emptyList()
    ) = Schedule(
        id = "s1", name = "测试课表", school = "", semesterStart = semesterStart, totalWeeks = 16,
        shareCode = null, revision = 1, createdAt = "", updatedAt = "", subscriberCount = 0, role = "owner",
        courseCount = courses.size, timeSlots = emptyList(), courses = courses, holidays = holidays
    )

    private fun leave(start: String, end: String, type: LeaveType = LeaveType.PERSONAL) =
        Leave(id = "l-$start", type = type, start = start, end = end, note = "", createdAt = "", updatedAt = "")

    private val monday = schedule(
        listOf(
            course("高等数学", start = 1, end = 2),
            course("大学英语", start = 3, end = 4),
            course("体育", start = 6, end = 7),
            course("线性代数", day = 2, start = 1, end = 2)
        )
    )

    // ===== 时间换算 =====

    @Test
    fun moment_roundTrips() {
        val millis = at(2026, 10, 8, 8, 5)
        assertEquals("2026-10-08 08:05", Leaves.formatMoment(millis))
        assertEquals(millis, Leaves.parseMoment("2026-10-08 08:05"))
        assertEquals("2026-10-08", Leaves.dateOf("2026-10-08 08:05"))
        assertEquals(485, Leaves.minutesOf("2026-10-08 08:05"))
    }

    @Test
    fun parseMoment_rejectsMalformed() {
        assertNull(Leaves.parseMoment(null))
        assertNull(Leaves.parseMoment("2026-10-08"))
        assertNull(Leaves.parseMoment("2026-10-08T08:05"))
        assertNull(Leaves.parseMoment("2026-10-08 25:00"))
    }

    @Test
    fun normalize_requiresEndAfterStart() {
        try {
            Leaves.normalize(LeaveDraft(LeaveType.PERSONAL, "2026-09-07 10:00", "2026-09-07 10:00"))
            fail("结束不晚于开始应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("结束时间要晚于开始时间", e.message)
        }
        val ok = Leaves.normalize(LeaveDraft(LeaveType.OFFICIAL, "2026-09-07 10:00", "2026-09-07 10:01", "  校运会  "))
        assertEquals("校运会", ok.note)
        assertEquals(LeaveType.OFFICIAL, ok.type)
    }

    @Test
    fun normalize_rejectsTooLongSpan() {
        try {
            Leaves.normalize(LeaveDraft(LeaveType.PERSONAL, "2026-01-01 00:00", "2026-12-31 00:00"))
            fail("超过上限的时长应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("最长"))
        }
    }

    @Test
    fun displayRange_sameDayAndCrossDay() {
        assertEquals("9月7日 周一 08:00–09:35", Leaves.displayRange("2026-09-07 08:00", "2026-09-07 09:35"))
        assertEquals(
            "9月7日 周一 08:00 – 9月8日 周二 17:30",
            Leaves.displayRange("2026-09-07 08:00", "2026-09-08 17:30")
        )
    }

    // ===== 哪些课算请假 =====

    @Test
    fun partialOverlap_counts() {
        // 请假到 08:00，第 1–2 节 07:50–09:30 只重叠 10 分钟也算
        val leaves = listOf(leave("2026-09-07 06:00", "2026-09-07 08:00"))
        assertNotNull(Leaves.leaveFor(leaves, "2026-09-07", "07:50", "09:30"))
    }

    @Test
    fun touchingEdges_doNotCount() {
        // 请假 09:30 结束、课 09:50 开始；请假 09:30 开始、课 09:30 结束：都只是首尾相接
        assertNull(Leaves.leaveFor(listOf(leave("2026-09-07 08:00", "2026-09-07 09:50")), "2026-09-07", "09:50", "11:30"))
        assertNull(Leaves.leaveFor(listOf(leave("2026-09-07 09:30", "2026-09-07 12:00")), "2026-09-07", "07:50", "09:30"))
    }

    @Test
    fun minutePrecision_decidesWhichClass() {
        // 09:31 开始请假：第 1–2 节（到 09:30）照常，第 3–4 节（09:50 起）请假
        val leaves = listOf(leave("2026-09-07 09:31", "2026-09-07 12:00"))
        assertNull(Leaves.leaveFor(leaves, monday, "2026-09-07", monday.courses[0]))
        assertNotNull(Leaves.leaveFor(leaves, monday, "2026-09-07", monday.courses[1]))
    }

    @Test
    fun otherDay_notAffected() {
        val leaves = listOf(leave("2026-09-07 00:00", "2026-09-07 23:59"))
        assertNull(Leaves.leaveFor(leaves, monday, "2026-09-14", monday.courses[0]))
    }

    @Test
    fun scheduleWithoutSemesterStart_neverOnLeave() {
        val s = schedule(monday.courses, semesterStart = "")
        val leaves = listOf(leave("2026-09-07 00:00", "2026-09-07 23:59"))
        assertNull(Leaves.leaveFor(leaves, s, "2026-09-07", s.courses[0]))
    }

    @Test
    fun affectedClasses_crossDaySpan() {
        // 周一 10:00 到周二 09:00：周一 3–4 节、6–7 节，周二 1–2 节
        val affected = Leaves.affectedClasses(monday, "2026-09-07 10:00", "2026-09-08 09:00")
        assertEquals(listOf("大学英语", "体育", "线性代数"), affected.map { it.course.name })
        assertEquals(listOf("2026-09-07", "2026-09-07", "2026-09-08"), affected.map { it.dateIso })
        assertEquals(listOf(1, 1, 2), affected.map { it.day })
    }

    @Test
    fun affectedClasses_skipsSuspendedDay() {
        val s = schedule(monday.courses, holidays = listOf(DayHoliday(id = "h1", week = 1, day = 1, createdAt = "", updatedAt = "")))
        val affected = Leaves.affectedClasses(s, "2026-09-07 00:00", "2026-09-08 23:59")
        assertEquals(listOf("线性代数"), affected.map { it.course.name })
    }

    @Test
    fun affectedClasses_endingAtMidnight_excludesThatDay() {
        val affected = Leaves.affectedClasses(monday, "2026-09-07 00:00", "2026-09-08 00:00")
        assertEquals(listOf("高等数学", "大学英语", "体育"), affected.map { it.course.name })
    }

    @Test
    fun candidateDates_coverSemesterAndIncludedDates() {
        val dates = Leaves.candidateDates("2026-09-07", 16, at(2026, 10, 8, 9, 0), listOf("2027-01-20"))
        assertEquals("2026-09-07", dates.first())
        assertEquals("2027-01-20", dates.last())
        assertTrue("2026-10-08" in dates)
        assertEquals(dates.sorted(), dates)
        assertEquals(dates.size, dates.toSet().size)
    }

    // ===== 提醒与下一节课 =====

    @Test
    fun nextClass_skipsClassOnLeave() {
        val leaves = listOf(leave("2026-09-07 07:00", "2026-09-07 09:40"))
        val info = NextClass.find(monday, emptyList(), at(2026, 9, 7, 7, 20), leaves)!!
        assertEquals("大学英语", info.item.title)
    }

    @Test
    fun reminderPlan_skipsClassesOnLeave() {
        val settings = ClassReminderSettings(enabled = true, leadMinutes = 10)
        val leaves = listOf(leave("2026-09-07 00:00", "2026-09-07 23:59", LeaveType.OFFICIAL))
        val plan = ClassReminderPlanner.plan(
            schedule = monday,
            events = emptyList(),
            settings = settings,
            nowMillis = at(2026, 9, 7, 6, 0),
            notifiedUpToMillis = 0L,
            leaves = leaves
        )
        // 周一整天请假：下一次提醒是周二第 1 节（07:50）前 10 分钟
        assertEquals("线性代数", plan.next.single().title)
        assertEquals(at(2026, 9, 8, 7, 40), plan.nextTriggerAtMillis)
    }

    @Test
    fun leaveOccurrences_listTheSkippedOnes() {
        val leaves = listOf(leave("2026-09-07 09:40", "2026-09-07 12:00", LeaveType.OFFICIAL))
        val skipped = ClassReminderPlanner.leaveOccurrencesOn(monday, 1, 1, "2026-09-07", leaves)
        assertEquals(listOf("大学英语"), skipped.map { it.first.title })
        assertEquals(LeaveType.OFFICIAL, skipped.single().second)
        val kept = ClassReminderPlanner.occurrencesOn(monday, emptyList(), false, 1, 1, "2026-09-07", leaves)
        assertEquals(listOf("高等数学", "体育"), kept.map { it.title })
    }

    @Test
    fun json_roundTripsAndDropsBrokenRecords() {
        val original = leave("2026-09-07 08:00", "2026-09-08 17:30", LeaveType.OFFICIAL).copy(note = "志愿者")
        val parsed = org.json.JSONArray()
            .put(original.toJson())
            .put(original.copy(id = "bad", end = "2026-09-07 07:00").toJson())
            .toLeaveList()
        assertEquals(listOf(original), parsed)
    }
}
