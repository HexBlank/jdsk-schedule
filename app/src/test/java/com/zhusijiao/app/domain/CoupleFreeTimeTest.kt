package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleFreeTimeTest {

    private fun t(value: String) = ScheduleTime.minutesOf(value)!!
    private fun span(start: String, end: String) = CoupleFreeTime.Span(t(start), t(end))

    // 韩师作息：第一节 07:50 上课，第十二节 21:50 下课
    private val dayStart = t("07:50")
    private val dayEnd = t("21:50")

    @Test
    fun `午休和晚饭算都有空，课间十来分钟不算`() {
        val busy = listOf(
            span("07:50", "09:30"), span("14:00", "15:40"), span("19:15", "20:55"), // 我
            span("09:50", "11:30"), span("14:00", "15:40"), span("16:00", "17:40")  // TA
        )
        val gaps = CoupleFreeTime.gaps(busy, dayStart, dayEnd)
        assertEquals(
            listOf("都有空 2小时30分", "都有空 1小时35分", "20:55 后都有空"),
            gaps.map(CoupleFreeTime::label)
        )
        assertEquals(t("11:30"), gaps[0].start)
        assertEquals(t("14:00"), gaps[0].end)
    }

    @Test
    fun `一天开头的空闲写几点前都有空`() {
        val gaps = CoupleFreeTime.gaps(listOf(span("09:50", "11:30")), dayStart, dayEnd)
        assertEquals("09:50 前都有空", CoupleFreeTime.label(gaps.first()))
        assertTrue(gaps.first().atDayStart)
    }

    @Test
    fun `一整天都没有占用时是全天都有空`() {
        val gaps = CoupleFreeTime.gaps(emptyList(), dayStart, dayEnd)
        assertEquals(listOf("全天都有空"), gaps.map(CoupleFreeTime::label))
    }

    @Test
    fun `重叠和乱序的占用按合并后计算`() {
        val busy = listOf(span("10:00", "12:00"), span("07:50", "10:30"), span("11:00", "11:30"))
        val gaps = CoupleFreeTime.gaps(busy, dayStart, t("14:00"))
        assertEquals(listOf(CoupleFreeTime.Gap(t("12:00"), t("14:00"), atDayStart = false, atDayEnd = true)), gaps)
    }

    @Test
    fun `恰好 45 分钟算，44 分钟不算`() {
        val exact = CoupleFreeTime.gaps(listOf(span("07:50", "09:00"), span("09:45", "21:50")), dayStart, dayEnd)
        assertEquals(1, exact.size)
        val short = CoupleFreeTime.gaps(listOf(span("07:50", "09:00"), span("09:44", "21:50")), dayStart, dayEnd)
        assertTrue(short.isEmpty())
    }

    @Test
    fun `作息范围外的占用被裁掉`() {
        val gaps = CoupleFreeTime.gaps(listOf(span("06:00", "08:30"), span("21:00", "23:00")), dayStart, dayEnd)
        assertEquals(listOf("都有空 12小时30分"), gaps.map(CoupleFreeTime::label))
    }

    @Test
    fun `时长文案`() {
        assertEquals("2小时30分", CoupleFreeTime.duration(150))
        assertEquals("3小时", CoupleFreeTime.duration(180))
        assertEquals("55分钟", CoupleFreeTime.duration(55))
    }

    @Test
    fun `周视图按节次找连续的都有空`() {
        assertEquals(listOf(3..5, 8..12), CoupleFreeTime.freeSectionRuns(setOf(1, 2, 6, 7)))
        assertEquals(listOf(1..12), CoupleFreeTime.freeSectionRuns(emptySet()))
        assertTrue(CoupleFreeTime.freeSectionRuns((1..12).toSet()).isEmpty())
    }
}
