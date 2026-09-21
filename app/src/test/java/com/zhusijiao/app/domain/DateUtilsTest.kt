package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** 教学周推算测试：重点覆盖「跨周当天上午仍显示上一周」的回归。 */
class DateUtilsTest {

    /** 2026-09-07 是周一，即第 1 周第 1 天；第三周周一为 2026-09-21。 */
    private val semesterStart = "2026-09-07"

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `开学当天上午算第1周`() {
        assertEquals(1, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 7, 8, 30)))
    }

    @Test
    fun `第1周周日深夜仍是第1周`() {
        assertEquals(1, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 13, 23, 59)))
    }

    @Test
    fun `第二周周一凌晨0点30就是第2周`() {
        assertEquals(2, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 14, 0, 30)))
    }

    @Test
    fun `跨周周一上午就应进入第3周（用户报告的场景）`() {
        // 修复前：上午 12 点前打开 App 会显示第 2 周
        assertEquals(3, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 21, 8, 0)))
        assertEquals(3, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 21, 0, 1)))
        assertEquals(3, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 21, 23, 0)))
    }

    @Test
    fun `开学前的日期下限为第1周`() {
        assertEquals(1, DateUtils.currentWeek(semesterStart, 20, at(2026, 8, 31, 12, 0)))
    }

    @Test
    fun `超出总周数时上限为最后一周`() {
        assertEquals(20, DateUtils.currentWeek(semesterStart, 20, at(2027, 1, 25, 10, 0)))
    }

    @Test
    fun `开学日期非法时按今天推算不崩溃`() {
        assertEquals(1, DateUtils.currentWeek(null, 20, at(2026, 9, 7, 8, 0)))
    }

    // ===== 开学日期不是周一（用户反馈：调休日历把 10 月 10 日标成周一，它其实是周六）=====

    /** 2026-09-12 是周六，所在周的周一是 2026-09-07。 */
    private val saturdayStart = "2026-09-12"

    @Test
    fun `开学日期不是周一时回退到那一周的周一`() {
        assertEquals("2026-09-07", DateUtils.weekStartOf(saturdayStart))
        assertEquals("2026-09-07", DateUtils.weekStartOf("2026-09-13")) // 周日
        assertEquals("2026-09-07", DateUtils.weekStartOf("2026-09-07")) // 周一本身不动
    }

    @Test
    fun `每周第1天必须是真实的周一`() {
        // 修复前：第 1 天直接取开学日期，于是「周一」栏里放的是 9-12 这个周六
        assertEquals("2026-09-07", DateUtils.datesForWeek(saturdayStart, 1).first().iso)
        assertEquals("2026-09-13", DateUtils.datesForWeek(saturdayStart, 1).last().iso)
        assertEquals("2026-10-05", DateUtils.datesForWeek(saturdayStart, 5).first().iso)
    }

    @Test
    fun `用户报告的日期落在正确的星期上`() {
        // 2026-10-10 是周六：必须出现在第 5 周的第 6 格（周六），而不是第 1 格（周一）
        val week5 = DateUtils.datesForWeek(saturdayStart, 5)
        assertEquals("2026-10-10", week5[5].iso)
        assertEquals("六", week5[5].name)
        assertEquals("一", week5[0].name)
        assertEquals("2026-10-05", week5[0].iso)
    }

    @Test
    fun `非周一开学时教学周仍按周一切换`() {
        assertEquals(1, DateUtils.currentWeek(saturdayStart, 20, at(2026, 9, 12, 10, 0)))
        assertEquals(1, DateUtils.currentWeek(saturdayStart, 20, at(2026, 9, 13, 23, 0)))
        // 9-14 是周一，进入第 2 周
        assertEquals(2, DateUtils.currentWeek(saturdayStart, 20, at(2026, 9, 14, 0, 30)))
        assertEquals(5, DateUtils.currentWeek(saturdayStart, 20, at(2026, 10, 10, 9, 0)))
    }

    @Test
    fun `开学日期本就是周一时推算不变`() {
        assertEquals(3, DateUtils.currentWeek(semesterStart, 20, at(2026, 9, 21, 8, 0)))
        assertEquals("2026-09-21", DateUtils.datesForWeek(semesterStart, 3).first().iso)
        assertEquals(semesterStart, DateUtils.weekStartOf(semesterStart))
    }

    @Test
    fun `isMonday 只认周一`() {
        assertEquals(true, DateUtils.isMonday("2026-09-07"))
        assertEquals(false, DateUtils.isMonday("2026-09-12"))
        assertEquals(false, DateUtils.isMonday(""))
    }
}