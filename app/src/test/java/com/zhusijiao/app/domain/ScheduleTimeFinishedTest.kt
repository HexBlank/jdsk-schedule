package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 「已上」起算时刻：下课那一分钟过完才算已上。 */
class ScheduleTimeFinishedTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    @Test
    fun `0930下课的课0931起才算已上`() {
        val finishedAt = ScheduleTime.finishedAtMillis("2026-09-23", "09:30")!!
        assertEquals(at(2026, 9, 23, 9, 31), finishedAt)
        assertTrue(at(2026, 9, 23, 9, 30, 59) < finishedAt)
    }

    @Test
    fun `2359下课跨到次日0点`() {
        assertEquals(at(2026, 9, 24, 0, 0), ScheduleTime.finishedAtMillis("2026-09-23", "23:59"))
    }

    @Test
    fun `日期或时间非法不标已上`() {
        assertNull(ScheduleTime.finishedAtMillis("2026-09", "09:30"))
        assertNull(ScheduleTime.finishedAtMillis("2026-09-23", ""))
        assertNull(ScheduleTime.finishedAtMillis(null, "09:30"))
    }
}
