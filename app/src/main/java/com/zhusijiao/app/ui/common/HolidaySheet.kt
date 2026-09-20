package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.DayHoliday
import com.zhusijiao.app.domain.DayHolidayDraft
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.DayMakeupDraft
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleValidator
import com.zhusijiao.app.util.Ui

/**
 * 调休与停课管理面板。
 *
 * 交互是「先点日期，再选要做什么」：上半部是整学期真实日历（停课灰、补课橙、角标是当天课程数），
 * 下半部固定显示所选那天的状态与可做的操作。没有模式切换，也不需要用户把「10 月 1 日」
 * 换算成「第 5 周周三」——旧版要填 2～4 个抽象数字，现在点一下就够。
 *
 * 补课是两步：在来源日点「把这天的课补到…」，再在日历上点补课日，期间日历用橙色描边标出待选目标。
 * 保存与删除都走 [ScheduleValidator] 与二次确认；保存后面板不关闭，由外部调用 [applySchedule]
 * 回灌最新课表，方便连续设置多天。
 */
class HolidaySheet(
    context: Context,
    schedule: Schedule,
    initialWeek: Int,
    initialDay: Int,
    private val canEdit: Boolean,
    private val onAddHolidays: (List<DayHolidayDraft>) -> Unit,
    private val onDeleteHoliday: (DayHoliday) -> Unit,
    private val onAddMakeup: (DayMakeupDraft) -> Unit,
    private val onDeleteMakeup: (DayMakeup) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    private var schedule = schedule

    /** 主选中日（要操作的那天）；-1 表示未选。 */
    private var selected = -1

    /** 补课选择进行中的来源日；-1 表示不在补课选择态。 */
    private var makeupSource = -1

    /** 补课选择进行中已点选的目标日；-1 表示还没点。 */
    private var makeupTarget = -1

    private var entriesExpanded = false

    private val calendar: HolidayCalendarView by lazy { findViewById(R.id.holidayCalendar) }
    private val calendarCard: View by lazy { findViewById(R.id.holidayCalendarCard) }
    private val scroll: NestedScrollView by lazy { findViewById(R.id.holidayScroll) }
    private val listContainer: LinearLayout by lazy { findViewById(R.id.holidayList) }
    private val listLabel: TextView by lazy { findViewById(R.id.holidayListLabel) }
    private val expandToggle: TextView by lazy { findViewById(R.id.holidayExpand) }
    private val selectedTitle: TextView by lazy { findViewById(R.id.holidaySelectedTitle) }
    private val selectedDetail: TextView by lazy { findViewById(R.id.holidaySelectedDetail) }
    private val durationRow: View by lazy { findViewById(R.id.holidayDurationRow) }
    private val durationStepper: StepperFieldView by lazy { findViewById(R.id.holidayDays) }
    private val actions: LinearLayout by lazy { findViewById(R.id.holidayActions) }
    private val readOnlyHint: View by lazy { findViewById(R.id.holidayReadOnly) }

    init {
        setContentView(R.layout.dialog_holiday)
        window?.apply {
            // 日历需要成片的纵向空间，这里用固定高度面板，底部动作区才能始终留在手边。
            val height = (context.resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, height)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        selected = indexOf(
            initialWeek.coerceIn(1, schedule.totalWeeks.coerceAtLeast(1)),
            initialDay.coerceIn(1, 7)
        )
        calendar.configure(schedule)
        calendar.onDayClick = { item ->
            if (makeupSource >= 0) makeupTarget = item.index else selected = item.index
            resetDuration()
            render()
        }
        durationStepper.configure(
            minimum = 1,
            maximum = MAX_SUSPEND_DAYS,
            initialValue = 1,
            formatter = { context.getString(R.string.holiday_days_value, it) },
            onChanged = { render() }
        )
        findViewById<View>(R.id.holidaySummaryRow).setOnClickListener {
            entriesExpanded = !entriesExpanded
            renderList()
        }
        findViewById<View>(R.id.holidayClose).setOnClickListener { dismiss() }

        if (!canEdit) {
            // 订阅者看不到操作，但仍保留「所选日期」卡片，方便查哪天停课/补课。
            durationRow.visibility = View.GONE
            actions.visibility = View.GONE
            readOnlyHint.visibility = View.VISIBLE
        }
        render()
        // 首帧直接定位到选中日所在月，等日历测量完才有格子坐标。
        calendar.doOnLayout { scrollToSelection(smooth = false) }
    }

    /** 保存成功后由外部回灌最新课表：刷新日历与列表，保留当前选中日。 */
    fun applySchedule(updated: Schedule) {
        schedule = updated
        // 学期变短时把选中日夹回范围内，避免停留在已不存在的日期上。
        val lastIndex = updated.totalWeeks * 7 - 1
        if (selected > lastIndex) selected = lastIndex.coerceAtLeast(-1)
        if (makeupSource > lastIndex) exitMakeupMode()
        if (makeupTarget > lastIndex) makeupTarget = -1
        calendar.configure(updated)
        resetDuration()
        render()
    }

    // ===== 渲染 =====

    private fun render() {
        calendar.setSelection(
            primaryIndex = if (makeupSource >= 0) makeupSource else selected,
            secondaryIndex = if (makeupSource >= 0) makeupTarget else -1
        )
        renderList()
        if (makeupSource >= 0) renderMakeupPicking() else renderSelectedDay()
    }

    private fun renderSelectedDay() {
        val item = calendar.itemAt(selected)
        if (item == null) {
            selectedTitle.text = context.getString(R.string.holiday_no_selection)
            selectedDetail.text = context.getString(R.string.holiday_pick_hint)
            durationRow.visibility = View.GONE
            actions.removeAllViews()
            return
        }
        selectedTitle.text = context.getString(
            R.string.holiday_selected_title,
            dayLabel(item.week, item.day),
            item.week
        )

        val holiday = holidayAt(selected)
        val asTarget = makeupTargetingAt(selected)
        val asSource = makeupSourcedAt(selected)
        val lines = mutableListOf<String>()
        when {
            asTarget != null -> lines += context.getString(
                R.string.holiday_state_makeup_target,
                dayLabel(asTarget.sourceWeek, asTarget.sourceDay)
            )
            holiday != null -> lines += context.getString(R.string.holiday_state_suspended)
            else -> lines += courseSummary(item.week, item.day)
        }
        if (asSource != null) {
            lines += context.getString(
                R.string.holiday_state_makeup_source,
                dayLabel(asSource.targetWeek, asSource.targetDay)
            )
        }
        selectedDetail.text = lines.joinToString("\n")

        if (!canEdit) return
        // 只有「还能设停课」的普通日才需要连停天数，其余情况不占版面。
        val suspendable = holiday == null && asTarget == null
        durationRow.visibility = if (suspendable) View.VISIBLE else View.GONE
        // 连停多天时把真实起止日期摊开，不让用户自己数到哪天为止。
        val planned = if (suspendable) plannedHolidays(durationStepper.value) else emptyList()
        if (planned.size > 1) {
            selectedDetail.text = (lines + context.getString(
                R.string.holiday_range_format,
                dayLabel(planned.first().week, planned.first().day),
                dayLabel(planned.last().week, planned.last().day),
                planned.size
            )).joinToString("\n")
        }

        actions.removeAllViews()
        when {
            asTarget != null -> actions.addView(
                actionButton(context.getString(R.string.holiday_action_remove_makeup), ActionKind.DANGER) {
                    confirmDeleteMakeup(asTarget)
                }
            )
            holiday != null -> actions.addView(
                actionButton(context.getString(R.string.holiday_action_remove_suspend), ActionKind.GHOST) {
                    confirmDeleteHoliday(holiday, item.week, item.day)
                }
            )
            else -> {
                val days = durationStepper.value
                val label = if (days <= 1) context.getString(R.string.holiday_action_suspend_day)
                else context.getString(R.string.holiday_action_suspend_days, days)
                actions.addView(actionButton(label, ActionKind.PRIMARY) { submitHolidays(days) })
            }
        }
        if (asSource != null) {
            // 这天既可能是别人的补课目标、又可能自己被补走，两个「取消补课」按钮必须能分清。
            val label = context.getString(
                R.string.holiday_action_remove_makeup_source,
                dayLabel(asSource.targetWeek, asSource.targetDay)
            )
            actions.addView(actionButton(label, ActionKind.GHOST) { confirmDeleteMakeup(asSource) })
        } else if (asTarget == null) {
            actions.addView(
                actionButton(context.getString(R.string.holiday_action_move_out), ActionKind.GHOST) {
                    makeupSource = selected
                    makeupTarget = -1
                    render()
                }
            )
        }
    }

    private fun renderMakeupPicking() {
        val source = calendar.itemAt(makeupSource) ?: run { exitMakeupMode(); return }
        selectedTitle.text = context.getString(
            R.string.holiday_makeup_title,
            dayLabel(source.week, source.day)
        )
        val target = calendar.itemAt(makeupTarget)
        selectedDetail.text = if (target == null) {
            context.getString(R.string.holiday_makeup_pick_hint)
        } else {
            val chosen = context.getString(
                R.string.holiday_makeup_chosen,
                dayLabel(target.week, target.day)
            )
            val note = if (target.courseCount > 0) {
                context.getString(R.string.holiday_makeup_target_courses, target.courseCount)
            } else {
                context.getString(R.string.holiday_makeup_target_free)
            }
            "$chosen\n$note"
        }
        durationRow.visibility = View.GONE
        actions.removeAllViews()
        actions.addView(
            actionButton(context.getString(R.string.holiday_action_add_makeup), ActionKind.PRIMARY) {
                submitMakeup()
            }
        )
        actions.addView(
            actionButton(context.getString(R.string.common_cancel), ActionKind.GHOST) {
                exitMakeupMode()
                render()
            }
        )
    }

    private fun renderList() {
        val entries = arrangements()
        listLabel.text = if (entries.isEmpty()) {
            context.getString(R.string.holiday_list_label)
        } else {
            context.getString(R.string.holiday_list_count, entries.size)
        }
        expandToggle.text = context.getString(
            if (entriesExpanded) R.string.holiday_collapse else R.string.holiday_expand
        )
        expandToggle.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        listContainer.removeAllViews()
        if (!entriesExpanded) {
            listContainer.visibility = View.GONE
            return
        }
        listContainer.visibility = View.VISIBLE
        if (entries.isEmpty()) {
            listContainer.addView(TextView(context).apply {
                text = context.getString(R.string.holiday_empty)
                setTextColor(ContextCompat.getColor(context, R.color.sub_8a))
                textSize = 13f
                setPadding(0, dp(6f), 0, dp(6f))
            })
            return
        }
        entries.forEach { listContainer.addView(entryRow(it)) }
    }

    // ===== 已有安排 =====

    private class Arrangement(
        val sortKey: Int,
        val label: String,
        val focusIndex: Int,
        val onDelete: () -> Unit
    )

    private fun arrangements(): List<Arrangement> {
        val entries = mutableListOf<Arrangement>()
        schedule.holidays.forEach { holiday ->
            entries += Arrangement(
                sortKey = indexOf(holiday.week, holiday.day),
                label = context.getString(
                    R.string.holiday_row_suspend,
                    dayLabel(holiday.week, holiday.day)
                ),
                focusIndex = indexOf(holiday.week, holiday.day),
                onDelete = { confirmDeleteHoliday(holiday, holiday.week, holiday.day) }
            )
        }
        schedule.makeups.forEach { makeup ->
            entries += Arrangement(
                sortKey = indexOf(makeup.targetWeek, makeup.targetDay),
                label = context.getString(
                    R.string.holiday_row_makeup,
                    dayLabel(makeup.sourceWeek, makeup.sourceDay),
                    dayLabel(makeup.targetWeek, makeup.targetDay)
                ),
                focusIndex = indexOf(makeup.targetWeek, makeup.targetDay),
                onDelete = { confirmDeleteMakeup(makeup) }
            )
        }
        return entries.sortedBy { it.sortKey }
    }

    /** 列表行：点行体跳到日历上那一天，删除单独放右侧并二次确认，避免误删。 */
    private fun entryRow(entry: Arrangement): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_menu_row)
            minimumHeight = dp(44f)
            setPadding(dp(10f), dp(4f), dp(6f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4f) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                exitMakeupMode()
                selected = entry.focusIndex
                resetDuration()
                render()
                // 列表刚重建，等布局稳定后再按新坐标滚动
                scroll.post { scrollToSelection() }
            }
        }
        row.addView(TextView(context).apply {
            text = entry.label
            setTextColor(ContextCompat.getColor(context, R.color.body_ink))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (canEdit) {
            row.addView(TextView(context).apply {
                text = context.getString(R.string.holiday_delete)
                setTextColor(ContextCompat.getColor(context, R.color.danger))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14f), dp(10f), dp(8f), dp(10f))
                isClickable = true
                isFocusable = true
                contentDescription = "${context.getString(R.string.holiday_delete)}：${entry.label}"
                setOnClickListener { entry.onDelete() }
            })
        }
        return row
    }

    // ===== 提交 =====

    /** 从选中日起连续 N 天里真正会新增停课的日子：已有安排的跳过，学期末截断。 */
    private fun plannedHolidays(days: Int): List<HolidayCalendarView.DayItem> {
        if (selected < 0) return emptyList()
        val planned = mutableListOf<HolidayCalendarView.DayItem>()
        for (offset in 0 until days) {
            val item = calendar.itemAt(selected + offset) ?: break
            if (item.isHoliday || item.isMakeupTarget) continue
            planned += item
        }
        return planned
    }

    /** 提交停课；逐日做与后端一致的链式校验，避免服务端才报错。 */
    private fun submitHolidays(days: Int) {
        val drafts = plannedHolidays(days).map { DayHolidayDraft(it.week, it.day) }
        if (drafts.isEmpty()) {
            Ui.toast(context, context.getString(R.string.holiday_nothing_added))
            return
        }
        var state = schedule
        for (draft in drafts) {
            val error = runCatching { ScheduleValidator.validateHoliday(state, draft) }.exceptionOrNull()
            if (error != null) {
                Ui.toast(context, error.message ?: context.getString(R.string.holiday_invalid))
                return
            }
            state = state.copy(
                holidays = state.holidays + DayHoliday(
                    id = "preview", week = draft.week, day = draft.day, createdAt = "", updatedAt = ""
                )
            )
        }
        onAddHolidays(drafts)
    }

    private fun submitMakeup() {
        val source = calendar.itemAt(makeupSource) ?: return
        val target = calendar.itemAt(makeupTarget)
        if (target == null) {
            Ui.toast(context, context.getString(R.string.holiday_makeup_need_target))
            return
        }
        val draft = DayMakeupDraft(
            sourceWeek = source.week,
            sourceDay = source.day,
            targetWeek = target.week,
            targetDay = target.day
        )
        val error = runCatching { ScheduleValidator.validateMakeup(schedule, draft) }.exceptionOrNull()
        if (error != null) {
            Ui.toast(context, error.message ?: context.getString(R.string.holiday_invalid))
            return
        }
        selected = target.index
        exitMakeupMode()
        onAddMakeup(draft)
    }

    private fun confirmDeleteHoliday(holiday: DayHoliday, week: Int, day: Int) {
        Ui.confirm(
            context,
            context.getString(R.string.holiday_delete_suspend_title),
            context.getString(R.string.holiday_delete_suspend_content, dayLabel(week, day)),
            confirmText = context.getString(R.string.holiday_delete),
            confirmColor = DANGER_CONFIRM
        ) {
            selected = indexOf(week, day)
            onDeleteHoliday(holiday)
        }
    }

    private fun confirmDeleteMakeup(makeup: DayMakeup) {
        Ui.confirm(
            context,
            context.getString(R.string.holiday_delete_makeup_title),
            context.getString(
                R.string.holiday_delete_makeup_content,
                dayLabel(makeup.targetWeek, makeup.targetDay)
            ),
            confirmText = context.getString(R.string.holiday_delete),
            confirmColor = DANGER_CONFIRM
        ) {
            selected = indexOf(makeup.targetWeek, makeup.targetDay)
            onDeleteMakeup(makeup)
        }
    }

    // ===== 辅助 =====

    private enum class ActionKind { PRIMARY, GHOST, DANGER }

    private fun actionButton(label: String, kind: ActionKind, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            when (kind) {
                ActionKind.PRIMARY -> {
                    setBackgroundResource(R.drawable.bg_primary_button)
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                }
                ActionKind.GHOST -> {
                    setBackgroundResource(R.drawable.bg_ghost_button)
                    setTextColor(ContextCompat.getColor(context, R.color.ink))
                }
                ActionKind.DANGER -> {
                    setBackgroundResource(R.drawable.bg_danger_button)
                    setTextColor(ContextCompat.getColor(context, R.color.danger))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46f)
            ).apply { if (actions.childCount > 0) topMargin = dp(8f) }
            setOnClickListener { onClick() }
        }

    private fun exitMakeupMode() {
        makeupSource = -1
        makeupTarget = -1
    }

    private fun resetDuration() = durationStepper.setValue(1)

    /** 把日历滚到选中日附近；首帧用直接定位，之后从列表跳转时用平滑滚动。 */
    private fun scrollToSelection(smooth: Boolean = true) {
        if (selected < 0) return
        val offset = (calendarCard.top + calendar.top + calendar.scrollTopFor(selected))
            .coerceAtLeast(0)
        if (smooth) scroll.smoothScrollTo(0, offset) else scroll.scrollTo(0, offset)
    }

    private fun indexOf(week: Int, day: Int) = (week - 1) * 7 + (day - 1)

    private fun holidayAt(index: Int) =
        schedule.holidays.find { indexOf(it.week, it.day) == index }

    private fun makeupTargetingAt(index: Int) =
        schedule.makeups.find { indexOf(it.targetWeek, it.targetDay) == index }

    private fun makeupSourcedAt(index: Int) =
        schedule.makeups.find { indexOf(it.sourceWeek, it.sourceDay) == index }

    /** 「这一天有 3 门课：高数、大英 等」；无课时给出明确提示，避免在空日上做无意义的停课。 */
    private fun courseSummary(week: Int, day: Int): String {
        val names = schedule.courses
            .filter { it.day == day && week in it.weeks }
            .map { it.name }
            .distinct()
        if (names.isEmpty()) return context.getString(R.string.holiday_selected_none)
        val shown = names.take(COURSE_PREVIEW_COUNT).joinToString("、")
        val suffix = if (names.size > COURSE_PREVIEW_COUNT) {
            " " + context.getString(R.string.holiday_selected_more)
        } else {
            ""
        }
        return context.getString(R.string.holiday_selected_courses, names.size, shown + suffix)
    }

    /** 「10月6日 周一」；开学日期缺失时回退「第 5 周周二」，而不是拿今天硬推一个错日期。 */
    private fun dayLabel(week: Int, day: Int): String {
        val date = if (DateUtils.hasSemesterStart(schedule.semesterStart)) {
            DateUtils.datesForWeek(schedule.semesterStart, week).getOrNull(day - 1)
        } else {
            null
        }
        return if (date != null) {
            context.getString(R.string.holiday_date_label, "${date.month}月${date.day}日", dayName(day))
        } else {
            context.getString(R.string.holiday_week_fallback, week, dayName(day))
        }
    }

    private fun dayName(day: Int) = DAY_NAMES.getOrElse(day - 1) { "?" }

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val SHEET_HEIGHT_RATIO = 0.9f
        private const val MAX_SUSPEND_DAYS = 31
        private const val COURSE_PREVIEW_COUNT = 3
        private const val DANGER_CONFIRM = 1
        private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")
    }
}
