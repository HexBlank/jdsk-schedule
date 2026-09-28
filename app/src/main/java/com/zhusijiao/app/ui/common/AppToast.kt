package com.zhusijiao.app.ui.common

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zhusijiao.app.R
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自家轻提示：替代系统 Toast（样式由厂商决定、不能带按钮、分不出成功失败）。
 *
 * - 深色胶囊，四种类型：普通 / 成功（绿勾）/ 失败（红叹号）/ 进行中（转圈，直到被下一条替换）；
 *   可带一个文字按钮（如「撤销」）。
 * - 同时只显示一条，新提示直接替换旧的；3 秒内同一句话重复只重新计时并轻轻放大一下。
 * - 位置：从页面发出（context 就是 Activity）时在底栏或导航条上方；从底部面板、弹窗发出
 *   （context 是 Dialog 包装过的）时在屏幕顶部，不挡面板按钮和键盘。
 * - 每条提示是一个独立的应用窗口（和 Dialog 同一层级），后加的窗口在上，所以面板开着时也不会被遮罩盖住。
 * - 发出提示后马上关闭页面（如「日程已保存」后 finish）时，提示跟到下一个页面继续显示。
 * - 点一下提前关闭（带按钮时只有按钮响应），向屏幕边缘轻扫关闭，按住时暂停计时；读屏会朗读内容。
 *
 * 找不到可用的 Activity 时退回系统 Toast，保证提示不丢。所有入口都要在主线程调用（非主线程会自动转到主线程）。
 */
object AppToast {

    enum class Kind { INFO, SUCCESS, ERROR, LOADING }

    class Action(val label: String, val onClick: () -> Unit)

