package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidayMakeupTest {

    private val course = Course(
        name = "数据结构",
        teacher = "陈老师",
        position = "A201",
        day = 1,
        startSection = 1,
        endSection = 2,
        weeks = listOf(3)
    )

    private fun schedule(
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
        courseCount = 1,
        timeSlots = emptyList(),
        courses = listOf(course),
        holidays = holidays,
        makeups = makeups
    )

    private fun holiday(week: Int = 5, day: Int = 2, id: String = "h1") = DayHoliday(
        id = id, week = week, day = day, createdAt = "", updatedAt = ""
    )

    private fun makeup(
        sourceWeek: Int = 3,
        sourceDay: Int = 1,
        targetWeek: Int = 2,
        targetDay: Int = 6,
        id: String = "m1"
    ) = DayMakeup(
        id = id,
        sourceWeek = sourceWeek,
        sourceDay = sourceDay,
        targetWeek = targetWeek,
        targetDay = targetDay,
        createdAt = "",
        updatedAt = ""
    )

    @Test
    fun defaultScheduleHasNoCalendarExceptions() {
        val s = schedule()
        assertTrue(s.holidays.isEmpty())
        assertTrue(s.makeups.isEmpty())
        assertTrue(s.isOwner)
    }

    @Test(expected = IllegalArgumentException::class)
    fun holidayWeekOutOfRangeIsRejected() {
        ScheduleValidator.validateHoliday(schedule(), DayHolidayDraft(week = 21, day = 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun holidayDayInvalidIsRejected() {
        ScheduleValidator.validateHoliday(schedule(), DayHolidayDraft(week = 1, day = 8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateHolidayIsRejected() {
        ScheduleValidator.validateHoliday(
            schedule(holidays = listOf(holiday())),
            DayHolidayDraft(week = 5, day = 2)
        )
    }

    @Test
    fun editingSameHolidayKeptByReplacingId() {
        ScheduleValidator.validateHoliday(
            schedule(holidays = listOf(holiday())),
            DayHolidayDraft(week = 5, day = 2),
            replacingId = "h1"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun holidayOnMakeupTargetDayIsRejected() {
        ScheduleValidator.validateHoliday(
            schedule(makeups = listOf(makeup())),
            DayHolidayDraft(week = 2, day = 6)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun makeupSameSourceAndTargetIsRejected() {
        ScheduleValidator.validateMakeup(
            schedule(),
            DayMakeupDraft(sourceWeek = 3, sourceDay = 1, targetWeek = 3, targetDay = 1)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun makeupOutOfRangeIsRejected() {
        ScheduleValidator.validateMakeup(
            schedule(),
            DayMakeupDraft(sourceWeek = 3, sourceDay = 1, targetWeek = 99, targetDay = 6)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateMakeupTargetIsRejected() {
        ScheduleValidator.validateMakeup(
            schedule(makeups = listOf(makeup())),
            DayMakeupDraft(sourceWeek = 4, sourceDay = 1, targetWeek = 2, targetDay = 6)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun makeupOnHolidayDayIsRejected() {
        ScheduleValidator.validateMakeup(
            schedule(holidays = listOf(holiday(week = 2, day = 6))),
            DayMakeupDraft(sourceWeek = 3, sourceDay = 1, targetWeek = 2, targetDay = 6)
        )
    }

    @Test
    fun makeupIntoHolidayFreeWeekendIsAllowed() {
        // 典型调休：停课日的来源周（第5周周二）可以照常作为补课来源
        ScheduleValidator.validateMakeup(
            schedule(holidays = listOf(holiday(week = 5, day = 2))),
            DayMakeupDraft(sourceWeek = 5, sourceDay = 2, targetWeek = 2, targetDay = 7)
        )
    }

    @Test
    fun crossWeekMakeupDoesNotChangeBaseCourse() {
        val s = schedule()
        ScheduleValidator.validateMakeup(s, DayMakeupDraft(3, 1, 2, 6))
        assertEquals(listOf(3), s.courses.first().weeks)
        assertEquals(1, s.courses.first().day)
    }

    @Test
    fun rangeSpansWeeksInEitherDirectionAndSkipsExistingArrangements() {
        val state = schedule(holidays = listOf(holiday(1, 7)), makeups = listOf(makeup(targetWeek = 2, targetDay = 2)))
        val forward = HolidayPlanner.range(state, 5, 11)
        assertEquals(5, forward.size)
        assertEquals(forward, HolidayPlanner.range(state, 11, 5))
        assertEquals(DayHolidayDraft(1, 6), forward.first())
        assertEquals(DayHolidayDraft(2, 5), forward.last())
        assertTrue(forward.none { it.week == 1 && it.day == 7 || it.week == 2 && it.day == 2 })
    }

    @Test
    fun rangeRejectsOutOfSemesterAndSupportsSingleDay() {
        assertTrue(HolidayPlanner.range(schedule(), 0, 140).isEmpty())
        assertTrue(HolidayPlanner.range(schedule(), -1, 3).isEmpty())
        assertEquals(listOf(DayHolidayDraft(20, 7)), HolidayPlanner.range(schedule(), 139, 139))
    }

    @Test
    fun consecutiveHolidaysMergeAcrossWeekBoundary() {
        val days = listOf(holiday(2, 1, "b"), holiday(1, 7, "a"), holiday(2, 3, "c"))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), HolidayPlanner.groups(days).map { group -> group.map { it.id } })
    }

    @Test
    fun emptyTargetListsOnlySuspendedDaysWithCoursesStillAwaitingMakeup() {
        val suspended = holiday(3, 1)
        val state = schedule(holidays = listOf(suspended, holiday(3, 2, "empty")))
        assertEquals(listOf(suspended), HolidayPlanner.makeupSources(state, HolidayPlanner.index(2, 6)))
        assertTrue(HolidayPlanner.makeupSources(state, HolidayPlanner.index(3, 1)).isEmpty())
        assertTrue(HolidayPlanner.makeupSources(state.copy(makeups = listOf(makeup())), HolidayPlanner.index(2, 7)).isEmpty())
    }

    @Test
    fun occupiedTargetDoesNotOfferMakeupSources() {
        val state = schedule(holidays = listOf(holiday(4, 1))).copy(
            courses = listOf(course, course.copy(weeks = listOf(4)))
        )
        assertTrue(HolidayPlanner.makeupSources(state, HolidayPlanner.index(3, 1)).isEmpty())
    }

    @Test
    fun groupDeleteAndUndoPreserveIdsAndUnrelatedChanges() {
        val first = holiday(1, 7, "a")
        val second = holiday(2, 1, "b")
        val original = schedule(holidays = listOf(first, second), makeups = listOf(makeup()))
        val deleted = CalendarEdit(removeHolidayIds = setOf("a", "b")).applyTo(original)
        assertTrue(deleted.holidays.isEmpty())
        assertEquals(original.makeups, deleted.makeups)
        val unrelated = holiday(6, 1, "other")
        val restored = CalendarEdit(restoreHolidays = listOf(first, second)).applyTo(deleted.copy(holidays = listOf(unrelated)))
        assertEquals(listOf(unrelated, first, second), restored.holidays)
        assertEquals(2, original.holidays.size)
    }

    @Test
    fun makeupDeleteAndUndoRestoreOriginalRecord() {
        val entry = makeup()
        val original = schedule(makeups = listOf(entry))
        val deleted = CalendarEdit(removeMakeupIds = setOf(entry.id)).applyTo(original)
        assertTrue(deleted.makeups.isEmpty())
        assertEquals(original, CalendarEdit(restoreMakeups = listOf(entry)).applyTo(deleted))
    }

    @Test(expected = IllegalArgumentException::class)
    fun groupDeleteRejectsMissingRecordBeforeChangingAnything() {
        CalendarEdit(removeHolidayIds = setOf("h1", "missing")).applyTo(schedule(holidays = listOf(holiday())))
    }

    @Test
    fun undoConflictLeavesOriginalStateIntact() {
        val original = schedule(makeups = listOf(makeup()))
        val undo = CalendarEdit(restoreHolidays = listOf(holiday(1, 1, "safe"), holiday(2, 6, "conflict")))
        assertTrue(runCatching { undo.applyTo(original) }.isFailure)
        assertTrue(original.holidays.isEmpty())
        assertEquals(1, original.makeups.size)
    }

    @Test
    fun makeupSourcesIncludeRegularDatesWithoutPriorSuspension() {
        val source = HolidayPlanner.index(3, 1)
        assertEquals(setOf(source), HolidayPlanner.makeupSourceDays(schedule(), HolidayPlanner.index(2, 6)))
        assertTrue(schedule().holidays.isEmpty())
    }

    @Test
    fun suspendedSourceRetainsItsOriginalCourses() {
        val state = schedule(holidays = listOf(holiday(3, 1)))
        assertEquals(listOf(course), HolidayPlanner.sourceCourses(state, HolidayPlanner.index(3, 1)))
        assertTrue(HolidayPlanner.index(3, 1) in HolidayPlanner.makeupSourceDays(state, HolidayPlanner.index(2, 6)))
    }

    @Test
    fun targetWithExistingCoursesStillUsesOriginalValidationRules() {
        val state = schedule().copy(courses = listOf(course, course.copy(weeks = listOf(4))))
        assertTrue(HolidayPlanner.index(4, 1) in HolidayPlanner.makeupSourceDays(state, HolidayPlanner.index(3, 1)))
    }

    @Test
    fun sourceOptionsExcludeSameDayAndAlreadyMovedCourses() {
        val source = HolidayPlanner.index(3, 1)
        assertTrue(source !in HolidayPlanner.makeupSourceDays(schedule(), source))
        assertTrue(source !in HolidayPlanner.makeupSourceDays(schedule(makeups = listOf(makeup())), HolidayPlanner.index(4, 6)))
        assertTrue(HolidayPlanner.sourceCourses(schedule(), -1).isEmpty())
    }

    @Test
    fun targetsExcludeSuspendedAndAlreadyAssignedDays() {
        val state = schedule(holidays = listOf(holiday(2, 7)), makeups = listOf(makeup()))
        val targets = HolidayPlanner.makeupTargetDays(state)
        assertTrue(HolidayPlanner.index(2, 7) !in targets)
        assertTrue(HolidayPlanner.index(2, 6) !in targets)
        assertTrue(HolidayPlanner.makeupSourceDays(state, HolidayPlanner.index(2, 7)).isEmpty())
        assertTrue(HolidayPlanner.makeupSourceDays(state, 140).isEmpty())
    }

    @Test
    fun sourcePreviewDoesNotRecursivelyCopyAnotherMakeupDay() {
        val state = schedule(makeups = listOf(makeup()))
        assertTrue(HolidayPlanner.sourceCourses(state, HolidayPlanner.index(2, 6)).isEmpty())
    }

    @Test
    fun changingEitherDateKeepsSuspendRangeValid() {
        val range = HolidayDateRange(5, 11)
        assertEquals(HolidayDateRange(12, 12), range.startingAt(12))
        assertEquals(HolidayDateRange(3, 3), range.endingAt(3))
        assertEquals(HolidayDateRange(6, 11), range.startingAt(6))
        assertEquals(HolidayDateRange(5, 10), range.endingAt(10))
    }
}