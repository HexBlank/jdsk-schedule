package com.zhusijiao.app.ui.common

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 全站底部面板的统一基类：进出场、遮罩、下拉关闭、输入法避让都在这里，子类只管内容。
 *
 * 手感（照 WWDC18「Designing Fluid Interfaces」）：
 * - 进出场都由弹簧驱动，随时可以被手指接住，不必等动画播完；
 * - 面板跟手下拉，遮罩随拉下的距离变浅；松手时按「当前位置 + 速度投射」决定关掉还是弹回：
 *   轻轻一甩就能关，拖了一大半又往回推也能留住；
 * - 甩下去关闭时手指速度原样交给弹簧（奖励动量），点遮罩 / 返回键关闭则利落收起、不回弹；
 * - 面板里的滚动容器没滚到顶时，下拉先滚内容；自绘控件按下时声明独占触摸（滚轮、调色板）就不会被抢。
 *
 * 输入法避让：窗口铺满全屏、边到边，遮罩自己画；键盘动画进行中只改面板的 translationY
 * （不触发布局、不动窗口），动画结束才把最终位置落到布局上（底部 margin），同一帧里
 * 把位移清零，两者无缝衔接。普通面板（窗口高度随内容、改 padding 顶内容）在键盘动画里
 * 会因为窗口反复改尺寸而逐帧跳闪，API 30+ 还会先闪一下终点，所以统一用这种做法。
 * 面板很高时，布局会把它限制在状态栏与键盘之间，根布局是滚动容器的面板就能在剩余空间里滚动。
 *
 * 子类照常 setContentView(布局)，不要再改窗口尺寸、重心和背景；需要固定高度用 [setPanelHeight]。
 * [scrimAmount] 是遮罩最深时的不透明度：一般 0.45；边调边看的面板（课表外观、选色预览）用 [SCRIM_CLEAR]。
 */
