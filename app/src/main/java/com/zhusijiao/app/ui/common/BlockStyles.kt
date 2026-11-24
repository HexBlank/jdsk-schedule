package com.zhusijiao.app.ui.common

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 课程块与日程的语义配色，周课表、今日视图、详情抽屉和外观预览共用，保证全站色彩语言一致。
 *
 * 清透柔色风（默认）：所有课程统一为低饱和柔和浅底 + 同色系深邃高对比文字 + 左侧高饱和强调竖条，
 * 消除高饱和深色与粉嫩浅色混排的割裂感，同时保持高辨识度与可读性。
 */
object BlockStyles {

    /** 新版里日程没有自选颜色时的默认色：主色墨绿（浅底后的冷灰蓝会和「已上」的灰块混在一起）。 */
    const val DEFAULT_EVENT_COLOR = 0xFF2F7D68.toInt()

    private const val INK = 0xFF1D2421.toInt()
    private const val GREY = 0xFF8A9490.toInt()

    /** 清透柔色底：根据原色明度自适应混白，保证全表所有课程块明度协调统一。 */
    fun courseSoftFill(tone: Int): Int {
        val light = isLightColor(tone)
        return blend(tone, Color.WHITE, if (light) 0.72f else 0.86f)
    }

    /** 清透柔色左侧强调竖条：原色过浅时适度向墨色加深，保证视觉锚点清晰。 */
    fun courseSoftAccent(tone: Int): Int =
        if (isLightColor(tone)) blend(tone, INK, 0.24f) else tone

    /** 清透柔色主文字：同色系深色，保证在浅底上拥有锐利的高对比度。 */
    fun courseSoftText(tone: Int): Int {
        val light = isLightColor(tone)
        return blend(tone, INK, if (light) 0.66f else 0.52f)
    }

    /** 清透柔色角标底色：比块底色略深一点，自然融于卡片中。 */
    fun courseSoftBadgeFill(tone: Int): Int {
        val light = isLightColor(tone)
        return blend(tone, Color.WHITE, if (light) 0.54f else 0.76f)
    }

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

    private fun isLightColor(color: Int): Boolean {
        val r = color shr 16 and 0xff
        val g = color shr 8 and 0xff
        val b = color and 0xff
        return (0.299f * r + 0.587f * g + 0.114f * b) >= 165f
    }

    fun blend(color: Int, toward: Int, amount: Float): Int {
        fun mix(shift: Int) = ((color shr shift and 0xff) * (1f - amount) + (toward shr shift and 0xff) * amount)
            .roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
