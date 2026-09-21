package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.util.Rpx

/**
 * 节次选择网格：12 节一次全部铺开（4 列 × 3 行），每格是「节次号 + 上课时间」。
 *
 * 刻意不用横向滚动的胶囊行——那会把第 11、12 节藏在屏幕边界外，用户既不知道还有，
 * 也看不出能滚；而且「起始节 + 时长几节」是让用户自己做算术，不如直接点第几节。
 *
 * 点选是两步区间语义，与调休面板选补课日同一套心智：
 * 第一下定起点、第二下定终点，第三下重新开始。
 */
class SectionPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 当前选中的节次区间；未选择时为 null。 */
    var selection: IntRange? = null
        private set

    /** 是否只点了起点、还等着点终点（界面据此显示「再点一格选一段」的提示）。 */
    val awaitingEnd: Boolean get() = selection?.let { it.first == it.last && pinned } == true

    private var pinned = false
    private var onChanged: ((IntRange?) -> Unit)? = null
    private val cells = mutableListOf<LinearLayout>()

    init {
        orientation = VERTICAL
    }

    /**
     * @param slots 作息（每格显示对应节次的开始时间）
     * @param initial 初始选中的区间；null 表示不选
     */
    fun configure(
        slots: List<TimeSlot>,
        initial: IntRange?,
        onChanged: (IntRange?) -> Unit
    ) {
        this.onChanged = onChanged
        removeAllViews()
        cells.clear()
        val count = slots.size
        var index = 0
        while (index < count) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = Rpx.dp(CELL_GAP_DP) }
            }
            for (column in 0 until COLUMNS) {
                val slot = slots.getOrNull(index + column)
                row.addView(if (slot == null) spacer(column) else cell(slot, column))
            }
            addView(row)
            index += COLUMNS
        }
        selection = initial
        pinned = false
        applySelection()
    }

    /** 外部（如精确时间的换算建议）直接改选区。 */
    fun setSelection(range: IntRange?, notify: Boolean = false) {
        selection = range
        pinned = false
        applySelection()
        if (notify) onChanged?.invoke(selection)
    }

    private fun cell(slot: TimeSlot, column: Int): LinearLayout {
        val number = TextView(context).apply {
            text = slot.number.toString()
            gravity = Gravity.CENTER
            includeFontPadding = false
            isDuplicateParentStateEnabled = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColorStateList(context, R.color.reschedule_choice_text))
        }
        val time = TextView(context).apply {
            text = slot.startTime
            gravity = Gravity.CENTER
            includeFontPadding = false
            isDuplicateParentStateEnabled = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            alpha = 0.82f
            setTextColor(ContextCompat.getColorStateList(context, R.color.reschedule_choice_text))
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            // 宽度四列平分；高度**不写死**——写死的话用户把系统字体调大后，
            // 里面的 sp 文字会撑出盒子被裁掉。交给内容决定，minHeight 保证触摸目标够大。
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (column > 0) marginStart = Rpx.dp(CELL_GAP_DP)
            }
            minimumHeight = Rpx.dp(CELL_MIN_HEIGHT_DP)
            val padV = Rpx.dp(7f)
            setPadding(0, padV, 0, padV)
            setBackgroundResource(R.drawable.bg_reschedule_choice)
            isClickable = true
            isFocusable = true
            tag = slot.number
            contentDescription = "第${slot.number}节 ${slot.startTime}"
            addView(number)
            addView(time)
            setOnClickListener { pick(slot.number) }
            cells += this
        }
    }

    /** 最后一行不足 4 格时补空位，保证各列宽度一致。 */
    private fun spacer(column: Int) = TextView(context).apply {
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            if (column > 0) marginStart = Rpx.dp(CELL_GAP_DP)
        }
    }

    /**
     * 两步区间：没选或已是完整区间时重新起头，只点了起点时把区间补全。
     * 再点一次同一格则取消选择，给用户一个明确的「反悔」出口。
     */
    private fun pick(section: Int) {
        val current = selection
        selection = when {
            current == null -> section..section
            current.first == current.last && current.first == section && pinned -> null
            current.first == current.last && pinned ->
                minOf(current.first, section)..maxOf(current.first, section)
            else -> section..section
        }
        pinned = selection?.let { it.first == it.last } == true
        applySelection()
        onChanged?.invoke(selection)
    }

    private fun applySelection() {
        val range = selection
        cells.forEach { cell ->
            val number = cell.tag as? Int ?: return@forEach
            cell.isSelected = range != null && number in range
        }
    }

    companion object {
        private const val COLUMNS = 4
        /** 格子最小高度；字体放大时格子会跟着长高，不会裁字。 */
        private const val CELL_MIN_HEIGHT_DP = 46f
        private const val CELL_GAP_DP = 5f
    }
}
