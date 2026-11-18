package com.zhusijiao.app.ui.common

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * 限制模态弹窗的最大高度，正文超出时由内部滚动区滚动。
 *
 * 上限按「窗口高度」计算，而不是按父布局给出的测量限制：弹窗窗口是
 * wrap_content，窗口 frame 会随内容高度变化，用父布局高度做上限会形成
 * 「上限 = 上次内容高度 × 0.8」的收缩循环，正文会被逐层压没。
 */
class ModalLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 不论父布局给的是 AT_MOST 还是 EXACTLY（不同 ROM 对弹窗窗口的测量方式不同），
        // 都按窗口高度封顶后重新测量，避免弹窗被窗口 frame 反向压缩
        val maxHeight = (windowHeight() * MAX_HEIGHT_RATIO).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        )
    }

    /** 当前窗口高度：分屏、横屏下取实际可用区域，而不是物理屏幕高度。 */
    private fun windowHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics?.bounds?.height()?.let { return it }
        }
        return resources.displayMetrics.heightPixels
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.8f
    }
}
