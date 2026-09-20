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
}