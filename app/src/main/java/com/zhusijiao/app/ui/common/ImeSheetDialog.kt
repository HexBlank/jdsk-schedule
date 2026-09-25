package com.zhusijiao.app.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.zhusijiao.app.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 带输入框的底部面板基类：点输入框时，面板随输入法逐帧同步上移，收起时同步落回
 * （软键盘避让，Android 上靠 WindowInsetsAnimation 与输入法动画逐帧同步）。
 *
 * 普通底部面板（窗口高度随内容、改 padding 把内容顶上去）在键盘动画里有两个毛病：
 * - 窗口高度跟内容走，动画每帧改 padding 都会让窗口本身改尺寸，要跨进程重排窗口、
 *   重新分配 Surface，部分机型上就表现为一帧一帧地跳、闪；
 * - API 30+ 在键盘动画开始前会先派发一次「终点」insets，直接按它改布局，
 *   第一帧会先闪到终点再回到起点。
 *
 * 这里的做法：窗口铺满全屏、边到边，遮罩自己画；键盘动画进行中只改面板的 translationY
 * （不触发布局、不动窗口），动画结束才把最终位置落到布局上（底部 margin），同一帧里
 * 把位移清零，两者无缝衔接。面板很高时，布局会把它限制在状态栏与键盘之间，
 * 根布局是滚动容器的面板（如调课）就能在剩余空间里滚动。
 *
 * 进出场动画也由面板自己做，节奏与普通底部面板一致（上滑 240ms、下滑 180ms）。
 * 子类照常 setContentView(布局)，不要再改窗口尺寸、重心和背景。
 */
open class ImeSheetDialog(context: Context) : Dialog(context, R.style.Theme_Zhusijiao_Sheet_Ime) {

    private val scrim = FrameLayout(context)
    private var panel: View? = null
    private var panelBaseBottomPadding = 0
    private var panelBaseTopMargin = 0
    private var panelBaseBottomMargin = 0

    private var cancelable = true
    private var cancelOnTouchOutside = true

    /** 展开程度：0 完全收起、1 完全展开；决定进出场位移和遮罩深浅。 */
    private var shown = 0f
    private var slideOffset = 0f

    /** 键盘动画进行中，面板相对「已落到布局上的位置」还要再移动的距离。 */
    private var imeOffset = 0f

    /** 已经通过底部 margin 落到布局上的上抬高度。 */
    private var appliedLift = 0
    private var imeAnimating = false
    private var latestInsets: WindowInsetsCompat? = null

    private var animator: ValueAnimator? = null
    private var closing = false

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
        scrim.background = ColorDrawable(SCRIM_COLOR).apply { alpha = 0 }
        scrim.setOnClickListener {
            if (cancelable && cancelOnTouchOutside && isShowing) cancel()
        }
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
        view.layoutParams = params
    }

    /** 面板需要上抬的高度：输入法顶边到导航条顶边的距离（导航条那段本来就由底部留白占着）。 */
    private fun liftOf(insets: WindowInsetsCompat): Int = max(
        0,
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom -
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    )

    private fun updateTranslation() {
        panel?.translationY = slideOffset + imeOffset
    }

    override fun onStart() {
        super.onStart()
        closing = false
        val view = panel ?: return
        shown = 0f
        scrim.background.alpha = 0
        // 首次布局完成、首帧绘制之前把面板放到屏幕外，再滑上来，不会闪出终点位置
        view.doOnLayout { animateTo(1f, ENTER_DURATION, ENTER_INTERPOLATOR, null) }
    }

    override fun dismiss() {
        if (closing) return
        val view = panel
        if (!isShowing || view == null || !view.isLaidOut) {
            finishDismiss()
            return
        }
        closing = true
        // 键盘与面板一起落下，不留键盘单独悬在半空
        window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime()) }
        animateTo(0f, EXIT_DURATION, EXIT_INTERPOLATOR) { finishDismiss() }
    }

    private fun finishDismiss() {
        animator?.cancel()
        try {
            super.dismiss()
        } catch (e: IllegalArgumentException) {
            // 所属页面已销毁、窗口已被系统移除时，这里无事可做
        }
    }

    private fun animateTo(target: Float, duration: Long, interpolator: TimeInterpolator, onEnd: (() -> Unit)?) {
        animator?.cancel()
        val view = panel ?: return
        // 让面板顶边正好落到屏幕底边外
        val distance = max(scrim.height - view.top, view.height).toFloat()
        // 先按起点摆好再开动画，保证首帧就在正确位置
        slideOffset = (1f - shown) * distance
        updateTranslation()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                shown = it.animatedValue as Float
                slideOffset = (1f - shown) * distance
                scrim.background.alpha = (shown * 255).roundToInt()
                updateTranslation()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun WindowInsetsAnimationCompat.isIme() =
        typeMask and WindowInsetsCompat.Type.ime() != 0

    private companion object {
        /** 与普通底部面板的系统压暗一致：黑色 45%。 */
        const val SCRIM_COLOR = 0x73000000
        const val ENTER_DURATION = 240L
        const val EXIT_DURATION = 180L

        /** 对应 @anim/sheet_up 的 fast_out_slow_in。 */
        val ENTER_INTERPOLATOR: TimeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        /** 对应 @anim/sheet_down 的 accelerate_quad。 */
        val EXIT_INTERPOLATOR: TimeInterpolator = AccelerateInterpolator()
    }
}
