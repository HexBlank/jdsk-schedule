package com.zhusijiao.app.domain

/**
 * 课表外观：格子高度、格子留白、文字大小三档偏好，外加「自动铺满一屏」。
 *
 * 只影响本机显示，不随课表同步、不进服务端模型——同一份课表在不同同学手机上
 * 可以各调各的。数值一律用 750 设计稿单位（rpx），由 [com.zhusijiao.app.ui.common.TimetableView]
 * 按视图宽度等比换算成 px。
 *
 * 注意：列宽不在可调范围内。整周一屏是硬约束（见 docs/DECISIONS.md D6），
 * 列宽由屏宽除以天数得到，用户想要的「格子瘦一点」由 [blockMarginRpx]（块的左右留白）实现。
 */
data class TimetableAppearance(
    /** 格子高度档位，0 最矮、4 最高，默认 2（124rpx，与历史版本一致）。 */
    val rowHeightLevel: Int = DEFAULT_ROW_HEIGHT_LEVEL,
    /** 格子留白档位，0 紧凑、2 宽松，默认 1（4rpx）。 */
    val paddingLevel: Int = DEFAULT_PADDING_LEVEL,
    /** 文字大小档位，0 小、2 大，默认 1（原字号）。 */
    val textLevel: Int = DEFAULT_TEXT_LEVEL,
    /** 开启后行高改为「可视高度 ÷ 节次数」，课表本体不用滚动；此时高度档位不生效。 */
    val fitScreen: Boolean = false
) {

    val rowHeightRpx: Float get() = ROW_HEIGHTS[rowHeightLevel.coerceIn(ROW_HEIGHTS.indices)]

    val blockMarginRpx: Float get() = BLOCK_MARGINS[paddingLevel.coerceIn(BLOCK_MARGINS.indices)]

    val textScale: Float get() = TEXT_SCALES[textLevel.coerceIn(TEXT_SCALES.indices)]

    /** 是否全部为默认值（面板据此决定「恢复默认」是否可点）。 */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        /** 矮 / 偏矮 / 标准 / 偏高 / 高。标准档 124rpx 是 1.2.21 及更早的固定值。 */
        val ROW_HEIGHTS = listOf(100f, 112f, 124f, 140f, 158f)

        /** 紧凑 / 标准 / 宽松：课程块相对格线的左右上下留白，越大块越瘦。 */
        val BLOCK_MARGINS = listOf(2f, 4f, 9f)

        /** 小 / 标准 / 大：课程名、教师、教室、角标字号的统一缩放系数。 */
        val TEXT_SCALES = listOf(0.9f, 1f, 1.12f)

        const val DEFAULT_ROW_HEIGHT_LEVEL = 2
        const val DEFAULT_PADDING_LEVEL = 1
        const val DEFAULT_TEXT_LEVEL = 1

        /** 铺满一屏时行高的上下限：太矮放不下课程名，太高反而浪费（节次少时会顶到上限）。 */
        const val FIT_MIN_RPX = 84f
        const val FIT_MAX_RPX = 176f

        val DEFAULT = TimetableAppearance()

        /**
         * 铺满一屏的行高：把可视高度减去表头后平分给各节次，并收敛到 [FIT_MIN_RPX]~[FIT_MAX_RPX]。
         * [availableRpx] 为可视区域（不含表头）换算成设计稿单位后的高度。
         */
        fun fitRowHeightRpx(availableRpx: Float, rows: Int): Float {
            if (rows <= 0 || availableRpx <= 0f) return DEFAULT.rowHeightRpx
            return (availableRpx / rows).coerceIn(FIT_MIN_RPX, FIT_MAX_RPX)
        }
    }
}
