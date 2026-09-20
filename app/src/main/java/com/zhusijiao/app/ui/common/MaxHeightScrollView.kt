package com.zhusijiao.app.ui.common

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * 底部抽屉内容滚动容器：首次 onMeasure 就把高度限制在屏幕高度的 72% 以内，
 * 弹窗窗口按最终高度布局，避免「先撑满全屏再收缩」造成的弹出跳动。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }

    companion object {
        private const val MAX_HEIGHT_RATIO = 0.72f
    }
}