open class SheetDialog(
    context: Context,
    private val scrimAmount: Float = SCRIM_DEFAULT
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    private val scrim = Scrim(context)
    private var panel: View? = null
    private var panelBaseBottomPadding = 0
    private var panelBaseTopMargin = 0
    private var panelBaseBottomMargin = 0

    private var cancelable = true
    private var cancelOnTouchOutside = true

    /** 面板相对停靠位置往下移了多少（px）：0 完全展开，[travel] 完全收起；拖过头往上为负。 */
    private val slide = Spring(0f, 0.5f)
    private val driver = SpringDriver(scrim, arrayOf(slide)) { onSlide() }

    /** 键盘动画进行中，面板相对「已落到布局上的位置」还要再移动的距离。 */
    private var imeOffset = 0f

    /** 已经通过底部 margin 落到布局上的上抬高度。 */
    private var appliedLift = 0
    private var imeAnimating = false
    private var latestInsets: WindowInsetsCompat? = null

    private var closing = false

    /** 下拉手势松手时的速度（px/s），交给收起动画。 */
    private var releaseVelocity = 0f

    init {
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(0)
            // 边到边：系统不再缩放/平移窗口，键盘高度只以 insets 的形式交给面板自己处理
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        scrim.background = ColorDrawable(Color.BLACK).apply { alpha = 0 }
        scrim.setOnClickListener {
            if (cancelable && cancelOnTouchOutside && isShowing) cancel()
        }
        driver.onSettled = { if (closing) finishDismiss() }
        installImeHandling()
    }

    override fun setContentView(layoutResID: Int) {
        setPanel(layoutInflater.inflate(layoutResID, scrim, false))
    }

    override fun setContentView(view: View) {
        setPanel(view)
    }

    override fun setCancelable(flag: Boolean) {
        cancelable = flag
        super.setCancelable(flag)
    }

    override fun setCanceledOnTouchOutside(cancel: Boolean) {
        cancelOnTouchOutside = cancel
        super.setCanceledOnTouchOutside(cancel)
    }

    /** 面板内容的固定高度（px，不含底部导航条留白）；-1 表示高度随内容。 */
    private var panelFixedHeight = -1

    /** 固定面板高度（px，不含导航条），如需要成片纵向空间的日历面板；默认高度随内容。 */
    protected fun setPanelHeight(height: Int) {
        panelFixedHeight = height
        val view = panel ?: return
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply { this.height = height }
        latestInsets?.let(::applyInsets)
    }

    private fun setPanel(view: View) {
        val params = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        params.gravity = Gravity.BOTTOM
        panelBaseTopMargin = params.topMargin
        panelBaseBottomMargin = params.bottomMargin
        panelBaseBottomPadding = view.paddingBottom
        // 面板自己吃掉点击，只有点遮罩才算「点外面」
        view.isClickable = true
        scrim.removeAllViews()
        scrim.addView(view, params)
        panel = view
        super.setContentView(scrim)
        latestInsets?.let(::applyInsets)
    }

    private fun installImeHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { _, insets ->
            latestInsets = insets
            // 键盘动画中派发的是终点 insets，先存着，等动画结束再落到布局上
            if (!imeAnimating) applyInsets(insets)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            scrim,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.isIme()) imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    if (imeAnimating && !closing) {
                        imeOffset = (appliedLift - liftOf(insets)).toFloat()
                        updateTranslation()
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (!animation.isIme()) return
                    imeAnimating = false
                    if (closing) return
                    imeOffset = 0f
                    latestInsets?.let(::applyInsets)
                    updateTranslation()
                }
            }
        )
    }

    /** 把 insets 落到布局上：面板白底铺到导航条下，整体避开状态栏与输入法。 */
    private fun applyInsets(insets: WindowInsetsCompat) {
        val view = panel ?: return
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(bottom = panelBaseBottomPadding + bars.bottom)
        appliedLift = liftOf(insets)
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = bars.left
        params.rightMargin = bars.right
        params.topMargin = panelBaseTopMargin + bars.top
        params.bottomMargin = panelBaseBottomMargin + appliedLift
        if (panelFixedHeight > 0) params.height = panelFixedHeight + bars.bottom
        view.layoutParams = params
    }

    /** 面板需要上抬的高度：输入法顶边到导航条顶边的距离（导航条那段本来就由底部留白占着）。 */
    private fun liftOf(insets: WindowInsetsCompat): Int = max(
        0,
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom -
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    )

    /** 完全收起时面板要往下移多远：顶边正好落到屏幕底边外。 */
    private fun travel(): Float {
        val view = panel ?: return 0f
        return max(scrim.height - view.top, view.height).toFloat().coerceAtLeast(1f)
    }

    private fun onSlide() {
        val travel = travel()
        val open = (1f - slide.value / travel).coerceIn(0f, 1f)
        scrim.background.alpha = (open * scrimAmount * 255).roundToInt()
        updateTranslation()
        // 面板拖过头往上时，底下露出的那一截由遮罩补白
        val lifted = slide.value < 0f
        if (lifted || filledBelow) scrim.invalidate()
        filledBelow = lifted
        // 收起动画的目标比屏幕底边再远一点：越过底边就算关完，不必等弹簧慢慢贴近终点
        if (closing && slide.value >= travel) finishDismiss()
    }

    private var filledBelow = false

    private fun updateTranslation() {
        panel?.translationY = slide.value + imeOffset
    }

    override fun onStart() {
        super.onStart()
        closing = false
        val view = panel ?: return
        driver.stop()
        scrim.background.alpha = 0
        // 首次布局完成、首帧绘制之前把面板放到屏幕外，再弹上来，不会闪出终点位置
        view.doOnLayout {
            if (!Motion.enabled()) {
                slide.jumpTo(0f)
                onSlide()
                return@doOnLayout
            }
            slide.jumpTo(travel())
            onSlide()
            slide.springTo(0f, ENTER, velocity = 0f)
            driver.start()
        }
    }

    override fun dismiss() {
        if (closing) return
        val view = panel
        val owner = ownerActivity
        // 所属页面正在销毁时没有时间播动画了，直接关，免得窗口泄漏
        val ownerGone = owner != null && (owner.isFinishing || owner.isDestroyed)
        if (!isShowing || view == null || !view.isLaidOut || !Motion.enabled() || ownerGone) {
            finishDismiss()
            return
        }
        closing = true
        // 键盘与面板一起落下，不留键盘单独悬在半空
        window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime()) }
        // 甩下去关的保留手指速度；点按关的从静止起步，利落收起
        val velocity = max(releaseVelocity, slide.velocity)
        releaseVelocity = 0f
        slide.springTo(travel() * EXIT_OVERSHOOT, EXIT, velocity)
        driver.start()
    }

    private fun finishDismiss() {
        driver.stop()
        try {
            super.dismiss()
        } catch (e: IllegalArgumentException) {
            // 所属页面已销毁、窗口已被系统移除时，这里无事可做
        }
    }

    // ===== 下拉关闭 =====

    private fun startDrag() {
        driver.stop()
        slide.jumpTo(slide.value.coerceAtMost(travel()))
    }

    /** [raw] 为手指要求的下移量（相对停靠位置）。往上拖、或不允许关闭时往下拖，都是越拉越重的橡皮筋。 */
    private fun dragTo(raw: Float) {
        val pullLimit = DRAG_PULL_LIMIT_DP * Rpx.density
        val value = when {
            raw < 0f -> Motion.rubberBand(raw, pullLimit)
            !cancelable -> Motion.rubberBand(raw, pullLimit * 2f)
            else -> raw
        }
        slide.jumpTo(value)
        onSlide()
    }

    private fun release(velocityY: Float) {
        // 动量投射：照这个速度再滑一小段会停在哪；过了一半就关，否则弹回
        val projected = slide.value + Motion.project(velocityY, PROJECTION_RATE)
        if (cancelable && !closing && slide.value > 0f && projected > travel() * DISMISS_FRACTION) {
            releaseVelocity = velocityY
            cancel()
        } else {
            slide.springTo(0f, SpringSpec.MOMENTUM, velocityY)
            driver.start()
        }
    }

    private fun WindowInsetsAnimationCompat.isIme() =
        typeMask and WindowInsetsCompat.Type.ime() != 0

    /**
     * 遮罩兼手势层：在面板上竖直往下拖、且手指下的内容已滚到顶时接管手势；
     * 其余触摸照常分发给面板里的控件。
     */
    private inner class Scrim(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val fillPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.surface) }
        private var tracker: VelocityTracker? = null
        private var downX = 0f
        private var downY = 0f
        private var dragBase = 0f
        private var dragging = false
        private var onPanel = false

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val view = panel ?: return
            if (view.translationY < 0f) {
                canvas.drawRect(view.left.toFloat(), view.bottom + view.translationY, view.right.toFloat(), height.toFloat(), fillPaint)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val view = panel ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTracker()
                    tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                    // 进场还没停稳时按下也照常分发：想点按钮的不会被吞，往下拖则从当前位置接住
                    onPanel = !closing && ev.y >= view.top + view.translationY && ev.y <= view.bottom + view.translationY
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    if (!onPanel || dragging) return dragging
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (dy > touchSlop && dy > abs(dx) * 1.2f && !canScrollUpAt(view, downX, downY)) {
                        beginDrag(downY + touchSlop)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetTracker()
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(event)
            tracker?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragTo(dragBase + event.y - downY)
                MotionEvent.ACTION_UP -> {
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    endDrag()
                    release(velocity)
                }
                MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    release(0f)
                }
            }
            return true
        }

        private fun beginDrag(fromY: Float) {
            dragging = true
            startDrag()
            dragBase = slide.value
            downY = fromY
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        private fun endDrag() {
            dragging = false
            resetTracker()
        }

        private fun resetTracker() {
            tracker?.recycle()
            tracker = null
        }

        /** 手指按下处（遮罩坐标）往里找，路径上有任何一个还能往上滚的容器，就先让它滚。 */
        private fun canScrollUpAt(root: View, x: Float, y: Float): Boolean {
            if (root.visibility != VISIBLE) return false
            // getHitRect 已含平移，坐标是父容器内容坐标系（已加上父容器的滚动量）
            val rect = Rect()
            root.getHitRect(rect)
            if (!rect.contains(x.roundToInt(), y.roundToInt())) return false
            if (root.canScrollVertically(-1)) return true
            if (root is ViewGroup) {
                val localX = x - root.left - root.translationX + root.scrollX
                val localY = y - root.top - root.translationY + root.scrollY
                for (i in root.childCount - 1 downTo 0) {
                    if (canScrollUpAt(root.getChildAt(i), localX, localY)) return true
                }
            }
            return false
        }
    }

    companion object {
        /** 一般面板的遮罩：黑色 45%。 */
        const val SCRIM_DEFAULT = 0.45f
        /** 边调边看的面板（课表外观、选色预览）：几乎不压暗，能看清上半屏的真实课表。 */
        const val SCRIM_CLEAR = 0.12f

        /** 点按打开：无回弹，利落到位。 */
        private val ENTER = SpringSpec(0.36f)
        /** 点遮罩 / 返回键 / 按钮关闭：比打开更快，越快越好。 */
        private val EXIT = SpringSpec(0.28f)
        private const val EXIT_OVERSHOOT = 1.1f

        private const val DRAG_PULL_LIMIT_DP = 16f
        private const val DISMISS_FRACTION = 0.5f
        /** 投射用的减速率：比滚动列表的 0.998 衰减得快，大约看手指速度后 0.1 秒的去向。 */
        private const val PROJECTION_RATE = 0.99f
    }
}
