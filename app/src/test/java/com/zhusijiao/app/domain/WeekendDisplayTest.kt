package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekendDisplayTest {

    private val weekendCourse = Course(
        name = "周末课程",
        teacher = "老师",
        position = "A101",
        day = 6,
        startSection = 1,
        endSection = 2,
        weeks = listOf(3)
    )

    private fun schedule(
        courses: List<Course> = listOf(weekendCourse),
        adjustments: List<CourseAdjustment> = emptyList(),
        holidays: List<DayHoliday> = emptyList(),
        makeups: List<DayMakeup> = emptyList()
    ) = Schedule(
        id = "s1",
        name = "测试课表",
        school = "",
        semesterStart = "2026-09-07",
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
        adjustments = adjustments,
        holidays = holidays,
        makeups = makeups
    )

    @Test
    fun autoModeOnlyExpandsTheWeekThatHasWeekendCourse() {
        val value = schedule()
        assertEquals(5, WeekendDisplay.dayCount(value, 2, WeekendDisplayMode.AUTO))
        assertEquals(7, WeekendDisplay.dayCount(value, 3, WeekendDisplayMode.AUTO))
        assertEquals(5, WeekendDisplay.dayCount(value, 4, WeekendDisplayMode.AUTO))
    }

    @Test
    fun alwaysModeIgnoresCourseDistribution() {
        val value = schedule()
        assertEquals(7, WeekendDisplay.dayCount(value, 2, WeekendDisplayMode.ALWAYS))
        assertEquals(7, WeekendDisplay.dayCount(value, 3, WeekendDisplayMode.ALWAYS))
        assertEquals(7, WeekendDisplay.dayCount(value, 5, WeekendDisplayMode.ALWAYS))
    }

    @Test
    fun autoModeRecognizesWeekendAdjustmentAndCalendarExceptionsByWeek() {
        val weekdayCourse = weekendCourse.copy(name = "工作日课程", day = 1, weeks = listOf(1, 2, 3, 4))
        val adjustment = CourseAdjustment(
            id = "a1",
            courseId = weekdayCourse.id,
            sourceWeek = 2,
            sourceDay = 1,
            sourceStartSection = 1,
            sourceEndSection = 2,
            targetWeek = 2,
            targetDay = 7,
            targetStartSection = 1,
            targetEndSection = 2,
            targetPosition = null,
            courseSnapshot = weekdayCourse,
            createdAt = "",
            updatedAt = ""
        )
        val value = schedule(
            courses = listOf(weekdayCourse),
            adjustments = listOf(adjustment),
            holidays = listOf(DayHoliday("h1", week = 3, day = 6, createdAt = "", updatedAt = "")),
            makeups = listOf(DayMakeup("m1", 4, 7, 5, 1, "", ""))
        )

        assertEquals(5, WeekendDisplay.dayCount(value, 1, WeekendDisplayMode.AUTO))
        assertEquals(7, WeekendDisplay.dayCount(value, 2, WeekendDisplayMode.AUTO))
        assertEquals(7, WeekendDisplay.dayCount(value, 3, WeekendDisplayMode.AUTO))
        assertEquals(7, WeekendDisplay.dayCount(value, 4, WeekendDisplayMode.AUTO))
        assertEquals(5, WeekendDisplay.dayCount(value, 5, WeekendDisplayMode.AUTO))
    }
}
