package com.zhusijiao.app.ui.common

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R

/**
 * 项目统一的开关控件：轨道 + 圆点全部自绘并带滑动动画，
 * 不依赖系统 Switch，保证所有设备外观一致（与 SegmentedChoiceView 等自定义控件同一设计语言）。
 */
class ToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 开关状态变化回调（仅状态真正翻转时触发）。 */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        private set

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
    }
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.toggle_thumb_border)
    }
    private val trackRect = RectF()

    private val colorOn = ContextCompat.getColor(context, R.color.accent_strong)
    private val colorOff = ContextCompat.getColor(context, R.color.toggle_track_off)
    private val argbEvaluator = ArgbEvaluator()

    /** 0 = 完全关闭，1 = 完全打开；动画期间插值，驱动圆点位移与轨道颜色渐变。 */
    private var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            trackPaint.color = argbEvaluator.evaluate(field, colorOff, colorOn) as Int
            invalidate()
        }

    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        progress = 0f
    }

    /** 设置开关状态；animate=false 用于初始化，直接呈现不做动画。 */
    fun setChecked(checked: Boolean, animate: Boolean = true) {
        val changed = isChecked != checked
        isChecked = checked
        animateTo(if (checked) 1f else 0f, animate)
        if (changed) onCheckedChange?.invoke(checked)
    }

    fun toggle() = setChecked(!isChecked)

    override fun performClick(): Boolean {
        super.performClick()
        toggle()
        return true
    }

    private fun animateTo(target: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            progress = target
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 170
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 视图略大于轨道（44×26dp），留出可点按的手指区域
        setMeasuredDimension(
            resolveSize(dp(50f).toInt(), widthMeasureSpec),
            resolveSize(dp(32f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val trackW = dp(44f)
        val trackH = dp(26f)
        val left = (width - trackW) / 2f
        val top = (height - trackH) / 2f
        trackRect.set(left, top, left + trackW, top + trackH)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        val inset = dp(2f)
        val thumbR = (trackH - inset * 2) / 2f
        val cx = left + inset + thumbR + progress * (trackW - inset * 2 - thumbR * 2)
        val cy = top + trackH / 2f
        canvas.drawCircle(cx, cy, thumbR, thumbPaint)
        canvas.drawCircle(cx, cy, thumbR - thumbBorderPaint.strokeWidth / 2f, thumbBorderPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isChecked = isChecked
        info.isClickable = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}