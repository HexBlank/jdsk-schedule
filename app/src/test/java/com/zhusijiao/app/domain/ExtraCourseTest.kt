package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar

/**
 * 自己加的课：校验、并进课表、并进去之后和普通课一样对待。
 * 开学日 2026-09-07 是周一，作息用默认作息：1–2 节 07:50–09:30，3–4 节 09:50–11:30。
 */
class ExtraCourseTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun course(name: String, day: Int = 1, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) =
        Course(name = name, teacher = "李老师", position = "A101", day = day, startSection = start, endSection = end, weeks = weeks)

    private fun extra(name: String, day: Int = 1, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) =
        course(name, day, start, end, weeks).copy(id = ExtraCourses.ID_PREFIX + name)

    private fun schedule(
        courses: List<Course>,
        totalWeeks: Int = 16,
        holidays: List<DayHoliday> = emptyList()
    ) = Schedule(
        id = "s1", name = "测试课表", school = "", semesterStart = "2026-09-07", totalWeeks = totalWeeks,
        shareCode = null, revision = 1, createdAt = "", updatedAt = "", subscriberCount = 0, role = "subscriber",
        courseCount = courses.size, timeSlots = emptyList(), courses = courses, holidays = holidays
    )

    private val base = schedule(listOf(course("高等数学", start = 1, end = 2)))

    @Test
    fun isExtra_goesByIdPrefix() {
        assertTrue(ExtraCourses.isExtra(extra("物理实验", start = 3, end = 4)))
        assertFalse(ExtraCourses.isExtra(base.courses[0]))
    }

    @Test
    fun normalize_trimsAndSortsWeeks() {
        val result = ExtraCourses.normalize(
            extra("  物理实验  ", start = 3, end = 4, weeks = listOf(5, 1, 3, 3)).copy(teacher = " 王老师 ", position = " B204 "),
            totalWeeks = 16
        )
        assertEquals("物理实验", result.name)
        assertEquals("王老师", result.teacher)
        assertEquals("B204", result.position)
        assertEquals(listOf(1, 3, 5), result.weeks)
    }

    @Test
    fun normalize_rejectsBadInput() {
        fun rejected(course: Course): String = try {
            ExtraCourses.normalize(course, totalWeeks = 16)
            fail("应当被拒绝")
            ""
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertEquals("课程名称不能为空", rejected(extra("  ", start = 1, end = 2)))
        assertEquals("请至少选择一个周次", rejected(extra("物理实验", start = 1, end = 2, weeks = emptyList())))
        assertEquals("周次超出学期范围", rejected(extra("物理实验", start = 1, end = 2, weeks = listOf(17))))
        assertEquals("节次无效", rejected(extra("物理实验", start = 5, end = 4)))
        assertEquals("星期无效", rejected(extra("物理实验", day = 8, start = 1, end = 2)))
    }

    @Test
    fun merge_withoutExtras_returnsSameSchedule() {
        assertSame(base, ExtraCourses.merge(base, emptyList()))
    }

    @Test
    fun merge_appendsAndCropsWeeksBeyondSemester() {
        // 课表重新导入后只剩 8 周：超出的周次裁掉，一周不剩的整门不并
        val short = schedule(base.courses, totalWeeks = 8)
        val merged = ExtraCourses.merge(
            short,
            listOf(
                extra("物理实验", start = 3, end = 4, weeks = listOf(7, 8, 9, 10)),
                extra("形势与政策", start = 6, end = 7, weeks = listOf(12, 13))
            )
        )
        assertEquals(listOf("高等数学", "物理实验"), merged.courses.map { it.name })
        assertEquals(listOf(7, 8), merged.courses[1].weeks)
        // 摘要字段说的是课表本身，不跟着变
        assertEquals(1, merged.courseCount)
    }

    @Test
    fun merged_extraCourse_isRemindedLikeAnyCourse() {
        val merged = ExtraCourses.merge(base, listOf(extra("物理实验", start = 3, end = 4)))
        val items = ClassReminderPlanner.occurrencesOn(merged, emptyList(), false, 1, 1, "2026-09-07")
        assertEquals(listOf("高等数学", "物理实验"), items.map { it.title })
        assertFalse(items[1].isEvent)
        // 上完第一节后，下一节就是自己加的那门
        val next = NextClass.find(merged, emptyList(), at(2026, 9, 7, 9, 40))!!
        assertEquals("物理实验", next.item.title)
    }

    @Test
    fun merged_extraCourse_followsHolidayAndLeave() {
        val extras = listOf(extra("物理实验", start = 3, end = 4))
        // 停课日：自己加的课也停
        val suspended = ExtraCourses.merge(
            schedule(base.courses, holidays = listOf(DayHoliday(id = "h1", week = 1, day = 1, createdAt = "", updatedAt = ""))),
            extras
        )
        assertTrue(ScheduleOccurrences.coursesOn(suspended, 1, 1).isEmpty())
        // 请假：自己加的课同样算进去
        val merged = ExtraCourses.merge(base, extras)
        val affected = Leaves.affectedClasses(merged, "2026-09-07 09:40", "2026-09-07 12:00")
        assertEquals(listOf("物理实验"), affected.map { it.course.name })
    }

    @Test
    fun conflicts_groupOverlappingWeeksByCourseName() {
        val draft = extra("物理实验", start = 2, end = 3, weeks = listOf(1, 2, 3))
        val conflicts = ExtraCourses.conflicts(base, draft)
        assertEquals(1, conflicts.size)
        assertEquals("高等数学", conflicts[0].courseName)
        assertEquals(listOf(1, 2, 3), conflicts[0].weeks)
        assertTrue(ExtraCourses.conflicts(base, extra("物理实验", start = 3, end = 4)).isEmpty())
        assertTrue(ExtraCourses.conflicts(base, extra("物理实验", day = 2, start = 1, end = 2)).isEmpty())
    }
}
