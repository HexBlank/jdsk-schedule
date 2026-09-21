package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimeSlot

/**
 * 日程详情抽屉。
 *
 * 时间给两套口径：主行是自定义时间（没填则是该节次的作息），副行写明它占第几节、
 * 那几节的上下课时间是多少——填了 18:30 的日程画在 19:15 那一行，这里解释得清楚。
 */
class EventDetailSheet(
    context: Context,
    private val data: TimetableView.EventClick,
    private val slots: List<TimeSlot>,
    private val onEdit: () -> Unit,
    private val onDelete: () -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_event_detail)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        val event = data.event
        val timeText = event.timeText(slots) ?: context.getString(R.string.detail_time_pending)
        findViewById<TextView>(R.id.eventDetailTitle).text = event.title
        findViewById<TextView>(R.id.eventDetailSubtitle).text = "${data.dayName} · $timeText"
        findViewById<TextView>(R.id.eventDetailWeeks).text =
            ScheduleView.formatWeekSummary(event.weeks)
        findViewById<TextView>(R.id.eventDetailTime).text = timeText
        findViewById<TextView>(R.id.eventDetailSections).text = context.getString(
            R.string.event_detail_sections,
            event.startSection,
            event.endSection,
            ScheduleTime.rangeText(slots, event.startSection, event.endSection)
                ?.let { "（$it）" }.orEmpty()
        )
        findViewById<TextView>(R.id.eventDetailPlace).text =
            event.position.ifBlank { context.getString(R.string.detail_room_pending) }
        if (event.note.isNotBlank()) {
            findViewById<View>(R.id.eventDetailNoteRow).visibility = View.VISIBLE
            findViewById<TextView>(R.id.eventDetailNote).text = event.note
        }

        findViewById<View>(R.id.eventAccent).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(data.backgroundColor)
        }
        findViewById<View>(R.id.eventDetailEdit).setOnClickListener { dismiss(); onEdit() }
        findViewById<View>(R.id.eventDetailDelete).setOnClickListener { dismiss(); onDelete() }
        findViewById<View>(R.id.eventDetailClose).setOnClickListener { dismiss() }
    }
}
