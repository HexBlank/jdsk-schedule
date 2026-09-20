package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseAdjustmentTest {

    private val course = Course(
        name = "数据结构",
        teacher = "陈老师",
        position = "A201",
        day = 4,
        startSection = 1,
        endSection = 2,
        weeks = listOf(4, 5, 6)
    )

    @Test
    fun stableIdMatchesServerAlgorithmAndIgnoresWeeks() {
        assertEquals("course-3fad440d7afe9150421778df", course.id)
        assertEquals(course.id, course.copy(weeks = listOf(9)).id)
    }

    @Test
    fun crossWeekAdjustmentBuildsTargetOccurrenceWithoutChangingBase() {
        val adjustment = CourseAdjustment(
            id = "a1",
            courseId = course.id,
            sourceWeek = 4,
            sourceDay = 4,
            sourceStartSection = 1,
            sourceEndSection = 2,
            targetWeek = 5,
            targetDay = 3,
            targetStartSection = 3,
            targetEndSection = 4,
            targetPosition = "B302",
            courseSnapshot = course,
            createdAt = "",
            updatedAt = ""
        )

        val target = adjustment.targetCourse(course)
        assertEquals(listOf(5), target.weeks)
        assertEquals(3, target.day)
        assertEquals(3, target.startSection)
        assertEquals(4, target.endSection)
        assertEquals("B302", target.position)
        assertEquals(listOf(4, 5, 6), course.weeks)
    }

    @Test
    fun conflictDetectionWarnsForOtherCourseButExcludesMovedSource() {
        val collision = course.copy(
            name = "高等数学",
            teacher = "李老师",
            day = 3,
            startSection = 3,
            endSection = 4,
            weeks = listOf(5)
        )
        val schedule = schedule(listOf(course, collision))
        val draft = draft()

        assertEquals(listOf("高等数学"), ScheduleValidator.conflicts(schedule, draft).map { it.name })
        assertTrue(ScheduleValidator.conflicts(schedule, draft.copy(targetDay = 2)).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateSourceAdjustmentIsRejected() {
        val draft = draft()
        val adjustment = CourseAdjustment(
            id = "a1",
            courseId = course.id,
            sourceWeek = 4,
            sourceDay = 4,
            sourceStartSection = 1,
            sourceEndSection = 2,
            targetWeek = 5,
            targetDay = 3,
            targetStartSection = 3,
            targetEndSection = 4,
            targetPosition = null,
            courseSnapshot = course,
            createdAt = "",
            updatedAt = ""
        )
        ScheduleValidator.validateAdjustment(schedule(listOf(course)).copy(adjustments = listOf(adjustment)), draft)
    }

    private fun draft() = CourseAdjustmentDraft(
        courseId = course.id,
        sourceWeek = 4,
        sourceDay = 4,
        sourceStartSection = 1,
        sourceEndSection = 2,
        targetWeek = 5,
        targetDay = 3,
        targetStartSection = 3,
        targetEndSection = 4
    )

    private fun schedule(courses: List<Course>) = Schedule(
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
        courses = courses
    )
}
