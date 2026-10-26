package com.zhusijiao.app.ui.common

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * 全站共用的交互动效基础件（不引入 dynamicanimation，见 DECISIONS D1）。
 *
 * 规矩只有几条（照 WWDC18「Designing Fluid Interfaces」）：
 * - 跟手、松手吸附、回弹一律用 [Spring]：换目标时保留当前速度，随时能被手指接住；
 * - 松手时把手指速度交给弹簧（[Spring.springTo] 的 velocity），甩得快就走得快；
 * - 只用缓出，不用先慢后快的 Accelerate 类曲线：关掉一个东西应该越快越好；
 * - 回弹（bounce > 0）只奖励手势动量；点按触发的开合用无回弹的弹簧。
 */
object Motion {

    /** 不方便用弹簧的定时动画（ViewPropertyAnimator 之类）统一用这条缓出曲线：起步快、收尾柔。 */
    val EASE_OUT: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 系统没关动画（开发者选项 / 无障碍「移除动画」）。弹簧是自己积分的，不受时长缩放影响，得自己判断。 */
    fun enabled(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    /**
     * 橡皮筋阻尼：越拉越重，最多走到 [limit]。与 iOS 的 rubber band 同形（系数 0.55）。
     * [overshoot] 是手指越界的距离，返回元素实际越界的距离，符号不变。
     */
    fun rubberBand(overshoot: Float, limit: Float): Float {
        if (limit <= 0f) return 0f
        val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
        return limit * pull * sign(overshoot)
    }

    /** [rubberBand] 的反函数：从已经被阻尼过的位置接着拖时，换算回手指位置，免得一接手就跳。 */
    fun rubberBandInverse(value: Float, limit: Float): Float {
        if (limit <= 0f) return 0f
        val pull = (abs(value) / limit).coerceAtMost(0.95f)
        return limit / 0.55f * (1f / (1f - pull) - 1f) * sign(value)
    }

    /**
     * 动量投射：以 [velocity]（每秒）甩出、按 iOS 滚动的减速率自然停下时还会再走多远。
     * 用来决定「松手后该落到哪」：比只看位移或只看速度阈值都更贴近手感。
     */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        velocity / 1000f * decelerationRate / (1f - decelerationRate)
}

/** 弹簧参数：与 Flutter 的 SpringDescription.withDurationAndBounce 同一套换算（质量为 1）。 */
class SpringSpec(durationSeconds: Float, bounce: Float = 0f) {
    val stiffness = (2f * Math.PI.toFloat() / durationSeconds).let { it * it }
    val damping = 4f * Math.PI.toFloat() * (1f - bounce) / durationSeconds

    companion object {
        /** 跟手：几乎贴着手指走，只滤掉触摸采样的抖动。 */
        val TRACK = SpringSpec(0.12f)
        /** 利落：点按触发的开合、松手吸附，无回弹。 */
        val SNAPPY = SpringSpec(0.32f)
        /** 柔和：页面级的大位移（翻周、面板进场），无回弹。 */
        val SMOOTH = SpringSpec(0.42f)
        /** 带动量：手势甩出后的落位，允许一点回弹作为奖励。 */
        val MOMENTUM = SpringSpec(0.42f, bounce = 0.12f)
        /** 按下 / 松开的缩放反馈。 */
        val PRESS = SpringSpec(0.22f)
    }
}

/** 目标可以每一帧都变的弹簧：换目标时保留当前速度，所以跟手拖动和松手回弹之间没有顿挫。 */
class Spring(var value: Float, private val restDelta: Float) {
    var velocity = 0f
        private set
    var target = value
        private set
    var running = false
        private set
    private var spec: SpringSpec? = null

    /** 弹向 [target]；传了 [velocity] 就以它为初速（如松手时的手指速度），否则沿用当前速度。 */
    fun springTo(target: Float, spec: SpringSpec, velocity: Float = this.velocity) {
        this.target = target
        this.spec = spec
        this.velocity = velocity
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

/**
 * 把一组 [Spring] 挂到 [view] 的动画帧上：任一根还在动就继续逐帧推进并回调 [onFrame]，
 * 全部停下后回调 [onSettled]（只在由「在动」变为「全停」时回调一次）。
 */
class SpringDriver(
    private val view: View,
    private val springs: Array<Spring>,
    private val onFrame: () -> Unit
) {
    var onSettled: (() -> Unit)? = null
    private var lastFrame = 0L
    private var framing = false
    private val frame = Runnable { step() }

    val running: Boolean get() = springs.any { it.running }

    fun start() {
        if (framing) return
        framing = true
        lastFrame = AnimationUtils.currentAnimationTimeMillis()
        view.postOnAnimation(frame)
    }

    /** 停止逐帧推进，弹簧停在当前值（不跳到终点）。 */
    fun stop() {
        view.removeCallbacks(frame)
        framing = false
    }

    private fun step() {
        val now = AnimationUtils.currentAnimationTimeMillis()
        // 掉帧或刚回到前台时一步不要迈太大，否则弹簧会一下子甩出去
        val dt = ((now - lastFrame).coerceIn(1L, 34L)) / 1000f
        lastFrame = now
        var moving = false
        for (spring in springs) {
            spring.advance(dt)
            moving = moving || spring.running
        }
        onFrame()
        // onFrame 里可能已经 stop()（如面板关完），不要再续帧
        if (!framing) return
        if (moving && view.isAttachedToWindow) {
            view.postOnAnimation(frame)
        } else {
            framing = false
            if (!moving) onSettled?.invoke()
        }
    }
}
