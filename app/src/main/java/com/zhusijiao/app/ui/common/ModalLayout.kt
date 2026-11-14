package com.zhusijiao.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** 限制模态弹窗高度，正文通过权重让出标题与按钮所需的空间。 */
class ModalLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // 使用父布局给出的可用高度，兼顾横屏、分屏及系统栏占用。
        val availableHeight = if (mode == MeasureSpec.AT_MOST) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            resources.displayMetrics.heightPixels
        }
        val maxHeight = (availableHeight * 0.8f).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }
}
