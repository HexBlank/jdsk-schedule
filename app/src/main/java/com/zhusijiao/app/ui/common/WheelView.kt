package com.zhusijiao.app.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 单列滚轮选择器：居中一行为选中项（大字、强调色，上下两条细线框出选择区），
 * 相邻项渐小渐淡。拖动跟手、松手按速度惯性滑动并吸附到最近一项。
 *
 * 自绘实现，不依赖 Material / RecyclerView / 系统 NumberPicker——
 * 后者的外观会随 Android 版本和厂商 ROM 变化（同 D17 的理由）。
 */
class WheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前选中下标。 */
    var selectedIndex: Int = 0
        private set

    private var labels: List<String> = emptyList()
    private var onSelected: ((Int) -> Unit)? = null
    private var descriptionOf: (String) -> String = { it }

    /** 滚动位置，单位 px；offset = selectedIndex * itemHeight 时该项正好居中。 */
    private var offset = 0f
    private var itemHeightPx = 0f
    private var animator: ValueAnimator? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val colorSelected = ContextCompat.getColor(context, R.color.accent_strong)
    private val colorNormal = ContextCompat.getColor(context, R.color.sub_8a)
    private val colorLine = ContextCompat.getColor(context, R.color.line)

    private var lastY = 0f
    private var downY = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isClickable = true
        isFocusable = true
    }

    fun configure(
        labels: List<String>,
        initialIndex: Int,
        descriptionOf: (String) -> String = { it },
        onSelected: (Int) -> Unit
    ) {
        this.labels = labels
        this.descriptionOf = descriptionOf
        this.onSelected = onSelected
        selectedIndex = initialIndex.coerceIn(0, maxOf(0, labels.lastIndex))
        offset = selectedIndex * itemHeight()
        updateDescription()
        requestLayout()
        invalidate()
    }

    /** 外部改值（如按节次预填时间）；[animate] 为 true 时滚动过去。 */
    fun setSelectedIndex(index: Int, animate: Boolean = false, notify: Boolean = false) {
        val target = index.coerceIn(0, maxOf(0, labels.lastIndex))
        if (animate) {
            animateTo(target * itemHeight()) { commit(target, notify) }
        } else {
            animator?.cancel()
            offset = target * itemHeight()
            commit(target, notify)
            invalidate()
        }
    }

    private fun itemHeight(): Float {
        if (itemHeightPx <= 0f) itemHeightPx = ITEM_HEIGHT_DP * resources.displayMetrics.density
        return itemHeightPx
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (itemHeight() * VISIBLE_ROWS).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty()) return
        val step = itemHeight()
        val centerY = height / 2f
        // 选择区的上下两条细线
        linePaint.color = colorLine
        linePaint.strokeWidth = maxOf(1f, resources.displayMetrics.density)
        canvas.drawLine(0f, centerY - step / 2f, width.toFloat(), centerY - step / 2f, linePaint)
        canvas.drawLine(0f, centerY + step / 2f, width.toFloat(), centerY + step / 2f, linePaint)

        val density = resources.displayMetrics.density
        labels.forEachIndexed { index, label ->
            val y = centerY + index * step - offset
            if (y < -step || y > height + step) return@forEachIndexed
            // 距离中心越远字越小越淡，形成滚轮的纵深感
            val distance = min(1f, abs(index * step - offset) / step)
            textPaint.textSize = (SELECTED_SP - (SELECTED_SP - NORMAL_SP) * distance) * density
            val color = if (distance < 0.5f) colorSelected else colorNormal
            textPaint.color = withAlpha(color, 1f - 0.45f * distance)
            val fm = textPaint.fontMetrics
            canvas.drawText(label, width / 2f, y - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).roundToInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = event.y
                lastY = event.y
                dragging = false
                // 滚轮在滚动容器里也要能拖动，先把手势要过来
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = event.y - lastY
                lastY = event.y
                if (!dragging && abs(event.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    offset = rubberBand(offset - dy)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging) {
                    // 轻点某一行：直接选中它
                    performClick()
                    val tapped = ((event.y - height / 2f) / itemHeight()).roundToInt()
                    settleTo(offset + tapped * itemHeight())
                } else {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    settleTo(offset - velocityY * FLING_FACTOR)
                }
                releaseTracker()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                settleTo(offset)
                releaseTracker()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
    }

    /** 拖到两端之外时阻尼回拉，给出「到头了」的手感而不是硬停。 */
    private fun rubberBand(value: Float): Float {
        val max = labels.lastIndex * itemHeight()
        return when {
            value < 0f -> value / 3f
            value > max -> max + (value - max) / 3f
            else -> value
        }
    }

    /** 惯性落点吸附到最近一项。 */
    private fun settleTo(rawTarget: Float) {
        val step = itemHeight()
        val max = labels.lastIndex * step
        val clamped = rawTarget.coerceIn(0f, max)
        val index = (clamped / step).roundToInt().coerceIn(0, labels.lastIndex)
        animateTo(index * step) { commit(index, notify = true) }
    }

    private fun animateTo(target: Float, onEnd: () -> Unit) {
        animator?.cancel()
        if (abs(target - offset) < 0.5f) {
            offset = target
            invalidate()
            onEnd()
            return
        }
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = SETTLE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
            start()
        }
    }

    private fun commit(index: Int, notify: Boolean) {
        val changed = index != selectedIndex
        selectedIndex = index
        updateDescription()
        if (notify && changed) onSelected?.invoke(index)
    }

    private fun updateDescription() {
        labels.getOrNull(selectedIndex)?.let { contentDescription = descriptionOf(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        releaseTracker()
    }

    // 读屏用户靠上下滑动手势改值
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.NumberPicker::class.java.name
        if (selectedIndex > 0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (selectedIndex < labels.lastIndex) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                setSelectedIndex(selectedIndex + 1, animate = true, notify = true)
                return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                setSelectedIndex(selectedIndex - 1, animate = true, notify = true)
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    companion object {
        private const val ITEM_HEIGHT_DP = 46f
        /** 可见行数（上一行 + 选中行 + 下一行）。 */
        private const val VISIBLE_ROWS = 3
        private const val SELECTED_SP = 27f
        private const val NORMAL_SP = 20f
        private const val FLING_FACTOR = 0.22f
        private const val SETTLE_MS = 220L
    }
}
