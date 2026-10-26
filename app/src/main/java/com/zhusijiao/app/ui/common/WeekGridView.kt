package com.zhusijiao.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx

/**
 * 周次宫格：每行 5 格，点一下切换这一周，按住划过一串格子可以连续选 / 连续取消。
 *
 * 划选的方向由按下的那一格决定：按在没选的格子上就是「一路选上」，按在已选的格子上就是「一路取消」，
 * 和相册多选是同一个手势，不需要任何说明文字。
 */
class WeekGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 当前选中的周次（升序）。 */
    val selection: List<Int> get() = selected.sorted()

    private val selected = sortedSetOf<Int>()
    private val cells = mutableListOf<TextView>()
    private var onChanged: ((List<Int>) -> Unit)? = null

    /** 划选中：true 表示一路选上，false 表示一路取消；null 表示没在划。 */
    private var paintMode: Boolean? = null
    private var lastWeek = 0
    private val hit = Rect()

    init {
        orientation = VERTICAL
    }

    fun configure(totalWeeks: Int, initial: Collection<Int>, onChanged: (List<Int>) -> Unit) {
        this.onChanged = onChanged
        removeAllViews()
        cells.clear()
        var week = 1
        while (week <= totalWeeks) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { if (week > 1) topMargin = Rpx.dp(GAP_DP) }
            }
            for (column in 0 until COLUMNS) {
                val value = week + column
                row.addView(if (value <= totalWeeks) cell(value, column) else spacer(column))
            }
            addView(row)
            week += COLUMNS
        }
        setSelection(initial)
    }

    /** 外部（快捷项「每周 / 单周 / 双周」）整体替换选区，会回调 onChanged。 */
    fun setSelection(weeks: Collection<Int>, notify: Boolean = false) {
        selected.clear()
        selected.addAll(weeks.filter { it in 1..cells.size })
        cells.forEach { it.isSelected = (it.tag as Int) in selected }
        if (notify) onChanged?.invoke(selection)
    }

    private fun cell(week: Int, column: Int) = TextView(context).apply {
        text = week.toString()
        tag = week
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = Rpx.dp(CELL_MIN_HEIGHT_DP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ContextCompat.getColorStateList(context, R.color.reschedule_choice_text))
        setBackgroundResource(R.drawable.bg_reschedule_choice)
        contentDescription = context.getString(R.string.week_picker_chip_plain, week)
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            if (column > 0) marginStart = Rpx.dp(GAP_DP)
        }
        // 触摸由宫格统一处理（为了划选）；这里的点击只给无障碍服务和键盘用
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle(week) }
        cells += this
    }

    private fun spacer(column: Int) = TextView(context).apply {
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            if (column > 0) marginStart = Rpx.dp(GAP_DP)
        }
    }

    private fun toggle(week: Int) {
        apply(week, week !in selected)
        onChanged?.invoke(selection)
    }

    private fun apply(week: Int, select: Boolean) {
        if (select) selected.add(week) else selected.remove(week)
        cells.getOrNull(week - 1)?.isSelected = select
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val week = weekAt(event.x, event.y) ?: return false
                // 底部面板会把下拉当成关闭手势，按在格子上时声明独占
                parent?.requestDisallowInterceptTouchEvent(true)
                paintMode = week !in selected
                lastWeek = week
                apply(week, paintMode == true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onChanged?.invoke(selection)
            }
            MotionEvent.ACTION_MOVE -> {
                val mode = paintMode ?: return true
                val week = weekAt(event.x, event.y) ?: return true
                if (week == lastWeek) return true
                lastWeek = week
                if ((week in selected) != mode) {
                    apply(week, mode)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onChanged?.invoke(selection)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                paintMode = null
                lastWeek = 0
            }
        }
        return true
    }

    private fun weekAt(x: Float, y: Float): Int? {
        for (cell in cells) {
            cell.getHitRect(hit)
            val row = cell.parent as? LinearLayout ?: continue
            hit.offset(row.left, row.top)
            if (hit.contains(x.toInt(), y.toInt())) return cell.tag as Int
        }
        return null
    }

    companion object {
        private const val COLUMNS = 5
        private const val CELL_MIN_HEIGHT_DP = 44f
        private const val GAP_DP = 6f
    }
}
