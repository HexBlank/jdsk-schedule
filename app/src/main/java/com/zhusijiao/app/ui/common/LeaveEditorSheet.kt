package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.LeaveDraft
import com.zhusijiao.app.domain.LeaveType
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.Schedule

/**
 * 请假编辑面板：选类别（请假 / 公假）、起止时刻（各弹一次 [DateTimeSheet]，精确到分钟，可以跨天）、
 * 可选的事由；下面实时列出这段时间里会上的课，保存前就能看到「这次请假涉及哪几节」。
 *
 * 面板只负责收集和校验，落盘由调用方做（[onSave] / [onDelete]）。
 */
class LeaveEditorSheet(
    context: Context,
    private val schedule: Schedule,
    private val existing: Leave?,
    initialStart: String,
    initialEnd: String,
    private val onSave: (LeaveDraft) -> Unit,
    private val onDelete: (() -> Unit)? = null
) : ImeSheetDialog(context) {

    private var type = existing?.type ?: LeaveType.PERSONAL
    private var start = existing?.start ?: initialStart
    private var end = existing?.end ?: initialEnd

    private val startView by lazy { findViewById<TextView>(R.id.leaveStart) }
    private val endView by lazy { findViewById<TextView>(R.id.leaveEnd) }
    private val errorView by lazy { findViewById<TextView>(R.id.leaveError) }
    private val saveView by lazy { findViewById<TextView>(R.id.leaveSave) }
    private val noteView by lazy { findViewById<EditText>(R.id.leaveNote) }

    init {
        setContentView(R.layout.dialog_leave_editor)
        setCanceledOnTouchOutside(true)

        findViewById<TextView>(R.id.leaveEditorTitle).setText(
            if (existing == null) R.string.leave_new_title else R.string.leave_edit_title
        )
        findViewById<SegmentedChoiceView>(R.id.leaveType).configure(
            LeaveType.entries.map { it.label },
            LeaveType.entries.indexOf(type),
            context.getString(R.string.leave_type_desc_prefix)
        ) { index -> type = LeaveType.entries[index] }
        noteView.setText(existing?.note.orEmpty())

        findViewById<View>(R.id.leaveStartRow).setOnClickListener {
            pick(R.string.leave_pick_start, start) { picked ->
                // 开始改到结束之后时，保持原来的时长把结束一起往后带，不必再去改一次结束
                val oldStart = Leaves.parseMoment(start)
                val oldEnd = Leaves.parseMoment(end)
                val newStart = Leaves.parseMoment(picked)
                start = picked
                if (oldStart != null && oldEnd != null && newStart != null && newStart >= oldEnd && oldEnd > oldStart) {
                    end = Leaves.formatMoment(newStart + (oldEnd - oldStart))
                }
                render()
            }
        }
        findViewById<View>(R.id.leaveEndRow).setOnClickListener {
            pick(R.string.leave_pick_end, end) { picked ->
                end = picked
                render()
            }
        }
        findViewById<View>(R.id.leaveWholeDay).setOnClickListener {
            start = Leaves.moment(Leaves.dateOf(start), 0)
            // 结束正好落在某天 00:00 时，那一天其实没请：整天对齐到前一天的 23:59
            val endDate = if (Leaves.minutesOf(end) == 0 && Leaves.dateOf(end) > Leaves.dateOf(start)) {
                Leaves.parseMoment(end)?.let { Leaves.dateOf(Leaves.formatMoment(it - 60_000L)) } ?: Leaves.dateOf(end)
            } else Leaves.dateOf(end)
            end = Leaves.moment(maxOf(endDate, Leaves.dateOf(start)), 23 * 60 + 59)
            render()
        }

        saveView.setOnClickListener {
            val draft = draftOrNull() ?: return@setOnClickListener
            dismiss()
            onSave(draft)
        }
        findViewById<TextView>(R.id.leaveDelete).apply {
            visibility = if (existing != null && onDelete != null) View.VISIBLE else View.GONE
            setOnClickListener {
                dismiss()
                onDelete?.invoke()
            }
        }
        findViewById<View>(R.id.leaveCancel).setOnClickListener { dismiss() }
        render()
    }

    private fun pick(titleRes: Int, initial: String, onPicked: (String) -> Unit) {
        val dates = Leaves.candidateDates(
            schedule.semesterStart,
            schedule.totalWeeks,
            System.currentTimeMillis(),
            listOf(Leaves.dateOf(start), Leaves.dateOf(end))
        )
        DateTimeSheet(context, context.getString(titleRes), dates, initial, onPicked).show()
    }

    /** 当前填写内容规范化后的草稿；不合法返回 null（错误文案由 [render] 显示）。 */
    private fun draftOrNull(): LeaveDraft? = try {
        Leaves.normalize(LeaveDraft(type, start, end, noteView.text?.toString().orEmpty()))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun render() {
        startView.text = Leaves.displayMoment(start)
        endView.text = Leaves.displayMoment(end)
        val error = try {
            Leaves.normalize(LeaveDraft(type, start, end, ""))
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        errorView.text = error
        errorView.visibility = if (error == null) View.GONE else View.VISIBLE
        saveView.isEnabled = error == null
        saveView.alpha = if (error == null) 1f else 0.4f
        renderPreview(valid = error == null)
    }

    private fun renderPreview(valid: Boolean) {
        val label = findViewById<TextView>(R.id.leavePreviewLabel)
        val body = findViewById<TextView>(R.id.leavePreview)
        val affected = if (valid) Leaves.affectedClasses(schedule, start, end) else emptyList()
        if (affected.isEmpty()) {
            label.setText(R.string.leave_preview_label)
            body.setText(R.string.leave_preview_none)
            return
        }
        label.text = context.getString(R.string.leave_preview_count, affected.size)
        val lines = affected.take(PREVIEW_LINES).map { item ->
            val date = Leaves.displayMoment(Leaves.moment(item.dateIso, 0)).dropLast(6)
            val sections = if (item.course.startSection == item.course.endSection) {
                context.getString(R.string.leave_preview_section, item.course.startSection)
            } else {
                context.getString(R.string.leave_preview_sections, item.course.startSection, item.course.endSection)
            }
            "$date $sections ${item.course.name}"
        }
        val more = affected.size - lines.size
        body.text = (if (more > 0) lines + context.getString(R.string.leave_preview_more, more) else lines)
            .joinToString("\n")
    }

    private companion object {
        const val PREVIEW_LINES = 6
    }
}
