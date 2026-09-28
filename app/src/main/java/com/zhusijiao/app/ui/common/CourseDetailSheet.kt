package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R

/**
 * 课程详情底部抽屉：课程色浅底的头部里，教室用大字、时间和节次紧随其后（最常被问的「在哪上、几点上」）；
 * 周次、教师两格次要信息；调课、撤销、完成并排放在底部。
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
        findViewById<TextView>(R.id.sheetWeeks).text = data.weekSummary
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

        findViewById<TextView>(R.id.sheetClose).setOnClickListener { dismiss() }
    }

    private fun dayName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }
}
