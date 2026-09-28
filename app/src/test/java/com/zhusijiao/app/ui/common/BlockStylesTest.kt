package com.zhusijiao.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** 新版状态样式的混色：朝白色、墨色、灰色按比例混合，结果不透明。 */
class BlockStylesTest {

    @Test
    fun `黑白各半得到中灰`() {
        assertEquals(0xFF808080.toInt(), BlockStyles.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun `比例为零保持原色`() {
        assertEquals(0xFF2F7D68.toInt(), BlockStyles.blend(0xFF2F7D68.toInt(), 0xFFFFFFFF.toInt(), 0f))
    }

    @Test
    fun `日程浅底是日程色朝白色混86%`() {
        // 主色 #2F7D68 朝白色混 86%
        assertEquals(0xFFE2EDEA.toInt(), BlockStyles.eventFill(BlockStyles.DEFAULT_EVENT_COLOR))
    }

    @Test
    fun `日程描边就是日程色`() {
        assertEquals(BlockStyles.DEFAULT_EVENT_COLOR, BlockStyles.eventStroke(BlockStyles.DEFAULT_EVENT_COLOR))
    }
}
