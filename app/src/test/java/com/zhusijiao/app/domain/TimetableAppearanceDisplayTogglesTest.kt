package com.zhusijiao.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「当前时间线」「下一节课提示条」两个显示开关：默认都开，关掉任何一个就不算默认（「恢复默认」可点）。 */
class TimetableAppearanceDisplayTogglesTest {

    @Test
    fun `默认都开启`() {
        assertTrue(TimetableAppearance.DEFAULT.showNowLine)
        assertTrue(TimetableAppearance.DEFAULT.showNextClass)
        assertTrue(TimetableAppearance.DEFAULT.isDefault)
    }

    @Test
    fun `关掉当前时间线后不再是默认`() {
        val off = TimetableAppearance.DEFAULT.copy(showNowLine = false)
        assertFalse(off.showNowLine)
        assertTrue(off.showNextClass)
        assertFalse(off.isDefault)
    }

    @Test
    fun `关掉下一节课提示后不再是默认`() {
        val off = TimetableAppearance.DEFAULT.copy(showNextClass = false)
        assertTrue(off.showNowLine)
        assertFalse(off.showNextClass)
        assertFalse(off.isDefault)
    }
}
