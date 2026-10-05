package com.zhusijiao.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AnimationUtils
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnAttach
import androidx.customview.widget.ExploreByTouchHelper
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 悬浮底栏（Floating Navigation Dock）：一枚浮在页面上的胶囊，页签不到五个时不铺满、居中收窄；
 * 选中态是一块在页签之间弹性滑动的「透镜」，透镜盖住的图标和文字变主色，没盖住的保持灰色，
 * 滑过时一个页签会一半灰一半绿。
 *
 * 手感照着 FlClash 的 NavigationDock 做（它又是照 iOS 26 的标签栏做的）：
 * - 按下：整条栏微微鼓起，透镜抬起放大并移到手指下的页签；
 * - 按住横向拖：透镜跟手滑动，越过页签时轻震一下，拖出两端有橡皮筋阻尼，栏身被拉长一点；
 * - 松手：透镜带回弹落到所在页签并切换过去；甩一下可以多带一格。
 *
 * 全部自绘、弹簧自己积分（没有引入 dynamicanimation），页面内容可以滚到栏下面，
 * 各页的滚动容器用 [padScrollContent] 在末尾留出栏的位置。
 * 新增入口只需在 [Tab] 增加一项。「我们」（情侣课表）只在绑定后出现，由宿主用 [setTabVisible] 控制。
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tab(val labelRes: Int, val iconRes: Int) {
        SCHEDULE(R.string.nav_schedule, R.drawable.ic_nav_schedule),
        COUPLE(R.string.nav_couple, R.drawable.ic_nav_couple),
        LIBRARY(R.string.nav_library, R.drawable.ic_nav_library),
        SETTINGS(R.string.nav_settings, R.drawable.ic_nav_settings)
    }

    var onTabSelected: ((Tab) -> Unit)? = null

    private var current: Tab = Tab.SCHEDULE
    private val hidden = mutableSetOf(Tab.COUPLE)
    private var tabs: List<Tab> = Tab.entries.filter { it !in hidden }

    private val colNormal = ContextCompat.getColor(context, R.color.nav_text)
    private val colActive = ContextCompat.getColor(context, R.color.accent)
    private val colLens = ContextCompat.getColor(context, R.color.accent_soft)
    private val colLensPressed = BlockStyles.blend(colLens, ContextCompat.getColor(context, R.color.accent_strong), 0.08f)

    private val iconsNormal = Tab.entries.associateWith { tintedIcon(it, colNormal) }
    private val iconsActive = Tab.entries.associateWith { tintedIcon(it, colActive) }
    private val labelOf = Tab.entries.associateWith { context.getString(it.labelRes) }
    /** 按当前页签宽度截断后的标签（中文两三个字基本不会截断，系统字体很大时兜底）。 */
    private var shownLabels: Map<Tab, CharSequence> = labelOf

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, Rpx.density * 0.5f)
        color = 0x0F000000
    }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW_COLOR }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // 几何（px）
    private val topRoom = dpf(TOP_ROOM_DP)
    private val barPadding = dpf(BAR_PADDING_DP)
    private val iconSize = dpf(ICON_DP)
    private val labelGap = dpf(LABEL_GAP_DP)
    private val hitSlop = dpf(HIT_SLOP_DP)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var navInset = 0
    private val bar = RectF()
    private var extent = 0f
    private var shadow: Bitmap? = null
    private val lensRect = RectF()
    private val lensPath = Path()

    // 弹簧：透镜位置（以页签为单位）、透镜抬起、整栏鼓起、整栏横向拉伸（px）
    private val lens = Spring(0f, 0.001f)
    private val lift = Spring(0f, 0.001f)
    private val swell = Spring(0f, 0.001f)
    private val stretch = Spring(0f, 0.1f)
    private val springs = arrayOf(lens, lift, swell, stretch)
    private var lastFrame = 0L
    private var framing = false
    private val frame = Runnable { onFrame() }

    // 刚出现的页签（「我们」）图标弹一下
    private var popTab: Tab? = null
    private var popScale = 1f
    private var popAnimator: ValueAnimator? = null

    // 触摸
    private var pressedIndex = -1
    private var pressX = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    private val accessibility = TabAccessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (bottom != navInset) {
                navInset = bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
        framing = false
        popAnimator?.cancel()
        releaseTracker()
        springs.forEach { it.finish() }
    }

    // ===== 对外接口 =====

    /** 显示或隐藏某个页签（目前只有「我们」会隐藏）；从隐藏变为显示时图标轻轻弹一下，提示多了一个入口。 */
    fun setTabVisible(tab: Tab, visible: Boolean) {
        val wasHidden = tab in hidden
        if (visible == !wasHidden) return
        if (visible) hidden -= tab else hidden += tab
        tabs = Tab.entries.filter { it !in hidden }
        cancelPress()
        updateGeometry()
        lens.jumpTo(indexOfCurrent().toFloat())
        accessibility.invalidateRoot()
        invalidate()
        if (visible && isAttachedToWindow) pop(tab)
    }

    /** 由宿主设置当前项（不触发回调）。 */
    fun setCurrent(tab: Tab) {
        if (tab == current) return
        current = tab
        accessibility.invalidateRoot()
        if (pressedIndex >= 0) return
        val target = indexOfCurrent().toFloat()
        if (isLaidOut && animationsEnabled()) {
            lens.springTo(target, SETTLE)
            startFrames()
        } else {
            lens.jumpTo(target)
            invalidate()
        }
    }

    private fun indexOfCurrent(): Int = tabs.indexOf(current).coerceAtLeast(0)

    private fun select(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab == current) return
        current = tab
        accessibility.invalidateRoot()
        onTabSelected?.invoke(tab)
    }

    private fun pop(tab: Tab) {
        popAnimator?.cancel()
        if (!animationsEnabled()) return
        popTab = tab
        popAnimator = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 360L
            interpolator = android.view.animation.OvershootInterpolator(3f)
            addUpdateListener {
                popScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ===== 测量与几何 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = topRoom + barHeightPx() + bottomMarginPx(navInset)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
        if (!lens.running && pressedIndex < 0) lens.jumpTo(indexOfCurrent().toFloat())
    }

    /** 系统字体调大时标签变高，栏跟着加高，免得文字顶到边。 */
    private fun barHeightPx(): Float = dpf(BAR_HEIGHT_DP) + max(0f, labelPaint.textSize - dpf(LABEL_SP))

    private fun updateGeometry() {
        if (width == 0 || tabs.isEmpty()) return
        val available = width - 2f * dpf(EDGE_MARGIN_DP)
        // 页签不到五个时不铺满：每个最多 72dp 宽，整条居中
        val wanted = if (tabs.size >= FULL_WIDTH_COUNT) available else tabs.size * dpf(MAX_ITEM_EXTENT_DP) + 2f * barPadding
        val barWidth = min(available, wanted)
        val left = (width - barWidth) / 2f
        val sizeChanged = abs(bar.width() - barWidth) > 0.5f || abs(bar.height() - barHeightPx()) > 0.5f
        bar.set(left, topRoom, left + barWidth, topRoom + barHeightPx())
        extent = (barWidth - 2f * barPadding) / tabs.size
        val labelRoom = extent - 2f * dpf(LABEL_INSET_DP)
        shownLabels = labelOf.mapValues { TextUtils.ellipsize(it.value, labelPaint, labelRoom, TextUtils.TruncateAt.END) }
        if (sizeChanged || shadow == null) rebuildShadow()
    }

    /**
     * 栏下面那层又宽又淡的投影。硬件画布上 setShadowLayer 要到 Android 9 才对图形生效，
     * 所以先在位图里用模糊滤镜画好，之后每帧只贴图（栏鼓起、拉伸时跟着一起变形）。
     */
    private fun rebuildShadow() {
        val blur = dpf(SHADOW_BLUR_DP)
        val w = (bar.width() + blur * 2f).roundToInt()
        val h = (bar.height() + blur * 2f).roundToInt()
        if (w <= 0 || h <= 0) return
        shadow?.recycle()
        shadow = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { bitmap ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
                val radius = bar.height() / 2f
                Canvas(bitmap).drawRoundRect(RectF(blur, blur, blur + bar.width(), blur + bar.height()), radius, radius, paint)
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** 透镜的位置：横向以页签为单位；滑得快时被拉长压扁（果冻感），按下抬起时整体放大。 */
    private fun computeLensRect(out: RectF) {
        val innerHeight = bar.height() - 2f * barPadding
        val jelly = (abs(lens.velocity) / JELLY_SPEED).coerceIn(0f, 1f) * JELLY_STRETCH
        val growth = dpf(LENS_GROWTH_DP) * 2f * lift.value
        val w = (extent + growth) * (1f + jelly)
        val h = (innerHeight + growth) * (1f - jelly / 2f)
        val centerX = bar.left + barPadding + (lens.value + 0.5f) * extent
        val centerY = bar.centerY()
        out.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
    }

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        if (tabs.isEmpty() || bar.isEmpty) return
        canvas.save()
        // 按下时整条栏鼓起一点；拖出两端时跟着手指被拉长
        val swellScale = 1f + swell.value * min(PRESS_GROWTH * 2f, dpf(MAX_PRESS_GROWTH_DP) / max(bar.width(), bar.height()))
        val pull = stretch.value
        canvas.translate(pull, 0f)
        canvas.scale(swellScale * (1f + abs(pull) / bar.width() * PULL_STRETCH), swellScale, bar.centerX(), bar.centerY())

        shadow?.let {
            val blur = dpf(SHADOW_BLUR_DP)
            canvas.drawBitmap(it, bar.left - blur, bar.top - blur + dpf(SHADOW_DY_DP), shadowPaint)
        }
        val radius = bar.height() / 2f
        canvas.drawRoundRect(bar, radius, radius, barPaint)
        canvas.drawRoundRect(bar, radius, radius, edgePaint)

        computeLensRect(lensRect)
        val lensRadius = lensRect.height() / 2f
        lensPaint.color = BlockStyles.blend(colLens, colLensPressed, lift.value.coerceIn(0f, 1f))
        canvas.drawRoundRect(lensRect, lensRadius, lensRadius, lensPaint)
        lensPath.rewind()
        lensPath.addRoundRect(lensRect, lensRadius, lensRadius, Path.Direction.CW)

        // 透镜外的部分画灰色，透镜内的部分画主色：同一个页签被透镜压到一半时，两种颜色各占一半
        canvas.save()
        clipOutLens(canvas)
        tabs.indices.forEach { drawItem(canvas, it, active = false) }
        canvas.restore()
        canvas.save()
        canvas.clipPath(lensPath)
        tabs.indices.forEach { drawItem(canvas, it, active = true) }
        canvas.restore()

        canvas.restore()
    }

    private fun clipOutLens(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(lensPath)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(lensPath, Region.Op.DIFFERENCE)
        }
    }

    private fun drawItem(canvas: Canvas, index: Int, active: Boolean) {
        val tab = tabs[index]
        val left = bar.left + barPadding + index * extent
        val centerX = left + extent / 2f
        // 透镜抬起时，它正下方的页签跟着放大一点，像被放大镜罩住
        val emphasis = (1f - abs(lens.value - index)).coerceIn(0f, 1f)
        var scale = 1f + LENS_MAGNIFY * emphasis * lift.value
        if (tab == popTab) scale *= popScale
        val fm = labelPaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val top = bar.centerY() - (iconSize + labelGap + lineHeight) / 2f

        canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, centerX, bar.centerY())
        val icon = (if (active) iconsActive else iconsNormal).getValue(tab)
        val iconLeft = (centerX - iconSize / 2f).roundToInt()
        val iconTop = top.roundToInt()
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize.roundToInt(), iconTop + iconSize.roundToInt())
        icon.draw(canvas)
        labelPaint.color = if (active) colActive else colNormal
        val label = shownLabels.getValue(tab)
        canvas.drawText(label, 0, label.length, centerX, top + iconSize + labelGap - fm.ascent, labelPaint)
        canvas.restore()
    }

    // ===== 触摸 =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 栏外的触摸不拦，交给下面的页面（栏是浮在内容上的）
                if (tabs.isEmpty() || !hits(event.x, event.y)) return false
                releaseTracker()
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
                pressedIndex = indexAt(positionAt(event.x))
                pressX = event.x
                dragging = false
                if (animationsEnabled()) {
                    swell.springTo(1f, LIFT)
                    lift.springTo(1f, LIFT)
                    lens.springTo(pressedIndex.toFloat(), SETTLE)
                    startFrames()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedIndex < 0) return false
                tracker?.addMovement(event)
                slide(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedIndex < 0) return false
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                fling(tracker?.xVelocity ?: 0f)
                release(commit = true)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressedIndex < 0) return false
                release(commit = false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hits(x: Float, y: Float): Boolean =
        x >= bar.left - hitSlop && x <= bar.right + hitSlop && y >= bar.top - hitSlop && y <= bar.bottom + hitSlop

    /** 手指横坐标对应的透镜位置（以页签为单位，0 为第一个页签的中心）；拖出两端时带橡皮筋阻尼。 */
    private fun positionAt(x: Float): Float {
        val last = tabs.lastIndex.toFloat()
        val position = (x - bar.left - barPadding) / extent - 0.5f
        return when {
            position < 0f -> rubberBand(position, OVERDRAG)
            position > last -> last + rubberBand(position - last, OVERDRAG)
            else -> position
        }
    }

    private fun indexAt(position: Float): Int = position.roundToInt().coerceIn(0, tabs.lastIndex)

    private fun slide(x: Float) {
        if (!dragging) {
            if (abs(x - pressX) < touchSlop) return
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val position = positionAt(x)
        val index = indexAt(position)
        if (index != pressedIndex) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            pressedIndex = index
        }
        if (!animationsEnabled()) return
        val overshoot = x - x.coerceIn(bar.left, bar.right)
        stretch.springTo(rubberBand(overshoot, bar.height() * PULL_LIMIT), TRACK)
        lens.springTo(position, TRACK)
        startFrames()
    }

    /** 拖着甩一下松手：顺着方向多带一格（最多一格），不必把手指拖到位。 */
    private fun fling(velocityX: Float) {
        if (!dragging || pressedIndex < 0 || extent <= 0f) return
        val lead = velocityX / extent * FLING_PROJECTION_SECONDS
        val base = if (lens.running) lens.target else lens.value
        val index = (base + lead).roundToInt().coerceIn(pressedIndex - 1, pressedIndex + 1).coerceIn(0, tabs.lastIndex)
        if (index != pressedIndex) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            pressedIndex = index
        }
    }

    private fun release(commit: Boolean) {
        val index = pressedIndex
        pressedIndex = -1
        dragging = false
        releaseTracker()
        if (index < 0) return
        if (commit) select(index)
        val target = indexOfCurrent().toFloat()
        if (animationsEnabled()) {
            lift.springTo(0f, SETTLE)
            swell.springTo(0f, SETTLE)
            stretch.springTo(0f, SETTLE)
            lens.springTo(target, SETTLE)
            startFrames()
        } else {
            lens.jumpTo(target)
            invalidate()
        }
    }

    /** 页签增减时如果正按着，直接收手，免得按下时记的下标对不上新的页签。 */
    private fun cancelPress() {
        pressedIndex = -1
        dragging = false
        releaseTracker()
        lift.jumpTo(0f)
        swell.jumpTo(0f)
        stretch.jumpTo(0f)
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }

    // ===== 弹簧驱动 =====

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun startFrames() {
        if (framing) return
        framing = true
        lastFrame = AnimationUtils.currentAnimationTimeMillis()
        postOnAnimation(frame)
    }

    private fun onFrame() {
        val now = AnimationUtils.currentAnimationTimeMillis()
        // 掉帧或刚回到前台时一步不要迈太大，否则弹簧会一下子甩出去
        val dt = ((now - lastFrame).coerceIn(1L, 34L)) / 1000f
        lastFrame = now
        var running = false
        for (spring in springs) {
            spring.advance(dt)
            running = running || spring.running
        }
        invalidate()
        if (running && isAttachedToWindow) postOnAnimation(frame) else framing = false
    }

    /** 弹簧参数：与 Flutter 的 SpringDescription.withDurationAndBounce 同一套换算（质量为 1）。 */
    private class SpringSpec(durationSeconds: Float, bounce: Float = 0f) {
        val stiffness = (2f * Math.PI.toFloat() / durationSeconds).let { it * it }
        val damping = 4f * Math.PI.toFloat() * (1f - bounce) / durationSeconds
    }

    /** 目标可以每一帧都变的弹簧：换目标时保留当前速度，所以跟手拖动和松手回弹之间没有顿挫。 */
    private class Spring(var value: Float, private val restDelta: Float) {
        var velocity = 0f
            private set
        var target = value
            private set
        var running = false
            private set
        private var spec: SpringSpec? = null

        fun springTo(target: Float, spec: SpringSpec) {
            this.target = target
            this.spec = spec
            running = true
        }

        fun jumpTo(value: Float) {
            this.value = value
            target = value
            velocity = 0f
            running = false
        }

        fun finish() = jumpTo(if (running) target else value)

        fun advance(dt: Float) {
            val s = spec
            if (!running || s == null) return
            var remaining = dt
            // 半隐式欧拉，步长不超过 4ms：最硬的那根弹簧（跟手用的 120ms）也不会发散
            while (remaining > 0f) {
                val h = min(remaining, 0.004f)
                velocity += (-s.stiffness * (value - target) - s.damping * velocity) * h
                value += velocity * h
                remaining -= h
            }
            if (abs(value - target) < restDelta && abs(velocity) < restDelta * 10f) jumpTo(target)
        }
    }

    // ===== 无障碍：每个页签是一个可聚焦、可点击的虚拟按钮 =====

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private inner class TabAccessibility : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (tabs.isEmpty() || !hits(x, y)) return INVALID_ID
            return indexAt((x - bar.left - barPadding) / extent - 0.5f)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(tabs.indices)
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            event.contentDescription = tabs.getOrNull(virtualViewId)?.let { labelOf.getValue(it) } ?: ""
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val tab = tabs.getOrNull(virtualViewId)
            node.className = Button::class.java.name
            node.contentDescription = tab?.let { labelOf.getValue(it) } ?: ""
            node.isSelected = tab == current
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val left = (bar.left + barPadding + virtualViewId * extent).roundToInt()
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(left, bar.top.roundToInt(), left + extent.roundToInt().coerceAtLeast(1), bar.bottom.roundToInt().coerceAtLeast(1)))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || virtualViewId !in tabs.indices) return false
            select(virtualViewId)
            setCurrentLens()
            return true
        }
    }

    private fun setCurrentLens() {
        val target = indexOfCurrent().toFloat()
        if (animationsEnabled()) {
            lens.springTo(target, SETTLE)
            startFrames()
        } else {
            lens.jumpTo(target)
            invalidate()
        }
    }

    private fun tintedIcon(tab: Tab, color: Int): Drawable =
        ContextCompat.getDrawable(context, tab.iconRes)!!.mutate().apply { setTint(color) }

    private fun dpf(value: Float): Float = value * Rpx.density

    companion object {
        private const val BAR_HEIGHT_DP = 62f
        private const val BAR_PADDING_DP = 4f
        private const val EDGE_MARGIN_DP = 21f
        /** 栏上方留给抬起的透镜的余量（透镜放大后会略微探出栏外）。 */
        private const val TOP_ROOM_DP = 12f
        private const val HIT_SLOP_DP = 8f
        private const val MAX_ITEM_EXTENT_DP = 72f
        private const val FULL_WIDTH_COUNT = 5
        private const val ICON_DP = 24f
        private const val LABEL_GAP_DP = 2f
        private const val LABEL_INSET_DP = 2f
        private const val LABEL_SP = 10f
        /** 页面内容的末尾与栏顶之间再留一点空。 */
        private const val CONTENT_GAP_DP = 8f

        private const val SHADOW_BLUR_DP = 24f
        private const val SHADOW_DY_DP = 8f
        private const val SHADOW_COLOR = 0x1A000000

        private const val PRESS_GROWTH = 1f / 8f
        private const val MAX_PRESS_GROWTH_DP = 16f
        private const val LENS_GROWTH_DP = 14f
        private const val LENS_MAGNIFY = 0.12f
        /** 透镜速度达到每秒这么多个页签时，果冻形变到头。 */
        private const val JELLY_SPEED = 8f
        private const val JELLY_STRETCH = 0.25f
        /** 拖出两端最多再走多远（以页签为单位）。 */
        private const val OVERDRAG = 0.35f
        private const val PULL_LIMIT = 7f / 32f
        private const val PULL_STRETCH = 0.5f
        private const val FLING_PROJECTION_SECONDS = 0.1f

        private val TRACK = SpringSpec(0.12f)
        private val LIFT = SpringSpec(0.28f, bounce = 0.2f)
        private val SETTLE = SpringSpec(0.5f, bounce = 0.32f)

        private fun rubberBand(overshoot: Float, limit: Float): Float {
            if (limit <= 0f) return 0f
            val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
            return limit * pull * sign(overshoot)
        }

        /** 栏底到屏幕底边的距离：至少 21dp；有三键导航条时坐在导航条上方。 */
        private fun bottomMarginPx(navInset: Int): Float =
            max(EDGE_MARGIN_DP * Rpx.density, navInset + 4f * Rpx.density)

        /**
         * 悬浮底栏盖住的页面底部高度（px）。页面内容是铺到屏幕底的，滚动容器的末尾要留出这么多，
         * 最后一行才不会被栏挡住；「铺满一屏」的课表也要按扣掉它之后的高度来平分行高。
         */
        fun contentInset(view: View): Int {
            val nav = ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            return contentInset(nav)
        }

        private fun contentInset(navInset: Int): Int =
            ((BAR_HEIGHT_DP + CONTENT_GAP_DP) * Rpx.density + bottomMarginPx(navInset)).roundToInt()

        /**
         * 让主界面某一页的滚动容器把内容滚到悬浮底栏下面：关掉 clipToPadding，
         * 并在原有底部留白之外再留出栏的位置（随导航条高度变化自动更新）。
         */
        fun padScrollContent(scroll: ViewGroup) {
            val base = scroll.paddingBottom
            scroll.clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
                val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val bottom = base + contentInset(nav)
                if (v.paddingBottom != bottom) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
                insets
            }
            scroll.doOnAttach { it.requestApplyInsets() }
        }
    }
}
