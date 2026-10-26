package com.zhusijiao.app.ui.common

import android.content.Context
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import java.util.Calendar

/**
 * 选「第 1 周是哪一周」的底部面板：一列滚轮，每项是一整周「9月1日 – 9月7日」。
 *
 * 比选一个日期更直接：全 App 约定第 1 周从周一算起，让用户选日期再告诉他「已按所在周的周一算起」，
 * 不如一开始就只给整周可选。候选是当前选中那周前后各 [SPAN] 周，跨年时带上年份。
 */
class WeekStartSheet(
    context: Context,
    /** 当前的开学日期（YYYY-MM-DD）；空时从本周起算。 */
    initial: String,
    private val onPick: (monday: String) -> Unit
) : SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_week_start)
        setCanceledOnTouchOutside(true)

        val anchor = DateUtils.weekStartOf(
            initial.takeIf { DateUtils.hasSemesterStart(it) } ?: DateUtils.formatDate(Calendar.getInstance())
        )
        val mondays = (-SPAN..SPAN).map { shift(anchor, it * 7) }
        val wheel = findViewById<WheelView>(R.id.weekStartWheel)
        wheel.configure(mondays.map { label(context, it) }, SPAN) {}
        findViewById<TextView>(R.id.weekStartDone).setOnClickListener {
            val picked = mondays.getOrNull(wheel.selectedIndex) ?: return@setOnClickListener
            dismiss()
            onPick(picked)
        }
    }

    companion object {
        private const val SPAN = 30

        /** 「9月1日 – 9月7日」；不是今年的周带上年份。 */
        fun label(context: Context, monday: String): String {
            val days = DateUtils.datesForWeek(monday, 1)
            val first = days.first()
            val last = days.last()
            val year = monday.substringBefore('-')
            val thisYear = Calendar.getInstance().get(Calendar.YEAR).toString()
            val start = context.getString(R.string.widget_date, first.month, first.day)
            val text = context.getString(R.string.import_week_span, start, context.getString(R.string.widget_date, last.month, last.day))
            return if (year == thisYear) text else context.getString(R.string.import_week_span_year, year, text)
        }

        private fun shift(iso: String, days: Int): String {
            val parts = iso.split("-").map { it.toInt() }
            val cal = Calendar.getInstance().apply {
                clear()
                set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
                add(Calendar.DAY_OF_MONTH, days)
            }
            return DateUtils.formatDate(cal)
        }
    }
}
