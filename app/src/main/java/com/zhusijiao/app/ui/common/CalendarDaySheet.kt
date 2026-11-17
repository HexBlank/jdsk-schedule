package com.zhusijiao.app.ui.common

import android.content.Context
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Schedule

/** 从明确的日期字段打开，点选一天即返回表单；星期栏不随日历滚动。 */
class CalendarDaySheet(
    context: Context,
    title: String,
    schedule: Schedule,
    selected: Int,
    selectable: Set<Int>? = null,
    range: IntRange? = null,
    onPick: (Int) -> Unit
) : SheetDialog(context) {
    init {
        setContentView(R.layout.dialog_calendar_day)
        setPanelHeight((context.resources.displayMetrics.heightPixels * 0.82f).toInt())
        findViewById<TextView>(R.id.calendarDayTitle).text = title
        val calendar = findViewById<HolidayCalendarView>(R.id.calendarDayCalendar)
        calendar.configure(schedule)
        calendar.setSelectableDays(selectable)
        val focus = if (selectable == null || selected in selectable) selected
            else selectable.minByOrNull { kotlin.math.abs(it - selected) } ?: selected
        if (range != null) calendar.setSelection(range.first, range.last, rangeSelection = true)
        else calendar.setSelection(if (selectable == null || selected in selectable) selected else -1, -1)
        calendar.onDayClick = { day -> dismiss(); onPick(day.index) }
        calendar.doOnLayout {
            findViewById<NestedScrollView>(R.id.calendarDayScroll).scrollTo(0, calendar.scrollTopFor(focus))
        }
    }
}
