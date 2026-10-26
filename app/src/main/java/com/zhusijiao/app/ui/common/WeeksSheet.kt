package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.util.Rpx

/**
 * 选周次的底部面板（加课、加日程共用）：上面一排快捷项，下面是可以点、也可以划选的周次宫格。
 *
 * 快捷项都从 [from] 周算到学期末：课从第 1 周，日程从它所在的那一周。
 * [onlyWeek] 不为空时多一个「仅第 N 周」，给一次性的日程用。
 * 当前选区恰好等于某个快捷项时，那一项亮起，所以不需要另写「当前：每周」。
 */
class WeeksSheet(
    context: Context,
    private val totalWeeks: Int,
    private val from: Int,
    initial: List<Int>,
    private val onlyWeek: Int? = null,
    private val onDone: (List<Int>) -> Unit
) : SheetDialog(context) {

    private val title by lazy { findViewById<TextView>(R.id.weeksTitle) }
    private val grid by lazy { findViewById<WeekGridView>(R.id.weeksGrid) }
    private val done by lazy { findViewById<TextView>(R.id.weeksDone) }
    private val presets = mutableListOf<Pair<TextView, List<Int>>>()

    init {
        setContentView(R.layout.dialog_weeks)
        setCanceledOnTouchOutside(true)
        val start = from.coerceIn(1, totalWeeks)
        val range = start..totalWeeks
        val options = buildList {
            onlyWeek?.let { add(context.getString(R.string.weeks_preset_only, it) to listOf(it)) }
            add(context.getString(R.string.event_repeat_weekly) to range.toList())
            add(context.getString(R.string.event_repeat_odd) to range.filter { it % 2 == 1 })
            add(context.getString(R.string.event_repeat_even) to range.filter { it % 2 == 0 })
        }
        val row = findViewById<LinearLayout>(R.id.weeksPresets)
        options.forEachIndexed { index, (label, weeks) ->
            val chip = chip(label, index)
            chip.setOnClickListener { grid.setSelection(weeks, notify = true) }
            row.addView(chip)
            presets += chip to weeks
        }
        grid.configure(totalWeeks, initial) { render() }
        done.setOnClickListener {
            val weeks = grid.selection
            if (weeks.isEmpty()) return@setOnClickListener
            dismiss()
            onDone(weeks)
        }
        render()
    }

    private fun chip(label: String, index: Int) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        minHeight = Rpx.dp(40f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ContextCompat.getColorStateList(context, R.color.reschedule_choice_text))
        setBackgroundResource(R.drawable.bg_reschedule_choice)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = Rpx.dp(6f)
        }
    }

    private fun render() {
        val weeks = grid.selection
        title.text = if (weeks.isEmpty()) context.getString(R.string.weeks_none)
        else ScheduleView.formatWeekSummary(weeks)
        presets.forEach { (chip, value) ->
            chip.isSelected = value.isNotEmpty() && value == weeks
            chip.typeface = if (chip.isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        done.isEnabled = weeks.isNotEmpty()
    }
}
