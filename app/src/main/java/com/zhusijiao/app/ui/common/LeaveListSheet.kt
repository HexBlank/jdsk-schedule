package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.LeaveType
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.Schedule

/**
 * 请假记录面板：还没结束的排在前面（按开始时间由近到远），已结束的垫底并变淡。
 * 每条写明类别、起止时间、涉及当前课表的几节课；点一条进编辑（改时间或删除），底部新增。
 *
 * 增删改之后调用方用 [update] 就地刷新，不用关掉再进。
 */
class LeaveListSheet(
    context: Context,
    private val schedule: Schedule,
    leaves: List<Leave>,
    private val onAdd: () -> Unit,
    private val onEdit: (Leave) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_leave_list)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)
        findViewById<View>(R.id.leaveAdd).setOnClickListener { onAdd() }
        findViewById<View>(R.id.leaveListClose).setOnClickListener { dismiss() }
        update(leaves)
    }

    fun update(leaves: List<Leave>) {
        val list = findViewById<LinearLayout>(R.id.leaveList)
        list.removeAllViews()
        findViewById<View>(R.id.leaveEmpty).visibility = if (leaves.isEmpty()) View.VISIBLE else View.GONE
        val now = System.currentTimeMillis()
        val (active, ended) = leaves.partition { (it.endAtMillis ?: 0L) > now }
        val inflater = LayoutInflater.from(context)
        (active.sortedBy { it.start } + ended.sortedByDescending { it.start }).forEach { leave ->
            val row = inflater.inflate(R.layout.item_leave, list, false)
            val isEnded = (leave.endAtMillis ?: 0L) <= now
            val ongoing = !isEnded && (leave.startAtMillis ?: Long.MAX_VALUE) <= now
            row.findViewById<TextView>(R.id.leaveItemType).apply {
                text = leave.type.label
                val official = leave.type == LeaveType.OFFICIAL
                setBackgroundResource(if (official) R.drawable.bg_tag_warn else R.drawable.bg_tag)
                setTextColor(ContextCompat.getColor(context, if (official) R.color.warn else R.color.tag_text))
            }
            row.findViewById<TextView>(R.id.leaveItemRange).text = Leaves.displayRange(leave.start, leave.end)
            val count = Leaves.affectedClasses(schedule, leave.start, leave.end).size
            row.findViewById<TextView>(R.id.leaveItemSub).text = listOfNotNull(
                when {
                    isEnded -> context.getString(R.string.leave_status_ended)
                    ongoing -> context.getString(R.string.leave_status_ongoing)
                    else -> null
                },
                if (count > 0) context.getString(R.string.leave_row_classes, count)
                else context.getString(R.string.leave_row_no_classes),
                leave.note.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            row.alpha = if (isEnded) 0.55f else 1f
            row.setOnClickListener { onEdit(leave) }
            list.addView(row)
        }
    }
}
