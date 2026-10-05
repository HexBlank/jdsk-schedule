package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R

/**
 * 点课表空格子后的二选一：加一节课，还是加一条日程。
 *
 * 两者都只存在本机，区别在于它算不算「课」：自己加的课和导入的课完全一样对待
 * （停课补课、上课提醒、请假、桌面小部件），日程是课以外的安排。
 * 一句话说明写在选项下面，免得用户选完才发现不是想要的那种。
 */
class AddEntrySheet(
    context: Context,
    title: String,
    private val onCourse: () -> Unit,
    private val onEvent: () -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_add_entry)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.addEntryTitle).text = title
        findViewById<View>(R.id.addEntryCourse).setOnClickListener {
            dismiss()
            onCourse()
        }
        findViewById<View>(R.id.addEntryEvent).setOnClickListener {
            dismiss()
            onEvent()
        }
    }
}
