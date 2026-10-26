package com.zhusijiao.app.ui.common

import android.content.Context
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.TimeSlot

/**
 * 选星期与节次的底部面板（加课、加日程共用）。
 *
 * 标题就是当前选择（「周三 · 第 3–4 节」），下面一行是对应的作息时间，随点随变——
 * 这就是全部反馈，不写「点一格选开始、再点一格选结束」之类的说明。
 * 节次网格的两步区间语义见 [SectionPickerView]。
 */
class SlotSheet(
    context: Context,
    private val slots: List<TimeSlot>,
    initialDay: Int,
    initialRange: IntRange?,
    private val onDone: (day: Int, range: IntRange) -> Unit
) : SheetDialog(context) {

    private val title by lazy { findViewById<TextView>(R.id.slotTitle) }
    private val clock by lazy { findViewById<TextView>(R.id.slotClock) }
    private val day by lazy { findViewById<SegmentedChoiceView>(R.id.slotDay) }
    private val sections by lazy { findViewById<SectionPickerView>(R.id.slotSections) }
    private val done by lazy { findViewById<TextView>(R.id.slotDone) }

    init {
        setContentView(R.layout.dialog_slot)
        setCanceledOnTouchOutside(true)
        day.configure(DAY_NAMES, initialDay.coerceIn(1, 7) - 1, context.getString(R.string.course_day_prefix)) {
            render()
        }
        sections.configure(slots, initialRange) { render() }
        done.setOnClickListener {
            val range = sections.selection ?: return@setOnClickListener
            dismiss()
            onDone(day.selectedIndex + 1, range)
        }
        render()
    }

    private fun render() {
        val range = sections.selection
        val dayText = context.getString(R.string.slot_day, DAY_NAMES[day.selectedIndex])
        title.text = if (range == null) dayText else context.getString(R.string.slot_value, dayText, sectionText(range))
        clock.text = range?.let { ScheduleTime.rangeText(slots, it.first, it.last) }.orEmpty()
        done.isEnabled = range != null
    }

    companion object {
        val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

        /** 「3」或「3–4」，配合「第 %s 节」使用。 */
        fun sectionText(range: IntRange): String =
            if (range.first == range.last) range.first.toString() else "${range.first}–${range.last}"
    }
}
