package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Leaves
import java.util.Calendar

/**
 * 选一个「日期 + 时刻」的底部面板：日期、时、分三列滚轮，逐分钟可选。
 *
 * 日期列不是无限滚动的日历，而是给定的一串候选日（[dates]，通常是整个学期）：
 * 请假只跟有课的日子有关，范围有限反而好找，也顺带挡掉了「选到明年」这种手滑。
 */
class DateTimeSheet(
    context: Context,
    title: String,
    /** 候选日期（YYYY-MM-DD，升序）。 */
    private val dates: List<String>,
    /** 初始值 "YYYY-MM-DD HH:mm"；日期不在候选里时落到最近的一天。 */
    initial: String,
    private val onConfirm: (moment: String) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_date_time)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.dateTimeTitle).text = title

        val dateWheel = findViewById<WheelView>(R.id.dateWheel)
        val hourWheel = findViewById<WheelView>(R.id.hourWheel)
        val minuteWheel = findViewById<WheelView>(R.id.minuteWheel)

        val today = DateUtils.formatDate(Calendar.getInstance())
        val todayLabel = context.getString(R.string.leave_today)
        // 「10月8日 周四」；今天把星期换成「今天」，长度不变
        val labels = dates.map { iso ->
            val text = Leaves.displayMoment(Leaves.moment(iso, 0)).dropLast(6)
            if (iso == today) text.dropLast(2) + todayLabel else text
        }
        val initialDate = Leaves.dateOf(initial)
        val dateIndex = dates.indexOf(initialDate).takeIf { it >= 0 }
            ?: dates.indexOfFirst { it >= initialDate }.takeIf { it >= 0 }
            ?: dates.lastIndex
        val minutes = Leaves.minutesOf(initial)
        val hourUnit = context.getString(R.string.time_range_hour_unit)
        val minuteUnit = context.getString(R.string.time_range_minute_unit)

        dateWheel.configure(labels, dateIndex.coerceAtLeast(0)) {}
        hourWheel.configure((0..23).map { "%02d".format(it) }, minutes / 60, { "$it$hourUnit" }) {}
        minuteWheel.configure((0..59).map { "%02d".format(it) }, minutes % 60, { "$it$minuteUnit" }) {}

        findViewById<View>(R.id.dateTimeConfirm).setOnClickListener {
            val date = dates.getOrNull(dateWheel.selectedIndex) ?: return@setOnClickListener
            dismiss()
            onConfirm(Leaves.moment(date, hourWheel.selectedIndex * 60 + minuteWheel.selectedIndex))
        }
    }
}
