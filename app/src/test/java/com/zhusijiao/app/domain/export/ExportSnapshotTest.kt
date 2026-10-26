package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.LeaveType
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.WeekendDisplayMode
import com.zhusijiao.app.domain.export.ExportFixtures.adjustMath
import com.zhusijiao.app.domain.export.ExportFixtures.event
import com.zhusijiao.app.domain.export.ExportFixtures.extra
import com.zhusijiao.app.domain.export.ExportFixtures.holiday
import com.zhusijiao.app.domain.export.ExportFixtures.leave
import com.zhusijiao.app.domain.export.ExportFixtures.makeup
import com.zhusijiao.app.domain.export.ExportFixtures.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSnapshotTest {

    private fun build(
        s: com.zhusijiao.app.domain.Schedule = schedule(),
        extras: List<com.zhusijiao.app.domain.ExtraCourse> = emptyList(),
        events: List<com.zhusijiao.app.domain.PersonalEvent> = emptyList(),
        leaves: List<com.zhusijiao.app.domain.Leave> = emptyList(),
        mode: WeekendDisplayMode = WeekendDisplayMode.AUTO,
        options: ExportOptions = ExportOptions()
    ) = ExportSnapshot.build(s, extras, events, leaves, mode, options)

    @Test
    fun expandsEveryWeekWithDates() {
        val snapshot = build()
        assertEquals(16, snapshot.weeks.size)
        assertTrue(snapshot.hasDates)
        val week1 = snapshot.weeks[0]
        assertEquals("2026-09-07", week1.days[0].dateIso)
        assertEquals(9, week1.days[0].month)
        assertEquals(7, week1.days[0].dayOfMonth)
        assertEquals(listOf("高等数学", "大学英语"), week1.items.map { it.title })
        assertEquals("07:50", week1.items[0].startTime)
        assertEquals("09:30", week1.items[0].endTime)
        // 至少显示 8 节
        assertEquals(8, snapshot.sections.size)
    }

    @Test
    fun weekRangeIsClampedToSemester() {
        val snapshot = build(options = ExportOptions(weeks = (15..30).toList()))
        assertEquals(listOf(15, 16), snapshot.weeks.map { it.week })
    }

    @Test
    fun weekendColumnsFollowAppSetting() {
        val auto = build(options = ExportOptions(weeks = (1..2).toList()))
        assertEquals(5, auto.weeks[0].days.size)
        assertEquals(7, auto.weeks[1].days.size) // 第 2 周周六有体育
        val always = build(mode = WeekendDisplayMode.ALWAYS, options = ExportOptions(weeks = (1..1).toList()))
        assertEquals(7, always.weeks[0].days.size)
    }

    @Test
    fun weekendEventOpensWeekendColumnsOnlyWhenEventsIncluded() {
        val events = listOf(event("社团", day = 7, start = 5, end = 6, weeks = listOf(1)))
        assertEquals(7, build(events = events, options = ExportOptions(weeks = (1..1).toList())).weeks[0].days.size)
        assertEquals(
            5,
            build(events = events, options = ExportOptions(weeks = (1..1).toList(), includeEvents = false)).weeks[0].days.size
        )
    }

    @Test
    fun adjustmentsHolidaysAndMakeupsAreTagged() {
        val s = schedule(
            adjustments = listOf(adjustMath(3)),
            holidays = listOf(holiday(4, 3)),
            makeups = listOf(makeup(sourceWeek = 5, sourceDay = 1, targetWeek = 5, targetDay = 6))
        )
        val snapshot = build(s)
        val week3 = snapshot.weeks[2]
        val adjusted = week3.items.single { it.title == "高等数学" }
        assertEquals(ExportSnapshot.Kind.ADJUSTED, adjusted.kind)
        assertEquals(2, adjusted.day)
        assertEquals("B202", adjusted.position)

        val week4 = snapshot.weeks[3]
        assertTrue(week4.days[2].suspended)
        assertTrue(week4.items.none { it.title == "大学英语" })

        val week5 = snapshot.weeks[4]
        assertEquals(7, week5.days.size)
        assertEquals(1, week5.days[0].movedTo?.sourceDay)
        assertEquals(1, week5.days[5].makeupOf?.sourceDay)
        val moved = week5.items.single { it.title == "高等数学" }
        assertEquals(ExportSnapshot.Kind.MAKEUP, moved.kind)
        assertEquals(6, moved.day)
    }

    @Test
    fun leavesAreMarkedOnlyWhenAsked() {
        val leaves = listOf(leave("2026-09-07 08:00", "2026-09-07 08:30", type = LeaveType.OFFICIAL))
        assertNull(build(leaves = leaves).weeks[0].items[0].leave)
        val marked = build(leaves = leaves, options = ExportOptions(markLeaves = true)).weeks[0]
        assertEquals(LeaveType.OFFICIAL, marked.items.single { it.title == "高等数学" }.leave)
        assertNull(marked.items.single { it.title == "大学英语" }.leave)
    }

    @Test
    fun extrasToggleKeepsOtherCourseColorsStable() {
        val extras = listOf(extra("物理实验", day = 2, start = 5, end = 6, color = "#123456"))
        val withExtras = build(extras = extras).weeks[0]
        val without = build(extras = extras, options = ExportOptions(includeExtras = false)).weeks[0]
        val extraItem = withExtras.items.single { it.title == "物理实验" }
        assertEquals(ScheduleView.manualPalette("#123456")!!.background, extraItem.tone)
        assertTrue(without.items.none { it.title == "物理实验" })
        assertEquals(
            withExtras.items.single { it.title == "高等数学" }.tone,
            without.items.single { it.title == "高等数学" }.tone
        )
    }

    @Test
    fun eventsCarryCustomTimeAndDefaultColor() {
        val events = listOf(
            event("社团", day = 2, start = 9, end = 10, startTime = "19:00", endTime = "20:30"),
            event("自习", day = 4, start = 1, end = 2, id = "event-2")
        )
        val snapshot = build(events = events)
        val week = snapshot.weeks[0]
        val club = week.items.single { it.title == "社团" }
        assertTrue(club.isEvent)
        assertTrue(club.customTime)
        assertEquals("19:00", club.startTime)
        assertEquals(ExportSnapshot.DEFAULT_EVENT_COLOR, club.tone)
        val study = week.items.single { it.title == "自习" }
        assertFalse(study.customTime)
        assertEquals("07:50", study.startTime)
        assertEquals(10, snapshot.sections.size) // 日程用到第 10 节
        assertTrue(build(events = events, options = ExportOptions(includeEvents = false)).weeks[0].items.none { it.isEvent })
    }

    @Test
    fun uidsAreUniqueAndStable() {
        val first = build().weeks.flatMap { w -> w.items.map { it.uid } }
        val second = build().weeks.flatMap { w -> w.items.map { it.uid } }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
    }

    @Test
    fun noSemesterStartMeansNoDates() {
        val snapshot = build(schedule(semesterStart = ""))
        assertFalse(snapshot.hasDates)
        assertNull(snapshot.weeks[0].days[0].dateIso)
    }
}
