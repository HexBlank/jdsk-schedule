package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 课表「现在」时间线的纵向位置。 */
class ScheduleTimeRowPositionTest {

    private val slots = listOf(
        TimeSlot(1, "07:50", "08:35"),
        TimeSlot(2, "08:45", "09:30"),
        TimeSlot(3, "09:50", "10:35")
    )

    private fun at(hm: String) = ScheduleTime.minutesOf(hm)!!

    @Test
    fun `第一节上课前不画`() = assertNull(ScheduleTime.rowPosition(slots, at("07:49")))

    @Test
    fun `上课那一刻在该节顶部`() = assertEquals(0f, ScheduleTime.rowPosition(slots, at("07:50"))!!, 0.001f)

    @Test
    fun `上课中按节内进度`() = assertEquals(1f / 3f, ScheduleTime.rowPosition(slots, at("08:05"))!!, 0.001f)

    @Test
    fun `课间落在下一节顶部`() = assertEquals(2f, ScheduleTime.rowPosition(slots, at("09:40"))!!, 0.001f)

    @Test
    fun `最后一节下课那一刻在表底`() = assertEquals(3f, ScheduleTime.rowPosition(slots, at("10:35"))!!, 0.001f)

    @Test
    fun `最后一节下课后不画`() = assertNull(ScheduleTime.rowPosition(slots, at("10:36")))

    @Test
    fun `没有作息不画`() = assertNull(ScheduleTime.rowPosition(emptyList(), at("09:00")))
}
