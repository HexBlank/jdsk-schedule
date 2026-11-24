package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.View
import android.widget.TextView
import com.zhusijiao.app.R

/**
 * 点课表空格子后的二选一：添加课程 / 添加日程。
 * 与底部操作面板保持同一套浅灰无框分组行样式，协调克制。
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
        bindChoices(findViewById<View>(android.R.id.content), { dismiss(); onCourse() }, { dismiss(); onEvent() })
    }

    companion object {
        fun bindChoices(root: View, onCourse: () -> Unit, onEvent: () -> Unit) {
            root.findViewById<View>(R.id.addEntryCourse).setOnClickListener { onCourse() }
            root.findViewById<View>(R.id.addEntryEvent).setOnClickListener { onEvent() }
        }
    }
}
