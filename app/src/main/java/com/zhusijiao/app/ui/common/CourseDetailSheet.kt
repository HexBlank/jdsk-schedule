package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Leaves

/**
 * 课程详情底部抽屉：
 * 头部采用白底 + 左侧课程色竖条 + 大号课名 + 醒目教室胶囊；
 * 下方用矢量图标引导时间、周次、教师；底部操作超过 2 项时主操作独立沉底，清晰不挤字。
 */
class CourseDetailSheet(
    context: Context,
    data: TimetableView.CourseClick,
    canEdit: Boolean = false,
    onReschedule: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    readOnlyNote: String? = null,
    onLeave: (() -> Unit)? = null,
    onEditExtra: (() -> Unit)? = null,
    onDeleteExtra: (() -> Unit)? = null,
    onColor: (() -> Unit)? = null
) : SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_course_detail)
        setCanceledOnTouchOutside(true)

        val tone = data.backgroundColor
        val softFill = BlockStyles.courseSoftFill(tone)
        val softText = BlockStyles.courseSoftText(tone)

        findViewById<TextView>(R.id.sheetTitle).text = data.course.name

        val roomPill = findViewById<View>(R.id.sheetRoomPill)
        roomPill.background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(softFill)
        }
        findViewById<ImageView>(R.id.sheetRoomIcon).setColorFilter(softText)
        findViewById<TextView>(R.id.sheetRoom).apply {
            text = data.course.position.ifBlank { context.getString(R.string.detail_room_pending) }
            setTextColor(softText)
        }

        findViewById<TextView>(R.id.sheetSubtitle).text = "${data.dayName} · " +
            context.getString(R.string.detail_sections, data.course.startSection, data.course.endSection, data.timeText)
        findViewById<TextView>(R.id.sheetWeeks).text = data.weekSummary
        if (data.course.teacher.isNotBlank()) {
            findViewById<View>(R.id.sheetTeacherRow).visibility = View.VISIBLE
            findViewById<TextView>(R.id.sheetTeacher).text = data.course.teacher
        }

        val adjustment = data.adjustment
        when {
            data.makeup != null -> findViewById<TextView>(R.id.sheetAdjustment).apply {
                visibility = View.VISIBLE
                text = context.getString(
                    R.string.detail_makeup_notice,
                    data.makeup.sourceWeek,
                    dayName(data.makeup.sourceDay)
                )
            }
            data.madeUpNote != null -> findViewById<TextView>(R.id.sheetAdjustment).apply {
                visibility = View.VISIBLE
                text = data.madeUpNote
            }
            data.holiday != null -> findViewById<TextView>(R.id.sheetAdjustment).apply {
                visibility = View.VISIBLE
                text = context.getString(R.string.detail_suspended_notice)
            }
            adjustment != null -> findViewById<TextView>(R.id.sheetAdjustment).apply {
                visibility = View.VISIBLE
                text = context.getString(
                    R.string.reschedule_detail_format,
                    adjustment.sourceWeek,
                    dayName(adjustment.sourceDay),
                    adjustment.sourceStartSection,
                    adjustment.sourceEndSection,
                    adjustment.targetWeek,
                    dayName(adjustment.targetDay),
                    adjustment.targetStartSection,
                    adjustment.targetEndSection
                ) + if (data.orphaned) "\n\n${context.getString(R.string.reschedule_orphaned)}" else ""
            }
        }
        if (!canEdit && readOnlyNote != null) {
            findViewById<TextView>(R.id.sheetReadOnly).apply {
                visibility = View.VISIBLE
                text = readOnlyNote
            }
        }

        val attends = data.holiday == null && data.madeUpNote == null &&
            data.occurrence != TimetableView.Occurrence.MOVED_OUT
        val canLeave = onLeave != null && !data.partner && data.dateIso != null && (data.leave != null || attends)
        val leave = data.leave
        if (canLeave && leave != null) {
            findViewById<View>(R.id.sheetLeave).apply {
                visibility = View.VISIBLE
                setOnClickListener { dismiss(); onLeave?.invoke() }
            }
            findViewById<TextView>(R.id.sheetLeaveText).text = listOfNotNull(
                context.getString(R.string.detail_leave_active, leave.type.label, Leaves.displayRange(leave.start, leave.end)),
                leave.note.takeIf { it.isNotBlank() }
            ).joinToString("\n")
        }

        data class ActionItem(
            val label: String,
            val primary: Boolean = false,
            val danger: Boolean = false,
            val onClick: () -> Unit
        )

        val secondaryItems = mutableListOf<ActionItem>()
        if (canLeave && leave == null) {
            secondaryItems += ActionItem(context.getString(R.string.leave_title)) { onLeave?.invoke() }
        }
        if (onColor != null) {
            secondaryItems += ActionItem(context.getString(R.string.event_color_label), onClick = onColor)
        }
        if (canEdit && adjustment != null) {
            secondaryItems += ActionItem(context.getString(R.string.reschedule_undo)) { onUndo?.invoke() }
        }
        if (onDeleteExtra != null) {
            secondaryItems += ActionItem(context.getString(R.string.course_extra_delete), danger = true, onClick = onDeleteExtra)
        }

        val primaryItem: ActionItem? = when {
            canEdit && !data.orphaned && data.makeup == null && data.holiday == null -> ActionItem(
                context.getString(if (adjustment == null) R.string.reschedule_action else R.string.reschedule_edit_action),
                primary = true
            ) { onReschedule?.invoke() }
            onEditExtra != null -> ActionItem(context.getString(R.string.course_extra_edit), primary = true, onClick = onEditExtra)
            else -> null
        }

        val actionsRow = findViewById<LinearLayout>(R.id.sheetActions)
        val primaryBar = findViewById<TextView>(R.id.sheetPrimaryAction)

        if (primaryItem != null && secondaryItems.size >= 2) {
            // 次要操作较多时，主操作独占底部一行，清晰稳重不挤字
            secondaryItems.forEach { addAction(actionsRow, it.label, false, it.danger, it.onClick) }
            actionsRow.isVisible = true
            primaryBar.visibility = View.VISIBLE
            primaryBar.text = primaryItem.label
            primaryBar.setOnClickListener { dismiss(); primaryItem.onClick() }
        } else {
            secondaryItems.forEach { addAction(actionsRow, it.label, false, it.danger, it.onClick) }
            primaryItem?.let { addAction(actionsRow, it.label, true, false, it.onClick) }
            actionsRow.isVisible = actionsRow.childCount > 0
        }
    }

    private fun addAction(
        parent: LinearLayout,
        label: String,
        primary: Boolean,
        danger: Boolean,
        onClick: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        parent.addView(TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setBackgroundResource(if (primary) R.drawable.bg_close_button else R.drawable.bg_ghost_button)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        primary -> R.color.sheet_close_text
                        danger -> R.color.danger_action
                        else -> R.color.text_primary
                    }
                )
            )
            if (primary) typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss(); onClick() }
            layoutParams = LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f).apply {
                if (parent.childCount > 0) marginStart = (8 * density).toInt()
            }
        })
    }

    private fun dayName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }
}
