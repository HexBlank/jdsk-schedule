package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Rpx

/**
 * 横向滚动的单选胶囊行：直接点选目标值（周次、节次等离散选项），
 * 替代一下一下 ± 的步进器；选中项高亮并自动滚动到可视区域。
 * 与 SegmentedChoiceView 同一选择样式（bg_reschedule_choice）。
 */
class ChipRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    private var labels: List<String> = emptyList()
    private var onSelected: ((Int) -> Unit)? = null
    private var selectedIndex = 0

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(row)
    }

    fun configure(
        items: List<String>,
        initialIndex: Int,
        contentDescriptionPrefix: String = "",
        contentDescriptionSuffix: String = "",
        onSelected: (Int) -> Unit
    ) {
        labels = items
        this.onSelected = onSelected
        row.removeAllViews()
        items.forEachIndexed { index, label ->
            row.addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(Rpx.dp(11f), 0, Rpx.dp(11f), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(ContextCompat.getColorStateList(context, R.color.reschedule_choice_text))
                setBackgroundResource(R.drawable.bg_reschedule_choice)
                isClickable = true
                isFocusable = true
                contentDescription = "$contentDescriptionPrefix$label$contentDescriptionSuffix"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    Rpx.dp(36f)
                ).apply { if (index > 0) marginStart = Rpx.dp(6f) }
                setOnClickListener { setSelection(index, notify = true) }
            })
        }
        setSelection(initialIndex.coerceIn(labels.indices), notify = false)
        post { reveal(selectedIndex) }
    }

    fun setSelection(index: Int, notify: Boolean = false) {
        if (labels.isEmpty()) return
        selectedIndex = index.coerceIn(labels.indices)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as TextView).apply {
                isSelected = i == selectedIndex
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        reveal(selectedIndex)
        if (notify) onSelected?.invoke(selectedIndex)
    }

    /** 当前选中下标。 */
    val selection: Int get() = selectedIndex

    private fun reveal(index: Int) {
        val child = row.getChildAt(index) ?: return
        post {
            if (width > 0) {
                smoothScrollTo(
                    (child.left + child.width / 2f - width / 2f).toInt().coerceAtLeast(0),
                    0
                )
            }
        }
    }
}