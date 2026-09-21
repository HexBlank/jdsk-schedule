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
import com.zhusijiao.app.util.Rpx

/** 项目统一的单选分段控件，不依赖系统 RadioButton/Spinner 外观。 */
class SegmentedChoiceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var selectedIndex: Int = 0
        private set

    private var labels: List<String> = emptyList()
    private var onSelected: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun configure(
        items: List<String>,
        initialIndex: Int,
        contentDescriptionPrefix: String,
        onSelected: (Int) -> Unit
    ) {
        labels = items
        this.onSelected = onSelected
        removeAllViews()
        items.forEachIndexed { index, label ->
            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                includeFontPadding = false
                // 高度交给内容 + minHeight：写死高度时系统字体调大会把文字裁掉
                minHeight = Rpx.dp(48f)
                val padV = Rpx.dp(6f)
                setPadding(0, padV, 0, padV)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColorStateList(context, R.color.reschedule_choice_text))
                setBackgroundResource(R.drawable.bg_reschedule_choice)
                isClickable = true
                isFocusable = true
                contentDescription = "$contentDescriptionPrefix$label"
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = Rpx.dp(5f)
                }
                setOnClickListener { setSelection(index, notify = true) }
            })
        }
        setSelection(initialIndex, notify = false)
    }

    fun setSelection(index: Int, notify: Boolean = false) {
        if (labels.isEmpty()) return
        selectedIndex = index.coerceIn(labels.indices)
        for (childIndex in 0 until childCount) {
            (getChildAt(childIndex) as? TextView)?.apply {
                isSelected = childIndex == selectedIndex
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        if (notify) onSelected?.invoke(selectedIndex)
    }
}
