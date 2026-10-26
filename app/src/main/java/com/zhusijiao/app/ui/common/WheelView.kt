package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 单列滚轮选择器：居中一行为选中项（大字、强调色，上下两条细线框出选择区），
 * 相邻项渐小渐淡。拖动跟手、松手按速度惯性滑动并吸附到最近一项；
 * 吸附由弹簧驱动并继承松手速度（甩得远走得快），滑动途中按住即停；每滑过一项轻震一下。
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

    private val spring = Spring(0f, 0.3f)
    private val driver = SpringDriver(this, arrayOf(spring)) {
        offset = spring.value
        tickIfCrossed()
        invalidate()
    }.also { it.onSettled = { settled?.invoke() } }
    /** 这次吸附落定后要做的事（提交选中项）。 */
    private var settled: (() -> Unit)? = null
    /** 上一次轻震时居中的是哪一项。 */
    private var tickIndex = 0

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
        tickIndex = selectedIndex
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
            stopSettle()
            offset = target * itemHeight()
            tickIndex = target
            commit(target, notify)
            invalidate()
        }
    }

    /** sp → px，走 TypedValue 而不是 scaledDensity：API 34+ 的非线性字体缩放也能算对。 */
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /**
     * 行高不写死：系统字体放大后若行高不变，上下两项会挤在一起甚至叠字，
     * 所以取「基准行高」与「选中字号 × 1.55」的较大者。
     */
    private fun itemHeight(): Float {
        if (itemHeightPx <= 0f) {
            itemHeightPx = maxOf(
                ITEM_HEIGHT_DP * resources.displayMetrics.density,
                sp(SELECTED_SP) * 1.55f
            )
        }
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

        labels.forEachIndexed { index, label ->
            val y = centerY + index * step - offset
            if (y < -step || y > height + step) return@forEachIndexed
            // 距离中心越远字越小越淡，形成滚轮的纵深感
            val distance = min(1f, abs(index * step - offset) / step)
            textPaint.textSize = sp(SELECTED_SP - (SELECTED_SP - NORMAL_SP) * distance)
            // 长文案（如「10月8日 周四」）放不下时等比缩小，不被列宽裁掉
            val room = width - 4f * resources.displayMetrics.density
            val wanted = textPaint.measureText(label)
            if (wanted > room && room > 0f) textPaint.textSize *= room / wanted
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
                // 惯性滑动中按住即停在原地，不提交半路的值
                stopSettle()
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
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    dragRaw = unRubberBand(offset)
                }
                if (dragging) {
                    dragRaw -= dy
                    offset = rubberBand(dragRaw)
                    tickIfCrossed()
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
                    settleTo(offset - velocityY * FLING_FACTOR, -velocityY)
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

    /** 手指要求的滚动位置（未加阻尼）；拖到两端之外时由 [rubberBand] 换算成实际位置。 */
    private var dragRaw = 0f

    /** 拖到两端之外时越拉越重，最多再走一行，给出「到头了」的手感而不是硬停。 */
    private fun rubberBand(value: Float): Float {
        val max = labels.lastIndex * itemHeight()
        return when {
            value < 0f -> Motion.rubberBand(value, itemHeight())
            value > max -> max + Motion.rubberBand(value - max, itemHeight())
            else -> value
        }
    }

    /** [rubberBand] 的反函数：从当前（可能已在界外的）位置接着拖时，换算回手指位置。 */
    private fun unRubberBand(value: Float): Float {
        val max = labels.lastIndex * itemHeight()
        return when {
            value < 0f -> Motion.rubberBandInverse(value, itemHeight())
            value > max -> max + Motion.rubberBandInverse(value - max, itemHeight())
            else -> value
        }
    }

    /** 惯性落点吸附到最近一项；[velocity] 为松手时的滚动速度（px/s），交给弹簧作初速。 */
    private fun settleTo(rawTarget: Float, velocity: Float = 0f) {
        val step = itemHeight()
        val max = labels.lastIndex * step
        val clamped = rawTarget.coerceIn(0f, max)
        val index = (clamped / step).roundToInt().coerceIn(0, labels.lastIndex)
        animateTo(index * step, velocity) { commit(index, notify = true) }
    }

    private fun animateTo(target: Float, velocity: Float = 0f, onEnd: () -> Unit) {
        stopSettle()
        if (abs(target - offset) < 0.5f || !Motion.enabled()) {
            offset = target
            tickIfCrossed()
            invalidate()
            onEnd()
            return
        }
        settled = onEnd
        // 落点已经按速度投射选好了，多余的速度只会让它冲过头再回来，削到刚好不冲过头
        val limit = kotlin.math.sqrt(SETTLE.stiffness) * abs(target - offset)
        spring.jumpTo(offset)
        spring.springTo(target, SETTLE, velocity.coerceIn(-limit, limit))
        driver.start()
    }

    private fun stopSettle() {
        driver.stop()
        settled = null
    }

    /** 居中项变了就轻震一下，像拨动实体滚轮的齿。 */
    private fun tickIfCrossed() {
        if (labels.isEmpty()) return
        val index = (offset / itemHeight()).roundToInt().coerceIn(0, labels.lastIndex)
        if (index == tickIndex) return
        tickIndex = index
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
        stopSettle()
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
        /** 吸附弹簧：无回弹，长距离惯性也走得顺，不会被压在固定时长里一闪而过。 */
        private val SETTLE = SpringSpec(0.4f)
    }
}
