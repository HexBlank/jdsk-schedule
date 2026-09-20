package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.CourseAdjustmentDraft
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleValidator
import com.zhusijiao.app.util.Ui

/**
 * 单次调课编辑器：课程时长自动保持；目标日期在整学期日历上直接点选（与调休面板同一个
 * [HolidayCalendarView]，不用再把「第几周周几」换算成抽象数字），节次用胶囊行点选，
 * 预览实时给出「调到 第X周 周X（X月X日）第 A–B 节 · 上下课时间」。
 */
class RescheduleSheet(
    context: Context,
    private val schedule: Schedule,
    private val click: TimetableView.CourseClick,
    private val onSave: (CourseAdjustmentDraft) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    private val adjustment = click.adjustment
    private val duration = (adjustment?.let { it.sourceEndSection - it.sourceStartSection + 1 }
        ?: (click.course.endSection - click.course.startSection + 1)).coerceIn(1, 12)
    private val sourceWeek = adjustment?.sourceWeek ?: click.week
    private val sourceDay = adjustment?.sourceDay ?: click.course.day
    private val sourceStart = adjustment?.sourceStartSection ?: click.course.startSection
    private val sourceEnd = adjustment?.sourceEndSection ?: click.course.endSection
    private val lastStart = 13 - duration

    /** 日历上已选的目标日下标（从 0 起）；初始定位到原目标日。 */
    private var selectedIndex = -1

    init {
        setContentView(R.layout.dialog_reschedule)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        // 键盘弹出时把整个面板上抬到输入法上方，避免教室输入框被遮挡
        Ui.liftAboveIme(findViewById(android.R.id.content))

        findViewById<TextView>(R.id.rescheduleTitle).text = context.getString(
            if (adjustment == null) R.string.reschedule_title else R.string.reschedule_edit_title
        )
        val sourceBase = context.getString(
            R.string.reschedule_source_format,
            click.course.name,
            sourceWeek,
            dayName(sourceDay),
            sourceStart,
            sourceEnd
        )
        val sourceDate = dateText(sourceWeek, sourceDay)
        findViewById<TextView>(R.id.rescheduleSource).text =
            if (sourceDate.isEmpty()) sourceBase else "$sourceBase（$sourceDate）"

        val calendar = findViewById<HolidayCalendarView>(R.id.rescheduleCalendar)
        val sectionChips = findViewById<ChipRowView>(R.id.rescheduleSectionChips)
        val room = findViewById<EditText>(R.id.rescheduleRoom)

        // 初始定位到原目标日（修改调课时回显已调到的日期）。
        selectedIndex = calendar.dayIndex(
            (adjustment?.targetWeek ?: sourceWeek).coerceIn(1, schedule.totalWeeks),
            (adjustment?.targetDay ?: sourceDay).coerceIn(1, 7)
        )
        calendar.configure(schedule)
        calendar.onDayClick = { item ->
            selectedIndex = item.index
            calendar.setSelection(selectedIndex, secondaryIndex = -1)
            updateSummary()
        }
        // 课表缺开学日期时日历无数据可显示（正常导入流程已强制要求学期信息，这里只是兜底）：
        // 隐藏日历并提示，保存时目标日期回退为原目标日，保持旧版行为。
        val calendarUsable = DateUtils.hasSemesterStart(schedule.semesterStart) &&
            calendar.itemAt(selectedIndex) != null
        findViewById<View>(R.id.rescheduleCalendarCard).visibility =
            if (calendarUsable) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rescheduleNoCalendar).visibility =
            if (calendarUsable) View.GONE else View.VISIBLE
        if (calendarUsable) {
            calendar.setSelection(selectedIndex, secondaryIndex = -1)
            // 首帧把日历滚到原目标日附近：等日历完成 layout 拿到格子坐标后再定位（与调休面板一致）。
            calendar.doOnLayout {
                findViewById<NestedScrollView>(R.id.rescheduleScroll).scrollTo(0, scrollOffsetFor())
            }
        } else {
            selectedIndex = -1
        }

        sectionChips.configure(
            items = (1..lastStart).map { it.toString() },
            initialIndex = (adjustment?.targetStartSection ?: sourceStart).coerceIn(1, lastStart) - 1,
            contentDescriptionPrefix = "第",
            contentDescriptionSuffix = "节",
            onSelected = { updateSummary() }
        )
        room.setText(adjustment?.targetPosition.orEmpty())
        updateSummary()

        findViewById<View>(R.id.rescheduleCancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.rescheduleSave).setOnClickListener {
            val draft = draft()
            val base = runCatching {
                ScheduleValidator.validateAdjustment(schedule, draft, adjustment?.id)
            }.exceptionOrNull()
            if (base != null) {
                Ui.toast(context, base.message ?: context.getString(R.string.reschedule_invalid))
                return@setOnClickListener
            }
            val conflicts = ScheduleValidator.conflicts(schedule, draft, adjustment?.id)
            val submit = {
                dismiss()
                onSave(draft)
            }
            if (conflicts.isEmpty()) submit() else Ui.confirm(
                context,
                context.getString(R.string.reschedule_conflict_title),
                context.getString(R.string.reschedule_conflict_content, conflicts.joinToString("、") { it.name }),
                confirmText = context.getString(R.string.reschedule_continue),
                onConfirm = submit
            )
        }
    }

    private fun draft(): CourseAdjustmentDraft {
        val target = findViewById<HolidayCalendarView>(R.id.rescheduleCalendar).itemAt(selectedIndex)
        val targetWeek = target?.week ?: sourceWeek
        val targetDay = target?.day ?: sourceDay
        val targetStart = findViewById<ChipRowView>(R.id.rescheduleSectionChips).selection + 1
        return CourseAdjustmentDraft(
            courseId = adjustment?.courseId ?: click.course.id,
            sourceWeek = sourceWeek,
            sourceDay = sourceDay,
            sourceStartSection = sourceStart,
            sourceEndSection = sourceEnd,
            targetWeek = targetWeek,
            targetDay = targetDay,
            targetStartSection = targetStart,
            targetEndSection = targetStart + duration - 1,
            targetPosition = findViewById<EditText>(R.id.rescheduleRoom).text.toString().trim().ifEmpty { null }
        )
    }

    private fun updateSummary() {
        val target = findViewById<HolidayCalendarView>(R.id.rescheduleCalendar).itemAt(selectedIndex)
        val week = target?.week ?: sourceWeek
        val day = target?.day ?: sourceDay
        val start = findViewById<ChipRowView>(R.id.rescheduleSectionChips).selection + 1
        val end = start + duration - 1
        findViewById<TextView>(R.id.rescheduleSummary).text = context.getString(
            R.string.reschedule_summary_format,
            context.getString(R.string.reschedule_week_value, week),
            dayName(day),
            dateText(week, day),
            start,
            end,
            timeText(start, end)
        )
    }

    /** 起止节的上下课时间，如「 · 07:50–08:35」；无作息数据时为空。 */
    private fun timeText(start: Int, end: Int): String {
        val startTime = schedule.timeSlots.getOrNull(start - 1)?.startTime ?: return ""
        val endTime = schedule.timeSlots.getOrNull(end - 1)?.endTime ?: return ""
        return context.getString(R.string.reschedule_summary_time, startTime, endTime)
    }

    private fun dateText(week: Int, day: Int): String {
        val date = DateUtils.datesForWeek(schedule.semesterStart, week).getOrNull(day - 1) ?: return ""
        return "${date.month}月${date.day}日"
    }

    /** 让原目标日露出时外层滚动容器应滚到的 y；日历卡片相对滚动内容顶部的偏移 + 日历内目标格偏移。 */
    private fun scrollOffsetFor(): Int {
        val calendar = findViewById<HolidayCalendarView>(R.id.rescheduleCalendar)
        val card = findViewById<View>(R.id.rescheduleCalendarCard)
        return (card.top + calendar.top + calendar.scrollTopFor(selectedIndex)).coerceAtLeast(0)
    }

    private fun dayName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }
}