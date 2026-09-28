package com.zhusijiao.app.ui.common

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 课程块「新版」状态样式的配色，周课表、情侣日视图和外观面板的预览共用，保证三处画法一致。
 *
 * 一种形态只表达一件事：实心 = 今天要上的课；浅底加实线 = 自己的日程；
 * 空心加虚线、课程名划删除线 = 本来有、今天不上（停课、已调出、已补课）。
 * 颜色只负责「是哪一门」，所以停课的块保留课程色，只是变淡。
 */
object BlockStyles {

    /** 新版里日程没有自选颜色时的默认色：主色墨绿（浅底后的冷灰蓝会和「已上」的灰块混在一起）。 */
    const val DEFAULT_EVENT_COLOR = 0xFF2F7D68.toInt()

    private const val INK = 0xFF1D2421.toInt()
    private const val GREY = 0xFF8A9490.toInt()

    /** 日程：浅底（日程色 14%）。 */
    fun eventFill(tone: Int): Int = blend(tone, Color.WHITE, 0.86f)

    /** 日程：实线描边用日程色本身。 */
    fun eventStroke(tone: Int): Int = tone

    /** 日程：文字向墨色加深，浅色日程上也够清楚。 */
    fun eventText(tone: Int): Int = blend(tone, INK, 0.45f)

    /** 今天不上：虚线描边用课程色 60%。 */
    fun ghostStroke(tone: Int): Int = blend(tone, Color.WHITE, 0.4f)

    /** 今天不上：文字取课程色偏灰，看得出是哪门课，又明显退后一层。 */
    fun ghostText(tone: Int): Int = blend(tone, GREY, 0.55f)

    /** 浅色块上的角标底色。 */
    fun badgeFill(tone: Int): Int = blend(tone, Color.WHITE, 0.8f)

    fun blend(color: Int, toward: Int, amount: Float): Int {
        fun mix(shift: Int) = ((color shr shift and 0xff) * (1f - amount) + (toward shr shift and 0xff) * amount)
            .roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
