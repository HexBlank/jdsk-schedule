package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R

/**
 * 课程详情底部抽屉：
 * 顶部把手 + 与课程块同色的强调条 + 标题/副标题 + 周次/时间/教室/教师 + 完成按钮。
 */
class CourseDetailSheet(
    context: Context,
    data: TimetableView.CourseClick,
    canEdit: Boolean = false,
    onReschedule: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    /** 只读时的说明；默认是「通过分享码加入的课表」那一句，情侣课表里换成更贴切的话。 */
    readOnlyNote: String? = null
) :
    Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_course_detail)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        findViewById<TextView>(R.id.sheetTitle).text = data.course.name
        findViewById<TextView>(R.id.sheetSubtitle).text = "${data.dayName} · ${data.timeText}"
        findViewById<TextView>(R.id.sheetWeeks).text = data.weekSummary
        findViewById<TextView>(R.id.sheetTime).text =
            context.getString(R.string.detail_sections, data.course.startSection, data.course.endSection, data.timeText)
        findViewById<TextView>(R.id.sheetRoom).text =
            data.course.position.ifBlank { context.getString(R.string.detail_room_pending) }
        findViewById<TextView>(R.id.sheetTeacher).text =
            data.course.teacher.ifBlank { context.getString(R.string.detail_teacher_empty) }

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
        // 订阅课表只读：没有调课按钮时，明确告诉用户原因与获取自己副本的方式
        if (!canEdit) {
            findViewById<TextView>(R.id.sheetReadOnly).apply {
                visibility = View.VISIBLE
                if (readOnlyNote != null) text = readOnlyNote
            }
        }
        // 补课块与停课块表示的是「某一天的整体安排」，不支持对单节课再调课
        if (canEdit && !data.orphaned && data.makeup == null && data.holiday == null) {
            findViewById<TextView>(R.id.sheetReschedule).apply {
                visibility = View.VISIBLE
                text = context.getString(if (adjustment == null) R.string.reschedule_action else R.string.reschedule_edit_action)
                setOnClickListener { dismiss(); onReschedule?.invoke() }
            }
        }
        if (canEdit && adjustment != null) {
            findViewById<TextView>(R.id.sheetUndo).apply {
                visibility = View.VISIBLE
                setOnClickListener { dismiss(); onUndo?.invoke() }
            }
        }

        findViewById<View>(R.id.sheetAccent).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(data.backgroundColor)
        }
        findViewById<TextView>(R.id.sheetClose).setOnClickListener { dismiss() }
    }

    private fun dayName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }
}
