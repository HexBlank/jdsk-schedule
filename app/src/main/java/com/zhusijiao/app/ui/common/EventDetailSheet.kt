package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimeSlot

/**
 * 日程详情抽屉：标题下面依次是时间、周次与节次、地点，没填的行不出现；备注单独一块；编辑与删除并排。
 *
 * 填了精确时间时，时间行是「周三 · 18:30–20:30」，节次放进第二行（「第 10–11 节」），
 * 一眼就知道它画在哪几格；没填精确时间时时间行就是节次作息，第二行只写周次。
 */
class EventDetailSheet(
    context: Context,
    private val data: TimetableView.EventClick,
    private val slots: List<TimeSlot>,
    private val onEdit: () -> Unit,
    private val onDelete: () -> Unit
) : SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_event_detail)
        setCanceledOnTouchOutside(true)

        val event = data.event
        val timeText = event.timeText(slots) ?: context.getString(R.string.detail_time_pending)
        val sections = context.getString(
            R.string.slot_value,
            data.dayName,
            SlotSheet.sectionText(event.startSection..event.endSection)
        )
        findViewById<TextView>(R.id.eventDetailTitle).text = event.title
        findViewById<TextView>(R.id.eventDetailTime).text =
            if (event.startTime != null) "${data.dayName} · $timeText" else "$sections · $timeText"
        findViewById<TextView>(R.id.eventDetailWeeks).text = listOfNotNull(
            ScheduleView.formatWeekSummary(event.weeks),
            if (event.startTime != null) context.getString(R.string.course_sections_value, SlotSheet.sectionText(event.startSection..event.endSection)) else null
        ).joinToString(" · ")
        if (event.position.isNotBlank()) {
            findViewById<View>(R.id.eventDetailPlaceRow).visibility = View.VISIBLE
            findViewById<TextView>(R.id.eventDetailPlace).text = event.position
        }
        if (event.note.isNotBlank()) {
            findViewById<TextView>(R.id.eventDetailNote).apply {
                visibility = View.VISIBLE
                text = event.note
            }
        }

        findViewById<View>(R.id.eventDetailEdit).setOnClickListener { dismiss(); onEdit() }
        findViewById<View>(R.id.eventDetailDelete).setOnClickListener { dismiss(); onDelete() }
    }
}
