package com.zhusijiao.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 停课与日程的样式：默认新版，选了经典就不算默认（「恢复默认」可点）。 */
class TimetableAppearanceStateStyleTest {

    @Test
    fun `默认是新版样式`() {
        assertTrue(TimetableAppearance.DEFAULT.modernStateStyle)
        assertTrue(TimetableAppearance.DEFAULT.isDefault)
    }

    @Test
    fun `选经典样式后不再是默认`() {
        val classic = TimetableAppearance.DEFAULT.copy(stateStyle = TimetableAppearance.STATE_STYLE_CLASSIC)
        assertFalse(classic.modernStateStyle)
        assertFalse(classic.isDefault)
    }
}
