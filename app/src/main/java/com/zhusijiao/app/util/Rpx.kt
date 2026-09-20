package com.zhusijiao.app.util

import android.content.res.Resources
import kotlin.math.roundToInt

/**
 * 尺寸换算工具。
 * 以 750 设计稿宽度 = 屏宽为基准；课表等需要「整周一屏、列宽精确」的场景
 * 按容器宽度等比换算 px。普通静态界面在 XML 中直接用 dp/sp（约定 1dp ≈ 2 设计稿单位）。
 */
object Rpx {

    val density: Float get() = Resources.getSystem().displayMetrics.density

    val screenWidthPx: Int get() = Resources.getSystem().displayMetrics.widthPixels

    /** 以给定基准宽度（通常为容器实际宽度）把设计稿单位换算成 px。 */
    fun pxOf(rpx: Float, baseWidthPx: Int): Int = (rpx * baseWidthPx / 750f).roundToInt()

    /** 以屏宽为基准把设计稿单位换算成 px（无容器宽度时的兜底）。 */
    fun px(rpx: Float): Int = pxOf(rpx, screenWidthPx)

    fun dp(value: Float): Int = (value * density).roundToInt()
}
