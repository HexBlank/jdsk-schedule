package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Leaves

/**
 * 课程详情底部抽屉：课程色浅底的头部里，教室用大字、时间和节次紧随其后（最常被问的「在哪上、几点上」），
 * 周次与教师并成一行；能对这节课做的事（请假、颜色、调课……）是底部一排等宽操作块，
 * 没权限做的那块不出现——所有操作都看得见，不依赖长按之类的隐藏手势。
 * 没有「完成」按钮——下拉或点外面就关，一个操作都没有时整行不出现。
 */
class CourseDetailSheet(
    context: Context,
    data: TimetableView.CourseClick,
    canEdit: Boolean = false,
    onReschedule: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    /** 只读时的一行标注（如「自己加的课」「TA 的课」）；不传就什么都不写，没有按钮本身已经说明只能看。 */
    readOnlyNote: String? = null,
    /** 「这节课请假」或查看已有请假；为 null 时不显示入口（情侣课表、没有开学日期的课表）。 */
    onLeave: (() -> Unit)? = null,
    /** 自己加的课：底部给「编辑」和「删除」（课表自带的课不传，照旧是调课）。 */
    onEditExtra: (() -> Unit)? = null,
    onDeleteExtra: (() -> Unit)? = null,
    /** 改这门课的颜色；为 null 时不出现（订阅者不能改课表自带课的颜色）。 */
    onColor: (() -> Unit)? = null
) :
    SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_course_detail)
        setCanceledOnTouchOutside(true)

        // 头部配色：课程色朝白色混出浅底，文字朝墨色加深，浅色课程（如麦黄）上也清楚
        val color = data.backgroundColor
        val heroText = BlockStyles.blend(color, ContextCompat.getColor(context, R.color.text_primary), 0.45f)
        findViewById<View>(R.id.sheetHero).background = GradientDrawable().apply {
            cornerRadius = context.resources.getDimension(R.dimen.radius_md) + context.resources.displayMetrics.density * 2f
            setColor(BlockStyles.blend(color, Color.WHITE, 0.88f))
        }
        findViewById<TextView>(R.id.sheetTitle).apply {
            text = data.course.name
            setTextColor(heroText)
        }
        findViewById<TextView>(R.id.sheetRoom).apply {
            text = data.course.position.ifBlank { context.getString(R.string.detail_room_pending) }
            setTextColor(BlockStyles.blend(color, ContextCompat.getColor(context, R.color.text_primary), 0.62f))
        }
        findViewById<TextView>(R.id.sheetSubtitle).apply {
            text = "${data.dayName} " +
                context.getString(R.string.detail_sections, data.course.startSection, data.course.endSection, data.timeText)
            setTextColor(heroText)
        }
        findViewById<TextView>(R.id.sheetMeta).apply {
            text = listOf(data.weekSummary, data.course.teacher).filter { it.isNotBlank() }.joinToString(" · ")
            setTextColor(heroText)
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
        // 请假：当天真要上的课可以请假；已经在请假时段里的显示时段一行，点进去修改或取消
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

        // 操作块从左到右：请假 · 颜色 · 撤销 / 删除 · 主操作（调课 / 编辑）
        val actions = findViewById<LinearLayout>(R.id.sheetActions)
        if (canLeave && leave == null) {
            addAction(actions, context.getString(R.string.leave_title)) { onLeave?.invoke() }
        }
        if (onColor != null) addAction(actions, context.getString(R.string.event_color_label), onClick = onColor)
        if (canEdit && adjustment != null) {
            addAction(actions, context.getString(R.string.reschedule_undo)) { onUndo?.invoke() }
        }
        // 自己加的课不走调课（它不在课表里，调课记录无处可存），直接改或删
        if (onDeleteExtra != null) {
            addAction(actions, context.getString(R.string.course_extra_delete), danger = true, onClick = onDeleteExtra)
        }
        // 补课块与停课块表示的是「某一天的整体安排」，不支持对单节课再调课
        if (canEdit && !data.orphaned && data.makeup == null && data.holiday == null) {
            addAction(
                actions,
                context.getString(if (adjustment == null) R.string.reschedule_action else R.string.reschedule_edit_action),
                primary = true
            ) { onReschedule?.invoke() }
        }
        if (onEditExtra != null) {
            addAction(actions, context.getString(R.string.course_extra_edit), primary = true, onClick = onEditExtra)
        }
        actions.isVisible = actions.childCount > 0
    }

    private fun addAction(
        parent: LinearLayout,
        label: String,
        primary: Boolean = false,
        danger: Boolean = false,
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
                        else -> R.color.action_ink
                    }
                )
            )
            if (primary) typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss(); onClick() }
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                if (parent.childCount > 0) marginStart = (8 * density).toInt()
            }
        })
    }

    private fun dayName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }
}
