package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.PersonalEventDraft
import com.zhusijiao.app.domain.PersonalEventValidator
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.util.Ui

/**
 * 自定义日程编辑器。
 *
 * 时间有两种填法：「按节次」直接点起始节与时长；「按时间」填钟点，占位节次由
 * [ScheduleTime.sectionSpanFor] 换算并**在摘要里写明占哪几节**——用户填了 18:30
 * 却看到块画在 19:15 那一行，不解释会被当成 bug。
 *
 * 与课重叠不阻止保存，只做二次确认；能算出空节次时，摘要下方给一条「改到第 N 节」
 * 的一键避让。见 docs/DECISIONS.md D18。
 */
class EventEditorSheet(
    context: Context,
    private val schedule: Schedule,
    private val events: List<PersonalEvent>,
    private val editing: PersonalEvent?,
    initialWeek: Int,
    initialDay: Int,
    initialSection: Int,
    private val onSave: (PersonalEventDraft) -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    private val slots = ScheduleTime.slotsOf(schedule.timeSlots)
    private val totalWeeks = schedule.totalWeeks.coerceAtLeast(1)
    private val day = (editing?.day ?: initialDay).coerceIn(1, 7)
    private val anchorWeek = (editing?.weeks?.minOrNull() ?: initialWeek).coerceIn(1, totalWeeks)

    /** 0 = 按节次，1 = 按时间。编辑既有日程时按它当初的填法回显。 */
    private var mode = if (editing?.startTime != null) MODE_CLOCK else MODE_SECTION
    private var color: String? = editing?.color

    private val nameField by lazy { findViewById<EditText>(R.id.eventName) }
    private val placeField by lazy { findViewById<EditText>(R.id.eventPlace) }
    private val noteField by lazy { findViewById<EditText>(R.id.eventNote) }
    private val startChips by lazy { findViewById<ChipRowView>(R.id.eventStartChips) }
    private val spanChips by lazy { findViewById<ChipRowView>(R.id.eventSpanChips) }
    private val endWeekChips by lazy { findViewById<ChipRowView>(R.id.eventEndWeekChips) }
    private val repeatChoice by lazy { findViewById<SegmentedChoiceView>(R.id.eventRepeat) }
    private val startHour by lazy { findViewById<StepperFieldView>(R.id.eventStartHour) }
    private val startMinute by lazy { findViewById<StepperFieldView>(R.id.eventStartMinute) }
    private val endHour by lazy { findViewById<StepperFieldView>(R.id.eventEndHour) }
    private val endMinute by lazy { findViewById<StepperFieldView>(R.id.eventEndMinute) }

    init {
        setContentView(R.layout.dialog_event_editor)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)
        // 键盘弹出时把整个面板抬到输入法上方，避免名称/地点输入框被遮挡
        Ui.liftAboveIme(findViewById(android.R.id.content))

        findViewById<TextView>(R.id.eventTitle).setText(
            if (editing == null) R.string.event_new_title else R.string.event_edit_title
        )
        nameField.setText(editing?.title.orEmpty())
        placeField.setText(editing?.position.orEmpty())
        noteField.setText(editing?.note.orEmpty())

        configureSectionInputs(initialSection)
        configureClockInputs(initialSection)
        configureRepeat()
        configureColor()

        findViewById<SegmentedChoiceView>(R.id.eventTimeMode).configure(
            items = listOf(
                context.getString(R.string.event_mode_section),
                context.getString(R.string.event_mode_clock)
            ),
            initialIndex = mode,
            contentDescriptionPrefix = context.getString(R.string.event_time_label)
        ) { index ->
            mode = index
            applyMode()
            updateSummary()
        }
        applyMode()
        updateSummary()

        findViewById<View>(R.id.eventCancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.eventSave).setOnClickListener { submit() }
    }

    // ===== 各段输入 =====

    private fun configureSectionInputs(initialSection: Int) {
        val start = (editing?.startSection ?: initialSection).coerceIn(1, ScheduleTime.MAX_SECTION)
        val span = (editing?.span ?: 1).coerceIn(1, MAX_SPAN)
        startChips.configure(
            items = (1..ScheduleTime.MAX_SECTION).map { it.toString() },
            initialIndex = start - 1,
            contentDescriptionPrefix = "第",
            contentDescriptionSuffix = "节",
            onSelected = { updateSummary() }
        )
        spanChips.configure(
            items = (1..MAX_SPAN).map { it.toString() },
            initialIndex = span - 1,
            contentDescriptionPrefix = "连上",
            contentDescriptionSuffix = "节",
            onSelected = { updateSummary() }
        )
    }

    /**
     * 钟点输入复用项目自有的 [StepperFieldView]，不引入系统 TimePickerDialog。
     * 分钟用「第几个 5 分钟」做内部值、formatter 显示真实分钟，这样不必给步进器加 step 参数。
     */
    private fun configureClockInputs(initialSection: Int) {
        val slot = slots.find { it.number == initialSection.coerceIn(1, ScheduleTime.MAX_SECTION) }
        val defaultStart = ScheduleTime.minutesOf(editing?.startTime)
            ?: ScheduleTime.minutesOf(slot?.startTime) ?: 19 * 60
        val defaultEnd = ScheduleTime.minutesOf(editing?.endTime)
            ?: ScheduleTime.minutesOf(slot?.endTime) ?: (defaultStart + 90)
        bindClock(startHour, startMinute, defaultStart, R.string.event_clock_start_label)
        bindClock(endHour, endMinute, defaultEnd, R.string.event_clock_end_label)
    }

    private fun bindClock(hour: StepperFieldView, minute: StepperFieldView, value: Int, labelRes: Int) {
        val label = context.getString(labelRes)
        hour.contentDescription = label + context.getString(R.string.event_hour_desc)
        minute.contentDescription = label + context.getString(R.string.event_minute_desc)
        hour.configure(0, 23, value / 60, { "%02d 时".format(it) }) { updateSummary() }
        minute.configure(0, (60 / MINUTE_STEP) - 1, (value % 60) / MINUTE_STEP, {
            "%02d 分".format(it * MINUTE_STEP)
        }) { updateSummary() }
    }

    private fun configureRepeat() {
        val initial = repeatIndexOf(editing)
        repeatChoice.configure(
            items = listOf(
                context.getString(R.string.event_repeat_once),
                context.getString(R.string.event_repeat_weekly),
                context.getString(R.string.event_repeat_odd),
                context.getString(R.string.event_repeat_even)
            ),
            initialIndex = initial,
            contentDescriptionPrefix = context.getString(R.string.event_repeat_label)
        ) {
            applyRepeatVisibility()
            updateSummary()
        }
        val end = (editing?.weeks?.maxOrNull() ?: totalWeeks).coerceIn(anchorWeek, totalWeeks)
        endWeekChips.configure(
            items = (anchorWeek..totalWeeks).map { it.toString() },
            initialIndex = (end - anchorWeek).coerceIn(0, totalWeeks - anchorWeek),
            contentDescriptionPrefix = "第",
            contentDescriptionSuffix = "周",
            onSelected = { updateSummary() }
        )
        applyRepeatVisibility()
    }

    private fun configureColor() {
        val row = findViewById<View>(R.id.eventColorRow)
        row.setOnClickListener {
            ColorPickerSheet(
                context,
                courseName = nameField.text.toString().trim()
                    .ifEmpty { context.getString(R.string.event_badge) },
                autoColor = DEFAULT_EVENT_COLOR,
                currentManual = color,
                onPick = { hex -> color = hex; paintSwatch() },
                onReset = { color = null; paintSwatch() }
            ).show()
        }
        paintSwatch()
    }

    private fun paintSwatch() {
        val value = ScheduleView.manualPalette(color)?.background ?: DEFAULT_EVENT_COLOR
        findViewById<View>(R.id.eventColorSwatch).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(value)
        }
    }

    private fun applyMode() {
        findViewById<View>(R.id.eventSectionArea).visibility =
            if (mode == MODE_SECTION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.eventClockArea).visibility =
            if (mode == MODE_CLOCK) View.VISIBLE else View.GONE
    }

    private fun applyRepeatVisibility() {
        findViewById<View>(R.id.eventRepeatEndArea).visibility =
            if (repeatChoice.selectedIndex == REPEAT_ONCE) View.GONE else View.VISIBLE
    }

    // ===== 草稿与摘要 =====

    private fun startTimeText(): String =
        ScheduleTime.formatTime(startHour.value * 60 + startMinute.value * MINUTE_STEP)

    private fun endTimeText(): String =
        ScheduleTime.formatTime(endHour.value * 60 + endMinute.value * MINUTE_STEP)

    private fun weeks(): List<Int> {
        val end = (anchorWeek + endWeekChips.selection).coerceIn(anchorWeek, totalWeeks)
        return when (repeatChoice.selectedIndex) {
            REPEAT_ONCE -> listOf(anchorWeek)
            REPEAT_WEEKLY -> (anchorWeek..end).toList()
            REPEAT_ODD -> (anchorWeek..end).filter { it % 2 == 1 }
            REPEAT_EVEN -> (anchorWeek..end).filter { it % 2 == 0 }
            else -> listOf(anchorWeek)
        }
    }

    private fun draft(): PersonalEventDraft {
        val start = startChips.selection + 1
        val span = spanChips.selection + 1
        return PersonalEventDraft(
            title = nameField.text.toString(),
            position = placeField.text.toString(),
            note = noteField.text.toString(),
            day = day,
            startSection = start,
            endSection = (start + span - 1).coerceAtMost(ScheduleTime.MAX_SECTION),
            startTime = if (mode == MODE_CLOCK) startTimeText() else null,
            endTime = if (mode == MODE_CLOCK) endTimeText() else null,
            weeks = weeks(),
            color = color
        )
    }

    /** 校验通过的草稿；不合法时返回 null（调用方决定要不要提示）。 */
    private fun normalized(): PersonalEventDraft? = runCatching {
        PersonalEventValidator.normalize(draft(), totalWeeks, slots)
    }.getOrNull()

    private fun updateSummary() {
        val summary = findViewById<TextView>(R.id.eventSummary)
        val normalized = normalized()
        if (normalized == null) {
            summary.text = context.getString(R.string.event_summary_label)
            findViewById<View>(R.id.eventAvoid).visibility = View.GONE
            return
        }
        val weekText = context.getString(
            R.string.event_summary_count,
            ScheduleView.formatWeekSummary(normalized.weeks),
            normalized.weeks.size
        )
        val dayText = dayName(day)
        summary.text = when {
            normalized.startTime == null -> context.getString(
                R.string.event_summary_section,
                dayText,
                normalized.startSection,
                normalized.endSection,
                ScheduleTime.rangeText(slots, normalized.startSection, normalized.endSection)
                    ?.let { " · $it" }.orEmpty(),
                weekText
            )
            // 整段落在两节之间的空档里（如 12:30–13:30 在午休），占位只能塌缩到相邻那一节
            fallsInGap(normalized) -> context.getString(
                R.string.event_summary_gap,
                dayText,
                "${normalized.startTime}–${normalized.endTime}",
                normalized.startSection,
                weekText
            )
            else -> context.getString(
                R.string.event_summary_clock,
                dayText,
                "${normalized.startTime}–${normalized.endTime}",
                normalized.startSection,
                normalized.endSection,
                weekText
            )
        }
        updateAvoidHint(normalized)
    }

    /** 自定义时间是否整段都不落在任何一节的上课时间内。 */
    private fun fallsInGap(draft: PersonalEventDraft): Boolean {
        val start = ScheduleTime.minutesOf(draft.startTime) ?: return false
        val end = ScheduleTime.minutesOf(draft.endTime) ?: return false
        return slots.none { slot ->
            val slotStart = ScheduleTime.minutesOf(slot.startTime)
            val slotEnd = ScheduleTime.minutesOf(slot.endTime)
            slotStart != null && slotEnd != null && slotStart < end && start < slotEnd
        }
    }

    private fun updateAvoidHint(draft: PersonalEventDraft) {
        val avoid = findViewById<TextView>(R.id.eventAvoid)
        val conflicts = ScheduleOccurrences.conflictsForEvent(schedule, draft)
        // 按时间填的日程占位由换算决定，改节次没有意义，所以只在按节次模式下给避让
        val free = if (conflicts.isEmpty() || mode != MODE_SECTION) null
        else ScheduleOccurrences.nearestFreeStartSection(schedule, draft)
        if (free == null) {
            avoid.visibility = View.GONE
            return
        }
        avoid.visibility = View.VISIBLE
        avoid.text = context.getString(R.string.event_avoid_hint, conflicts.first().courseName, free)
        avoid.setOnClickListener {
            startChips.setSelection(free - 1, notify = true)
        }
    }

    // ===== 保存 =====

    private fun submit() {
        val draft = draft()
        val normalized = try {
            PersonalEventValidator.normalize(draft, totalWeeks, slots)
        } catch (error: IllegalArgumentException) {
            Ui.toast(context, error.message ?: context.getString(R.string.common_load_failed))
            return
        }
        ScheduleOccurrences.overlappingEvent(events, normalized, editing?.id)?.let { existing ->
            Ui.toast(context, "这个时段已经有日程「${existing.title}」了")
            return
        }
        val conflicts = ScheduleOccurrences.conflictsForEvent(schedule, normalized)
        val submit = {
            dismiss()
            onSave(normalized)
        }
        if (conflicts.isEmpty()) {
            submit()
            return
        }
        val lines = conflicts.joinToString("\n") { conflict ->
            context.getString(
                R.string.event_conflict_line,
                conflict.courseName,
                ScheduleView.consecutiveRanges(conflict.weeks).joinToString("、")
            )
        }
        Ui.confirm(
            context,
            context.getString(R.string.event_conflict_title),
            context.getString(R.string.event_conflict_content, lines),
            confirmText = context.getString(R.string.event_conflict_continue),
            onConfirm = submit
        )
    }

    private fun repeatIndexOf(event: PersonalEvent?): Int {
        val weeks = event?.weeks ?: return REPEAT_WEEKLY
        if (weeks.size <= 1) return REPEAT_ONCE
        return when (ScheduleView.alternatingWeekBadge(weeks)) {
            "单周" -> REPEAT_ODD
            "双周" -> REPEAT_EVEN
            else -> REPEAT_WEEKLY
        }
    }

    private fun dayName(value: Int) = listOf("一", "二", "三", "四", "五", "六", "日")
        .getOrElse(value - 1) { "?" }

    companion object {
        private const val MODE_SECTION = 0
        private const val MODE_CLOCK = 1
        private const val REPEAT_ONCE = 0
        private const val REPEAT_WEEKLY = 1
        private const val REPEAT_ODD = 2
        private const val REPEAT_EVEN = 3

        /** 最长连上几节；再长的安排用两条日程表达更清楚。 */
        private const val MAX_SPAN = 6

        /** 分钟步进粒度：社团活动用 5 分钟足够，长按连发能很快滚到目标值。 */
        private const val MINUTE_STEP = 5

        /** 与 TimetableView 的默认日程色保持一致（冷灰蓝）。 */
        const val DEFAULT_EVENT_COLOR = 0xFF4A5A72.toInt()
    }
}
