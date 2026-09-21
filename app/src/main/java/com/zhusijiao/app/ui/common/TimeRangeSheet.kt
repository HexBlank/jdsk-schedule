package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R

/**
 * 自定义时间面板：开始与结束各两列滚轮（时 / 分），底部确定。
 *
 * 逐分钟可选——学校作息本身就有 11:31 这种非整五的时刻，按 5 分钟对齐连自家节次
 * 的开始时间都表示不出来。结束早于开始时实时提示并禁用确定，不等点了才报错。
 */
class TimeRangeSheet(
    context: Context,
    private val initialStart: Int,
    private val initialEnd: Int,
    private val onConfirm: (startMinutes: Int, endMinutes: Int) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    private val startHour by lazy { findViewById<WheelView>(R.id.startHourWheel) }
    private val startMinute by lazy { findViewById<WheelView>(R.id.startMinuteWheel) }
    private val endHour by lazy { findViewById<WheelView>(R.id.endHourWheel) }
    private val endMinute by lazy { findViewById<WheelView>(R.id.endMinuteWheel) }

    init {
        setContentView(R.layout.dialog_time_range)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        val hours = (0..23).map { "%02d".format(it) }
        val minutes = (0..59).map { "%02d".format(it) }
        val startLabel = context.getString(R.string.time_range_start)
        val endLabel = context.getString(R.string.time_range_end)
        val hourUnit = context.getString(R.string.time_range_hour_unit)
        val minuteUnit = context.getString(R.string.time_range_minute_unit)

        startHour.configure(hours, initialStart / 60, { "$startLabel $it$hourUnit" }) { updateState() }
        startMinute.configure(minutes, initialStart % 60, { "$startLabel $it$minuteUnit" }) { updateState() }
        endHour.configure(hours, initialEnd / 60, { "$endLabel $it$hourUnit" }) { updateState() }
        endMinute.configure(minutes, initialEnd % 60, { "$endLabel $it$minuteUnit" }) { updateState() }
        updateState()

        findViewById<View>(R.id.timeRangeConfirm).setOnClickListener {
            if (!isValid()) return@setOnClickListener
            dismiss()
            onConfirm(startMinutes(), endMinutes())
        }
    }

    private fun startMinutes() = startHour.selectedIndex * 60 + startMinute.selectedIndex

    private fun endMinutes() = endHour.selectedIndex * 60 + endMinute.selectedIndex

    private fun isValid() = endMinutes() > startMinutes()

    private fun updateState() {
        val valid = isValid()
        findViewById<TextView>(R.id.timeRangeError).visibility =
            if (valid) View.GONE else View.VISIBLE
        findViewById<View>(R.id.timeRangeConfirm).apply {
            isEnabled = valid
            alpha = if (valid) 1f else 0.4f
        }
    }
}