    private class Spec(
        val message: CharSequence,
        val kind: Kind,
        val action: Action?,
        val top: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private var host: Host? = null
    private var current: Spec? = null
    private var shownAt = 0L
    private var hideAt = 0L

    /** 所在页面被暂停（跳转、关闭）时暂存的提示，下一个页面恢复时接着显示。 */
    private var carried: Spec? = null
    private var carriedRemaining = 0L
    private var carriedAt = 0L
    private var lifecycleHooked = false

    private val hideRunnable = Runnable { dismiss() }

    fun show(context: Context, message: CharSequence, kind: Kind = Kind.INFO, action: Action? = null) {
        show(context, message, kind, action, retried = false)
    }

    private fun show(context: Context, message: CharSequence, kind: Kind, action: Action?, retried: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(context, message, kind, action, retried) }
            return
        }
        val activity = findActivity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            return
        }
        // 页面窗口还没挂上（比如在 onCreate 里就发提示）：等它挂上再显示一次
        val decor = activity.window?.decorView
        if (decor != null && decor.windowToken == null && !retried) {
            decor.post { show(context, message, kind, action, retried = true) }
            return
        }
        hookLifecycle(activity.application)
        val spec = Spec(message, kind, action, top = context !is Activity)
        val existing = host
        val now = SystemClock.uptimeMillis()
        if (existing != null && existing.activity.get() === activity && existing.top == spec.top) {
            val last = current
            if (last != null && last.kind == kind && action == null && last.action == null &&
                TextUtils.equals(last.message, message) && now - shownAt < REPEAT_WINDOW_MS
            ) {
                existing.pulse()
            } else {
                existing.bind(spec, animate = true)
            }
            current = spec
            shownAt = now
            scheduleHide(durationOf(activity, spec))
            return
        }
        removeHost()
        if (!attach(activity, spec, durationOf(activity, spec))) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 当前是「进行中」提示时收起它：操作以弹窗收尾（发现新版本、解析失败）时，转圈不该继续挂着。 */
    fun dismissLoading() {
        if (current?.kind == Kind.LOADING) dismiss()
    }

    /** 立即（带退场动画）关闭当前提示。 */
    fun dismiss() {
        handler.removeCallbacks(hideRunnable)
        val h = host ?: return
        host = null
        current = null
        h.exit()
    }

    private fun attach(activity: Activity, spec: Spec, durationMs: Long): Boolean {
        val h = Host(activity, spec.top)
        if (!h.add()) return false
        host = h
        h.bind(spec, animate = false)
        h.enter()
        current = spec
        shownAt = SystemClock.uptimeMillis()
        scheduleHide(durationMs)
        h.announce(spec.message)
        return true
    }

    private fun scheduleHide(durationMs: Long) {
        handler.removeCallbacks(hideRunnable)
        hideAt = SystemClock.uptimeMillis() + durationMs
        handler.postDelayed(hideRunnable, durationMs)
    }

    private fun pauseTimer() = handler.removeCallbacks(hideRunnable)

    private fun resumeTimer() {
        handler.removeCallbacks(hideRunnable)
        hideAt = SystemClock.uptimeMillis() + RESUME_AFTER_TOUCH_MS
        handler.postDelayed(hideRunnable, RESUME_AFTER_TOUCH_MS)
    }

    private fun removeHost() {
        handler.removeCallbacks(hideRunnable)
        host?.removeNow()
        host = null
        current = null
    }

    /** 时长：2 秒起每字加 60ms、最长 4 秒；失败多停 0.8 秒；带按钮 4.5 秒；进行中 10 秒兜底。读屏开启时按系统建议加长。 */
    private fun durationOf(context: Context, spec: Spec): Long {
        val base = when {
            spec.kind == Kind.LOADING -> LOADING_MAX_MS
            spec.action != null -> ACTION_MS
            else -> min(MAX_MS, MIN_MS + PER_CHAR_MS * spec.message.length) + if (spec.kind == Kind.ERROR) ERROR_EXTRA_MS else 0L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val flags = AccessibilityManager.FLAG_CONTENT_TEXT or
                    (if (spec.kind != Kind.INFO) AccessibilityManager.FLAG_CONTENT_ICONS else 0) or
                    (if (spec.action != null) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
                return max(base, am.getRecommendedTimeoutMillis(base.toInt(), flags).toLong())
            }
        }
        return base
    }

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun hookLifecycle(app: Application) {
        if (lifecycleHooked) return
        lifecycleHooked = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                val h = host ?: return
                if (h.activity.get() !== activity) return
                val spec = current ?: return
                carried = spec
                carriedRemaining = hideAt - SystemClock.uptimeMillis()
                carriedAt = SystemClock.uptimeMillis()
                removeHost()
            }

            override fun onActivityResumed(activity: Activity) {
                val spec = carried ?: return
                carried = null
                val remaining = carriedRemaining - (SystemClock.uptimeMillis() - carriedAt)
                // 只接续刚刚发生的页面切换；从后台回来时旧提示已经没有意义
                if (SystemClock.uptimeMillis() - carriedAt > CARRY_WINDOW_MS || remaining < MIN_CARRY_MS) return
                if (host != null) return
                attach(activity, spec, remaining)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (host?.activity?.get() === activity) removeHost()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** 一条提示所在的窗口：外层留出阴影空间，里面是胶囊。 */
    private class Host(activity: Activity, val top: Boolean) {
        val activity = WeakReference(activity)
        private val context: Context = activity
        private val wm = activity.windowManager
        private val density = activity.resources.displayMetrics.density
        private fun dp(v: Float) = (v * density + 0.5f).toInt()

        private val root = FrameLayout(context)
        private val capsule = LinearLayout(context)
        private val icon = IconView(context)
        private val text = TextView(context)
        private val actionView = TextView(context)
        private val capsuleBg = GradientDrawable()
        private var added = false
        private var boundAction: Action? = null

        init {
            val shadow = dp(SHADOW_PAD_DP)
            root.setPadding(shadow, shadow, shadow, shadow)
            root.clipToPadding = false
            root.clipChildren = false

            capsuleBg.setColor(ContextCompat.getColor(context, R.color.toast_bg))
            capsuleBg.cornerRadius = dp(22f).toFloat()
            capsule.background = capsuleBg
            capsule.orientation = LinearLayout.HORIZONTAL
            capsule.gravity = Gravity.CENTER_VERTICAL
            capsule.minimumHeight = dp(44f)
            capsule.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            capsule.elevation = dp(6f).toFloat()
            ViewCompat.setAccessibilityLiveRegion(capsule, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)

            icon.layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(8f) }
            text.setTextColor(ContextCompat.getColor(context, android.R.color.white))
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            text.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            text.maxLines = 2
            text.ellipsize = TextUtils.TruncateAt.END
            text.setLineSpacing(0f, 1.15f)
            actionView.setTextColor(ContextCompat.getColor(context, R.color.toast_action))
            actionView.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            actionView.setTypeface(actionView.typeface, android.graphics.Typeface.BOLD)
            actionView.setPadding(dp(16f), dp(6f), dp(4f), dp(6f))
            actionView.isClickable = true
            actionView.isFocusable = true
            actionView.setOnClickListener {
                val a = boundAction ?: return@setOnClickListener
                boundAction = null
                dismiss()
                a.onClick()
            }

            capsule.addView(icon)
            capsule.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            capsule.addView(actionView)
            root.addView(capsule, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            installGestures()
        }

        fun add(): Boolean {
            val a = activity.get() ?: return false
            val decor = a.window?.decorView ?: return false
            if (decor.windowToken == null) return false
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            lp.y = edgeOffset(a)
            lp.windowAnimations = 0
            lp.title = "AppToast"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            return try {
                wm.addView(root, lp)
                added = true
                true
            } catch (e: RuntimeException) {
                false
            }
        }

        /** 胶囊外沿到屏幕边缘的距离：底部在底栏/导航条/键盘之上 12dp，顶部在状态栏之下 12dp。 */
        private fun edgeOffset(a: Activity): Int {
            val insets = ViewCompat.getRootWindowInsets(a.window.decorView)
            val gap = dp(EDGE_GAP_DP) - dp(SHADOW_PAD_DP)
            if (top) return (insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0) + gap
            val nav = a.findViewById<View?>(R.id.bottomNav)
            val bars = if (nav != null && nav.isShown && nav.height > 0) nav.height
            else insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val ime = if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else 0
            return max(bars, ime) + gap
        }

        fun bind(spec: Spec, animate: Boolean) {
            val apply = {
                icon.kind = spec.kind
                icon.visibility = if (spec.kind == Kind.INFO) View.GONE else View.VISIBLE
                boundAction = spec.action
                actionView.text = spec.action?.label.orEmpty()
                actionView.visibility = if (spec.action != null) View.VISIBLE else View.GONE
                text.text = spec.message
                text.maxWidth = maxTextWidth(spec)
                capsule.contentDescription = spec.message
                // 两行时圆角收一点，胶囊不会变成「药丸」
                text.post {
                    capsuleBg.cornerRadius = dp(if (text.lineCount > 1) 16f else 22f).toFloat()
                }
            }
            if (!animate) {
                apply()
                return
            }
            capsule.animate().cancel()
            text.animate().cancel()
            text.animate().alpha(0f).setDuration(REPLACE_OUT_MS).withEndAction {
                apply()
                text.animate().alpha(1f).setDuration(REPLACE_IN_MS).start()
                icon.alpha = 0f
                icon.animate().alpha(1f).setDuration(REPLACE_IN_MS).start()
            }.start()
            capsule.scaleX = 0.97f
            capsule.scaleY = 0.97f
            capsule.animate().scaleX(1f).scaleY(1f).setDuration(REPLACE_IN_MS + REPLACE_OUT_MS)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }

        private fun maxTextWidth(spec: Spec): Int {
            val screen = context.resources.displayMetrics.widthPixels
            val capsuleMax = min(screen - dp(32f), dp(360f))
            var used = dp(32f)
            if (spec.kind != Kind.INFO) used += dp(26f)
            if (spec.action != null) {
                used += (actionView.paint.measureText(spec.action.label) + dp(20f)).toInt()
            }
            return max(dp(80f), capsuleMax - used)
        }

        fun enter() {
            val dy = dp(16f).toFloat() * if (top) -1 else 1
            capsule.alpha = 0f
            capsule.translationY = dy
            capsule.scaleX = 0.94f
            capsule.scaleY = 0.94f
            capsule.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(ENTER_MS).setInterpolator(OvershootInterpolator(1.1f)).start()
        }

        fun pulse() {
            capsule.animate().cancel()
            capsule.scaleX = 1.04f
            capsule.scaleY = 1.04f
            capsule.animate().scaleX(1f).scaleY(1f).setDuration(PULSE_MS)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }

        fun exit() {
            if (!added) return
            val dy = dp(8f).toFloat() * if (top) -1 else 1
            capsule.animate().cancel()
            capsule.animate().alpha(0f).translationY(capsule.translationY + dy)
                .setDuration(EXIT_MS).setInterpolator(AccelerateInterpolator())
                .withEndAction { removeNow() }.start()
        }

        fun removeNow() {
            if (!added) return
            added = false
            icon.stop()
            try {
                wm.removeViewImmediate(root)
            } catch (e: RuntimeException) {
                // 窗口已随页面一起销毁
            }
        }

        fun announce(message: CharSequence) {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
            if (am.isEnabled) capsule.post { capsule.announceForAccessibility(message) }
        }

        /** 按住暂停计时；轻点关闭（有按钮时点空白不关）；朝屏幕边缘轻扫超过 24dp 关闭。 */
        private fun installGestures() {
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            var downY = 0f
            var moved = false
            capsule.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        moved = false
                        pauseTimer()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY
                        if (abs(dy) > slop) moved = true
                        val toward = if (top) min(0f, dy) else max(0f, dy)
                        capsule.translationY = toward
                        capsule.alpha = 1f - min(0.6f, abs(toward) / dp(80f))
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - downY
                        val swiped = if (top) dy < -dp(24f) else dy > dp(24f)
                        when {
                            swiped -> dismiss()
                            !moved && e.actionMasked == MotionEvent.ACTION_UP && boundAction == null -> {
                                v.performClick()
                                dismiss()
                            }
                            else -> {
                                capsule.animate().translationY(0f).alpha(1f).setDuration(PULSE_MS).start()
                                resumeTimer()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /** 18dp 圆形状态图标：成功绿勾、失败红叹号、进行中白色转圈。 */
    private class IconView(context: Context) : View(context) {
        var kind: Kind = Kind.INFO
            set(value) {
                field = value
                if (value == Kind.LOADING) start() else stop()
                invalidate()
            }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val arc = RectF()
        private var spinner: ValueAnimator? = null
        private var angle = 0f
        private val colSuccess = ContextCompat.getColor(context, R.color.toast_success)
        private val colError = ContextCompat.getColor(context, R.color.toast_error)

        fun start() {
            if (spinner != null) return
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            spinner?.cancel()
            spinner = null
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = min(w, h) / 2f
            val cx = w / 2f
            val cy = h / 2f
            val unit = r / 9f
            when (kind) {
                Kind.SUCCESS -> {
                    fill.color = colSuccess
                    canvas.drawCircle(cx, cy, r, fill)
                    stroke.color = android.graphics.Color.WHITE
                    stroke.strokeWidth = unit * 1.8f
                    path.reset()
                    path.moveTo(cx - unit * 3.6f, cy + unit * 0.2f)
                    path.lineTo(cx - unit * 1f, cy + unit * 2.8f)
                    path.lineTo(cx + unit * 3.8f, cy - unit * 2.4f)
                    canvas.drawPath(path, stroke)
                }
                Kind.ERROR -> {
                    fill.color = colError
                    canvas.drawCircle(cx, cy, r, fill)
                    stroke.color = android.graphics.Color.WHITE
                    stroke.strokeWidth = unit * 2f
                    canvas.drawLine(cx, cy - unit * 4f, cx, cy + unit * 1f, stroke)
                    fill.color = android.graphics.Color.WHITE
                    canvas.drawCircle(cx, cy + unit * 4f, unit * 1.1f, fill)
                }
                Kind.LOADING -> {
                    val sw = unit * 2f
                    stroke.strokeWidth = sw
                    arc.set(cx - r + sw, cy - r + sw, cx + r - sw, cy + r - sw)
                    stroke.color = 0x47FFFFFF
                    canvas.drawOval(arc, stroke)
                    stroke.color = android.graphics.Color.WHITE
                    canvas.drawArc(arc, angle - 90f, 100f, false, stroke)
                }
                Kind.INFO -> Unit
            }
        }
    }

    private const val MIN_MS = 2_000L
    private const val MAX_MS = 4_000L
    private const val PER_CHAR_MS = 60L
    private const val ERROR_EXTRA_MS = 800L
    private const val ACTION_MS = 4_500L
    private const val LOADING_MAX_MS = 10_000L
    private const val REPEAT_WINDOW_MS = 3_000L
    private const val RESUME_AFTER_TOUCH_MS = 1_500L
    private const val CARRY_WINDOW_MS = 1_500L
    private const val MIN_CARRY_MS = 600L
    private const val ENTER_MS = 240L
    private const val EXIT_MS = 160L
    private const val REPLACE_OUT_MS = 90L
    private const val REPLACE_IN_MS = 120L
    private const val PULSE_MS = 180L
    private const val EDGE_GAP_DP = 12f
    private const val SHADOW_PAD_DP = 10f
}
