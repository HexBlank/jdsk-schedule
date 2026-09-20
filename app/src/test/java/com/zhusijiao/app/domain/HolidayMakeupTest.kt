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
}