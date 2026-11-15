package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import kotlin.math.roundToInt

/**
 * 周次选择底部面板（替代系统选择器），风格与课程详情抽屉一致：
 * 顶部把手 + 标题 + 「回到本周」快捷项 + 周次宫格（当前周高亮、已选周实心）。
 * 每个周次下面直接标出那一周的起始日期，不用用户自己换算「第 13 周是几月几号」。
 */
class WeekPickerSheet(
    context: Context,
    private val totalWeeks: Int,
    private val semesterStart: String,
    private val selectedWeek: Int,
    private val currentWeekNumber: Int,
    private val onPick: (Int) -> Unit
) : SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_week_picker)
        setCanceledOnTouchOutside(true)

        findViewById<TextView>(R.id.pickerTitle).text = context.getString(R.string.index_week_title, selectedWeek)
        val backToCurrent = findViewById<TextView>(R.id.backToCurrent)
        if (selectedWeek == currentWeekNumber || currentWeekNumber !in 1..totalWeeks) {
            backToCurrent.visibility = View.GONE
        } else {
            backToCurrent.setOnClickListener { onPick(currentWeekNumber); dismiss() }
        }

        buildGrid()
    }

    private fun buildGrid() {
        val rows = findViewById<LinearLayout>(R.id.weekRows)
        val showDates = DateUtils.hasSemesterStart(semesterStart)
        val perRow = 5
        var week = 1
        while (week <= totalWeeks) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }
            for (col in 0 until perRow) {
                val w = week + col
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(if (showDates) 48f else 44f), 1f)
                        .apply { if (col < perRow - 1) marginEnd = dp(8f) }
                }
                if (w > totalWeeks) {
                    cell.visibility = View.INVISIBLE
                    row.addView(cell)
                    continue
                }
                val start = if (showDates) {
                    DateUtils.datesForWeek(semesterStart, w).firstOrNull()
                } else {
                    null
                }
                val isSelected = w == selectedWeek
                val isCurrent = w == currentWeekNumber
                val numberColor = when {
                    isSelected -> Color.WHITE
                    isCurrent -> ContextCompat.getColor(context, R.color.accent_strong)
                    else -> ContextCompat.getColor(context, R.color.text_primary)
                }
                cell.setBackgroundResource(
                    when {
                        isSelected -> R.drawable.bg_week_chip_selected
                        isCurrent -> R.drawable.bg_tag
                        else -> R.drawable.bg_week_chip
                    }
                )
                cell.addView(TextView(context).apply {
                    text = w.toString()
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    textSize = 14f
                    setTextColor(numberColor)
                    if (isSelected) typeface = Typeface.DEFAULT_BOLD
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                if (start != null) {
                    cell.addView(TextView(context).apply {
                        text = context.getString(R.string.week_picker_week_start, start.month, start.day)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        textSize = 9.5f
                        // 选中态底色是实心强调色，日期用同色系浅一档保证对比度。
                        setTextColor(
                            if (isSelected) Color.WHITE
                            else ContextCompat.getColor(context, R.color.text_tertiary)
                        )
                        alpha = if (isSelected) 0.85f else 1f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(1f) }
                    })
                }
                cell.isClickable = true
                cell.isFocusable = true
                cell.contentDescription = if (start != null) {
                    context.getString(R.string.week_picker_chip_dated, w, start.month, start.day)
                } else {
                    context.getString(R.string.week_picker_chip_plain, w)
                }
                cell.setOnClickListener { onPick(w); dismiss() }
                row.addView(cell)
            }
            rows.addView(row)
            week += perRow
        }
    }

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).roundToInt()
}
