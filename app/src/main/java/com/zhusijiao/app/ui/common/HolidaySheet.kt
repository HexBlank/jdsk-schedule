package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.DayHoliday
import com.zhusijiao.app.domain.DayHolidayDraft
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.DayMakeupDraft
import com.zhusijiao.app.domain.HolidayDateRange
import com.zhusijiao.app.domain.HolidayPlanner
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleValidator
import com.zhusijiao.app.util.Ui

/** 停课与补课入口始终可见；明确填写起止日期或补课日、原上课日，再保存。 */
class HolidaySheet(
    context: Context,
    schedule: Schedule,
    initialWeek: Int,
    initialDay: Int,
    private val canEdit: Boolean,
    private val onAddHolidays: (List<DayHolidayDraft>) -> Unit,
    private val onAddMakeup: (DayMakeupDraft) -> Unit,
    private val onDeleteEntries: (List<DayHoliday>, List<DayMakeup>) -> Unit
) : SheetDialog(context) {
    private var schedule = schedule
    private val initial = HolidayPlanner.index(initialWeek.coerceIn(1, schedule.totalWeeks.coerceAtLeast(1)), initialDay.coerceIn(1, 7))
    private var suspendRange = HolidayDateRange(initial, initial)
    private var target = initial
    private var source = -1
    private var mode = 0
    private var showingList = !canEdit
    private var saving = false
    private var picker: SheetDialog? = null
    private val modes: SegmentedChoiceView by lazy { findViewById(R.id.holidayMode) }
    private val title: TextView by lazy { findViewById(R.id.holidayTitle) }
    private val preview: TextView by lazy { findViewById(R.id.holidayPreview) }
    private val submit: TextView by lazy { findViewById(R.id.holidaySubmit) }

    init {
        setContentView(R.layout.dialog_holiday)
        setCanceledOnTouchOutside(true)
        modes.configure(listOf(context.getString(R.string.holiday_suspend_tab), context.getString(R.string.holiday_makeup_tab)),
            mode, "") { selected ->
            if (saving) modes.setSelection(mode) else {
                mode = selected
                render()
            }
        }
        findViewById<View>(R.id.holidayManage).setOnClickListener { showList(true) }
        findViewById<View>(R.id.holidayBack).setOnClickListener { showList(false) }
        findViewById<View>(R.id.holidayFirstRow).setOnClickListener {
            if (!saving && canEdit) pickFirst()
        }
        findViewById<View>(R.id.holidayLastRow).setOnClickListener {
            if (!saving && canEdit) {
                if (mode == 0) pickDay(R.string.holiday_end_field, suspendRange.end,
                    range = suspendRange.start..suspendRange.end) { suspendRange = suspendRange.endingAt(it); render() }
                else pickSource()
            }
        }
        render()
    }

    override fun dismiss() {
        picker?.dismiss()
        super.dismiss()
    }

    fun setSaving(value: Boolean) {
        saving = value
        render()
    }

    fun applySchedule(updated: Schedule) {
        schedule = updated
        val last = (updated.totalWeeks * 7 - 1).coerceAtLeast(0)
        suspendRange = HolidayDateRange(suspendRange.start.coerceAtMost(last), suspendRange.end.coerceAtMost(last))
        target = target.coerceIn(0, last)
        source = -1
        saving = false
        render()
    }

    private fun showList(show: Boolean) {
        showingList = show
        render()
    }

    private fun render() {
        setPanelHeight(if (showingList) (context.resources.displayMetrics.heightPixels * 0.8f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
        findViewById<View>(R.id.holidayEditorPage).visibility = if (showingList) View.GONE else View.VISIBLE
        findViewById<View>(R.id.holidayListPage).visibility = if (showingList) View.VISIBLE else View.GONE
        findViewById<View>(R.id.holidayBack).visibility = if (showingList && canEdit) View.VISIBLE else View.GONE
        findViewById<View>(R.id.holidayManage).visibility = if (showingList) View.GONE else View.VISIBLE
        title.setText(if (showingList) R.string.holiday_list_label else R.string.holiday_title)
        if (showingList) {
            renderList()
            return
        }
        modes.setSelection(mode)
        val usable = DateUtils.hasSemesterStart(schedule.semesterStart) && schedule.totalWeeks > 0
        if (mode == 0) renderSuspend(usable) else renderMakeup(usable)
        findViewById<View>(R.id.holidayFirstRow).isEnabled = !saving && usable
        findViewById<View>(R.id.holidayLastRow).isEnabled = !saving && usable
        submit.alpha = if (submit.isEnabled) 1f else 0.4f
        if (saving) submit.setText(R.string.holiday_saving)
        preview.visibility = if (preview.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderSuspend(usable: Boolean) {
        bindField(true, R.string.holiday_start_field, dayLabel(suspendRange.start))
        bindField(false, R.string.holiday_end_field, dayLabel(suspendRange.end))
        val drafts = HolidayPlanner.range(schedule, suspendRange.start, suspendRange.end)
        preview.text = when {
            !usable -> context.getString(R.string.holiday_calendar_missing)
            drafts.isEmpty() -> context.getString(R.string.holiday_all_suspended)
            else -> context.getString(R.string.holiday_range_value, suspendRange.end - suspendRange.start + 1, drafts.size)
        }
        submit.text = if (drafts.isEmpty()) context.getString(R.string.holiday_suspend_tab)
            else context.getString(R.string.holiday_action_suspend_days, drafts.size)
        submit.isEnabled = !saving && usable && drafts.isNotEmpty()
        submit.setOnClickListener { if (!saving) submitHolidays(drafts) }
    }

    private fun renderMakeup(usable: Boolean) {
        val options = HolidayPlanner.makeupSourceDays(schedule, target)
        bindField(true, R.string.holiday_target_field, dayLabel(target))
        bindField(false, R.string.holiday_source_field, if (source >= 0) dayLabel(source)
            else context.getString(if (options.isEmpty() && target in HolidayPlanner.makeupTargetDays(schedule))
                R.string.holiday_source_empty else R.string.holiday_source_unset))
        val draft = makeupDraft()
        val error = draft?.let { runCatching { ScheduleValidator.validateMakeup(schedule, it) }.exceptionOrNull()?.message }
        val courses = if (source >= 0) HolidayPlanner.sourceCourses(schedule, source) else emptyList()
        val existingCount = ScheduleOccurrences.coursesOn(schedule, target / 7 + 1, target % 7 + 1).size
        preview.text = when {
            !usable -> context.getString(R.string.holiday_calendar_missing)
            target !in HolidayPlanner.makeupTargetDays(schedule) -> context.getString(R.string.holiday_target_unavailable)
            error != null -> error
            source < 0 -> ""
            else -> context.getString(R.string.holiday_makeup_preview, dayLabel(target), dayLabel(source)) + "\n" +
                context.getString(R.string.holiday_makeup_course_value, courses.size, courses.map { it.name }.distinct().joinToString("、")) +
                if (existingCount > 0) "\n" + context.getString(R.string.holiday_target_courses_value, existingCount) else ""
        }
        submit.setText(R.string.holiday_makeup_submit)
        submit.isEnabled = !saving && usable && source in options && error == null
        submit.setOnClickListener {
            if (!saving && draft != null && source in options && validate { ScheduleValidator.validateMakeup(schedule, draft) }) {
                setSaving(true)
                onAddMakeup(draft)
            }
        }
    }

    private fun bindField(first: Boolean, label: Int, value: String) {
        findViewById<TextView>(if (first) R.id.holidayFirstLabel else R.id.holidayLastLabel).setText(label)
        findViewById<TextView>(if (first) R.id.holidayFirstValue else R.id.holidayLastValue).text = value
        findViewById<View>(if (first) R.id.holidayFirstRow else R.id.holidayLastRow).contentDescription = "${context.getString(label)}，$value"
    }

    private fun pickFirst() {
        if (mode == 0) {
            pickDay(R.string.holiday_start_field, suspendRange.start, range = suspendRange.start..suspendRange.end) {
                suspendRange = suspendRange.startingAt(it)
                render()
            }
        } else {
            val targets = HolidayPlanner.makeupTargetDays(schedule)
            if (targets.isEmpty()) { Ui.toastError(context, context.getString(R.string.holiday_no_target)); return }
            pickDay(R.string.holiday_target_field, target, targets) {
                target = it
                if (source !in HolidayPlanner.makeupSourceDays(schedule, target)) source = -1
                render()
            }
        }
    }

    private fun pickSource() {
        val options = HolidayPlanner.makeupSourceDays(schedule, target)
        if (options.isEmpty()) {
            Ui.toastError(context, context.getString(if (target !in HolidayPlanner.makeupTargetDays(schedule))
                R.string.holiday_target_unavailable else R.string.holiday_source_empty))
            return
        }
        fun pickOther() = pickDay(R.string.holiday_source_field, if (source >= 0) source else target, options) {
            source = it
            render()
        }
        val pending = schedule.holidays.filter { index(it.week, it.day) in options }.sortedBy { index(it.week, it.day) }
        if (pending.isEmpty()) { pickOther(); return }
        val actions = pending.map { day ->
            ActionSheet.Item(dayLabel(index(day.week, day.day)), R.drawable.ic_menu_holiday) {
                source = index(day.week, day.day)
                render()
            }
        } + ActionSheet.Item(context.getString(R.string.holiday_other_date), R.drawable.ic_menu_holiday, group = 1) { pickOther() }
        showPicker(ActionSheet(context, context.getString(R.string.holiday_source_field), actions))
    }

    private fun pickDay(label: Int, selected: Int, selectable: Set<Int>? = null, range: IntRange? = null, onPick: (Int) -> Unit) {
        showPicker(CalendarDaySheet(context, context.getString(label), schedule, selected, selectable, range, onPick))
    }

    private fun showPicker(sheet: SheetDialog) {
        picker = sheet
        sheet.setOnDismissListener { if (picker === sheet) picker = null }
        sheet.show()
    }

    private fun makeupDraft(): DayMakeupDraft? = if (source < 0) null else
        DayMakeupDraft(source / 7 + 1, source % 7 + 1, target / 7 + 1, target % 7 + 1)

    private data class Arrangement(
        val date: Int,
        val label: String,
        val holidays: List<DayHoliday> = emptyList(),
        val makeups: List<DayMakeup> = emptyList()
    )

    private fun renderList() {
        val container = findViewById<LinearLayout>(R.id.holidayList)
        container.removeAllViews()
        val entries = mutableListOf<Arrangement>()
        HolidayPlanner.groups(schedule.holidays).forEach { days ->
            val first = index(days.first().week, days.first().day)
            val last = index(days.last().week, days.last().day)
            entries += Arrangement(first, context.getString(R.string.holiday_group_value,
                if (first == last) dayLabel(first) else rangeLabel(first, last), days.size), holidays = days)
        }
        schedule.makeups.forEach { makeup ->
            val target = index(makeup.targetWeek, makeup.targetDay)
            entries += Arrangement(target, context.getString(R.string.holiday_row_makeup,
                dayLabel(index(makeup.sourceWeek, makeup.sourceDay)), dayLabel(target)), makeups = listOf(makeup))
        }
        if (entries.isEmpty()) {
            container.addView(TextView(context).apply {
                text = context.getString(R.string.holiday_empty)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }
        val group = MenuRows.group(container)
        entries.sortedBy { it.date }.forEachIndexed { i, entry ->
            if (i > 0) MenuRows.divider(group)
            val row = MenuRows.row(group, entry.label, R.drawable.ic_menu_holiday) {}.apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            if (canEdit) row.addView(TextView(context).apply {
                text = context.getString(R.string.holiday_delete)
                textSize = 14f
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setTextColor(ContextCompat.getColor(context, R.color.danger_action))
                contentDescription = "${text}：${entry.label}"
                isEnabled = !saving
                setOnClickListener {
                    if (!saving) {
                        setSaving(true)
                        onDeleteEntries(entry.holidays, entry.makeups)
                    }
                }
            }, row.childCount - 1)
            row.findViewById<View>(R.id.actionRowCheck).visibility = View.GONE
            group.addView(row)
        }
    }

    private fun submitHolidays(drafts: List<DayHolidayDraft>) {
        if (saving || drafts.isEmpty()) return
        var state = schedule
        for (draft in drafts) {
            if (!validate { ScheduleValidator.validateHoliday(state, draft) }) return
            state = state.copy(holidays = state.holidays + DayHoliday("preview", draft.week, draft.day, "", ""))
        }
        setSaving(true)
        onAddHolidays(drafts)
    }

    private fun validate(block: () -> Unit): Boolean {
        val error = runCatching(block).exceptionOrNull() ?: return true
        Ui.toastError(context, error.message ?: context.getString(R.string.holiday_invalid))
        return false
    }

    private fun rangeLabel(first: Int, last: Int): String {
        fun dateLabel(index: Int): String {
            if (!DateUtils.hasSemesterStart(schedule.semesterStart)) return dayLabel(index)
            val date = DateUtils.datesForWeek(schedule.semesterStart, index / 7 + 1)[index % 7]
            return "${date.month}月${date.day}日"
        }
        return context.getString(R.string.holiday_range_title, dateLabel(first), dateLabel(last))
    }

    private fun dayLabel(index: Int): String {
        val week = index / 7 + 1
        val day = index % 7 + 1
        val name = listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]
        val date = if (DateUtils.hasSemesterStart(schedule.semesterStart))
            DateUtils.datesForWeek(schedule.semesterStart, week).getOrNull(day - 1) else null
        return if (date == null) context.getString(R.string.holiday_week_fallback, week, name)
            else context.getString(R.string.holiday_date_label, "${date.month}月${date.day}日", name)
    }

    private fun index(week: Int, day: Int) = HolidayPlanner.index(week, day)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
