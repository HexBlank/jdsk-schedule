package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.ui.event.EventEditorActivity
import com.zhusijiao.app.util.Rpx

/**
 * 点课表空格子后的二选一：课程，还是日程。
 *
 * 两者都只存在本机，区别在于它算不算「课」：自己加的课和导入的课完全一样对待
 * （停课补课、上课提醒、请假、桌面小部件），日程是课以外的安排。
 * 不在面板里解释这些——两块并排，各带一个它在课表上的色块（课程色 / 日程的冷灰蓝），看一眼就分得清。
 */
class AddEntrySheet(
    context: Context,
    title: String,
    private val onCourse: () -> Unit,
    private val onEvent: () -> Unit
) : SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_add_entry)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.addEntryTitle).text = title
        swatch(R.id.addEntryCourseSwatch, ScheduleView.COURSE_PALETTES[0].background)
        swatch(R.id.addEntryEventSwatch, EventEditorActivity.DEFAULT_EVENT_COLOR)
        findViewById<View>(R.id.addEntryCourse).setOnClickListener {
            dismiss()
            onCourse()
        }
        findViewById<View>(R.id.addEntryEvent).setOnClickListener {
            dismiss()
            onEvent()
        }
    }

    private fun swatch(id: Int, color: Int) {
        findViewById<View>(id).background = GradientDrawable().apply {
            cornerRadius = Rpx.dp(6f).toFloat()
            setColor(color)
        }
    }
}
