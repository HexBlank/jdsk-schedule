package com.zhusijiao.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx

/**
 * 项目统一的数值步进控件：点按 ±1；长按 ± 后自动连发（先慢后快），不用一下一下地点。
 * 固定尺寸、触摸区与配色，不使用系统 Spinner。
 */
class StepperFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var value: Int = 1
        private set

    private var minimum = 1
    private var maximum = 1
    private var formatter: (Int) -> String = { it.toString() }
    private var onChanged: ((Int) -> Unit)? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeating = false
    private var repeatTask: Runnable? = null

    private val decrease = actionText("−", R.string.reschedule_decrease)
    private val valueText = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ContextCompat.getColor(context, R.color.heading_ink))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    }
    private val increase = actionText("+", R.string.reschedule_increase)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Rpx.dp(52f)
        setBackgroundResource(R.drawable.bg_reschedule_control)
        clipToOutline = true
        addView(decrease)
        addView(divider())
        addView(valueText)
        addView(divider())
        addView(increase)
        decrease.setOnClickListener { changeBy(-1) }
        increase.setOnClickListener { changeBy(1) }
        decrease.setOnLongClickListener { startRepeat(-1); true }
        increase.setOnLongClickListener { startRepeat(1); true }
        bindAutoRepeat(decrease)
        bindAutoRepeat(increase)
    }

    fun configure(
        minimum: Int,
        maximum: Int,
        initialValue: Int,
        formatter: (Int) -> String,
        onChanged: (Int) -> Unit
    ) {
        this.minimum = minimum
        this.maximum = maximum.coerceAtLeast(minimum)
        this.formatter = formatter
        this.onChanged = onChanged
        setValue(initialValue, notify = false)
    }

    fun setValue(newValue: Int, notify: Boolean = false) {
        value = newValue.coerceIn(minimum, maximum)
        valueText.text = formatter(value)
        decrease.isEnabled = value > minimum
        increase.isEnabled = value < maximum
        decrease.alpha = if (decrease.isEnabled) 1f else 0.3f
        increase.alpha = if (increase.isEnabled) 1f else 0.3f
        if (notify) onChanged?.invoke(value)
    }

    private fun changeBy(delta: Int) {
        val next = (value + delta).coerceIn(minimum, maximum)
        if (next != value) setValue(next, notify = true)
    }

    /**
     * 长按连发：手指抬起即停。抬起时若处于连发状态则消费事件，避免再触发一次点按。
     * 这里刻意不调用 performClick——连发期间数值已经改过了，再补一次点按会多加/减一格；
     * 按钮本身设了 OnClickListener，无障碍服务仍可正常点击。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindAutoRepeat(button: View) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (repeating) {
                        stopRepeat()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun startRepeat(delta: Int) {
        repeating = true
        var delay = 320L
        val task = object : Runnable {
            override fun run() {
                changeBy(delta)
                repeatHandler.postDelayed(this, delay)
                delay = (delay * 2 / 3).coerceAtLeast(60L)
            }
        }
        repeatTask = task
        repeatHandler.postDelayed(task, delay)
    }

    private fun stopRepeat() {
        repeating = false
        repeatTask?.let { repeatHandler.removeCallbacks(it) }
        repeatTask = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
    }

    private fun actionText(symbol: String, descriptionRes: Int) = TextView(context).apply {
        text = symbol
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ContextCompat.getColor(context, R.color.accent_strong))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundResource(R.drawable.bg_reschedule_step_action)
        isClickable = true
        isFocusable = true
        isLongClickable = true
        contentDescription = context.getString(descriptionRes)
        layoutParams = LayoutParams(Rpx.dp(52f), LayoutParams.MATCH_PARENT)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.line))
        layoutParams = LayoutParams(1, Rpx.dp(26f)).apply { gravity = Gravity.CENTER_VERTICAL }
    }
}