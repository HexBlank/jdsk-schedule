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
 * 主路径只有三件事：起个名字、在节次网格上点出占用哪几节、选重复方式。
 * 节次用 [SectionPickerView] 一次铺开 12 节直接点选，不再让用户填「起始节 + 时长几节」
 * 那种要自己做算术的抽象输入；地点、备注、颜色都收进「更多」，默认不占版面。
 *
 * 「精确到分钟」是可选开关：打开后时间按所选节次预填，只需微调；它**只改显示文案**，
 * 块画在哪一格始终由用户点的节次决定。时间被改到明显属于别的节次时，给一条可点的
 * 建议把格子挪过去，避免出现「写早上 8 点、块却画在晚上」。见 docs/DECISIONS.md D18。
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

    private var exact = editing?.startTime != null
    private var color: String? = editing?.color

    private val nameField by lazy { findViewById<EditText>(R.id.eventName) }
    private val placeField by lazy { findViewById<EditText>(R.id.eventPlace) }
    private val noteField by lazy { findViewById<EditText>(R.id.eventNote) }
    private val sectionPicker by lazy { findViewById<SectionPickerView>(R.id.eventSections) }
    private val repeatChoice by lazy { findViewById<SegmentedChoiceView>(R.id.eventRepeat) }
    private val endWeekStepper by lazy { findViewById<StepperFieldView>(R.id.eventEndWeek) }
    private val startHour by lazy { findViewById<StepperFieldView>(R.id.eventStartHour) }
    private val startMinute by lazy { findViewById<StepperFieldView>(R.id.eventStartMinute) }
    private val endHour by lazy { findViewById<StepperFieldView>(R.id.eventEndHour) }
    private val endMinute by lazy { findViewById<StepperFieldView>(R.id.eventEndMinute) }
    private val exactToggle by lazy { findViewById<ToggleView>(R.id.eventExactToggle) }

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

        configureSections(initialSection)
        configureClock()
        configureRepeat()
        configureMore()
        refresh()

        findViewById<View>(R.id.eventCancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.eventSave).setOnClickListener { submit() }
    }

    // ===== 各段输入 =====

    private fun configureSections(initialSection: Int) {
        val initial = editing?.let { it.startSection..it.endSection }
            ?: initialSection.coerceIn(1, ScheduleTime.MAX_SECTION).let { it..it }
        sectionPicker.configure(slots, initial) { refresh() }
    }

    /**
     * 钟点输入复用项目自有的 [StepperFieldView]，不引入系统 TimePickerDialog。
     *
     * 分钟逐分钟可调，不做 5 分钟对齐：学校作息本身就有 11:31 这种非整五的时刻，
     * 按 5 分钟取整连自家节次的开始时间都回填不准，还是静默改掉用户填的值。
     * 快速调整靠 [StepperFieldView] 的长按连发。
     */
    private fun configureClock() {
        val range = sectionPicker.selection
        val defaultStart = ScheduleTime.minutesOf(editing?.startTime)
            ?: ScheduleTime.minutesOf(slots.find { it.number == range?.first }?.startTime)
            ?: (19 * 60)
        val defaultEnd = ScheduleTime.minutesOf(editing?.endTime)
            ?: ScheduleTime.minutesOf(slots.find { it.number == range?.last }?.endTime)
            ?: (defaultStart + 90)
        bindClock(startHour, startMinute, defaultStart, R.string.event_clock_start_label)
        bindClock(endHour, endMinute, defaultEnd, R.string.event_clock_end_label)
        exactToggle.setChecked(exact, animate = false)
        exactToggle.onCheckedChange = { checked ->
            exact = checked
            // 刚打开时按当前所选节次预填，多数人不用再动
            if (checked) prefillClockFromSections()
            refresh()
        }
        findViewById<View>(R.id.eventExactRow).setOnClickListener { exactToggle.toggle() }
        findViewById<View>(R.id.eventClockSuggest).setOnClickListener {
            suggestedRange()?.let { sectionPicker.setSelection(it); refresh() }
        }
    }

    private fun bindClock(hour: StepperFieldView, minute: StepperFieldView, value: Int, labelRes: Int) {
        val label = context.getString(labelRes)
        hour.contentDescription = label + context.getString(R.string.event_hour_desc)
        minute.contentDescription = label + context.getString(R.string.event_minute_desc)
        hour.configure(0, 23, value / 60, { "%02d 时".format(it) }) { refresh() }
        minute.configure(0, 59, value % 60, { "%02d 分".format(it) }) { refresh() }
    }

    private fun prefillClockFromSections() {
        val range = sectionPicker.selection ?: return
        ScheduleTime.minutesOf(slots.find { it.number == range.first }?.startTime)?.let {
            startHour.setValue(it / 60)
            startMinute.setValue(it % 60)
        }
        ScheduleTime.minutesOf(slots.find { it.number == range.last }?.endTime)?.let {
            endHour.setValue(it / 60)
            endMinute.setValue(it % 60)
        }
    }

    private fun configureRepeat() {
        repeatChoice.configure(
            items = listOf(
                context.getString(R.string.event_repeat_once),
                context.getString(R.string.event_repeat_weekly),
                context.getString(R.string.event_repeat_odd),
                context.getString(R.string.event_repeat_even)
            ),
            initialIndex = repeatIndexOf(editing),
            contentDescriptionPrefix = context.getString(R.string.event_repeat_label)
        ) { refresh() }
        val end = (editing?.weeks?.maxOrNull() ?: totalWeeks).coerceIn(anchorWeek, totalWeeks)
        endWeekStepper.contentDescription = context.getString(R.string.event_repeat_end_label)
        endWeekStepper.configure(anchorWeek, totalWeeks, end, {
            context.getString(R.string.event_repeat_end_value, it)
        }) { refresh() }
    }

    private fun configureMore() {
        val area = findViewById<View>(R.id.eventMoreArea)
        val toggle = findViewById<View>(R.id.eventMoreToggle)
        // 编辑已有日程时这些字段有值就直接展开，免得用户以为内容丢了
        val hasContent = !editing?.position.isNullOrBlank() ||
            !editing?.note.isNullOrBlank() || editing?.color != null
        area.visibility = if (hasContent) View.VISIBLE else View.GONE
        toggle.visibility = if (hasContent) View.GONE else View.VISIBLE
        toggle.setOnClickListener {
            area.visibility = View.VISIBLE
            toggle.visibility = View.GONE
        }
        findViewById<View>(R.id.eventColorRow).setOnClickListener {
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

    // ===== 实时反馈 =====

    /** 任何输入变化后统一刷新顶部摘要、各段提示与冲突避让。 */
    private fun refresh() {
        val range = sectionPicker.selection
        updateHeadline(range)
        updateSectionHint(range)
        updateClockArea(range)
        updateRepeatArea()
        updateAvoidHint()
    }

    private fun updateHeadline(range: IntRange?) {
        val headline = findViewById<TextView>(R.id.eventHeadline)
        if (range == null) {
            headline.setText(R.string.event_headline_pending)
            return
        }
        headline.text = context.getString(
            R.string.event_headline,
            "周" + dayName(day),
            timeSummary(range)
        )
    }

    /** 顶部摘要里的时间部分：开了精确时间就用它，否则用节次作息。 */
    private fun timeSummary(range: IntRange): String {
        val sections = sectionText(range)
        val clock = if (exact) currentTimeText()
        else ScheduleTime.rangeText(slots, range.first, range.last)
        return if (clock.isNullOrEmpty()) "第 $sections 节" else "第 $sections 节 · $clock"
    }

    private fun updateSectionHint(range: IntRange?) {
        val hint = findViewById<TextView>(R.id.eventSectionHint)
        hint.text = when {
            range == null -> context.getString(R.string.event_section_hint_start)
            sectionPicker.awaitingEnd -> context.getString(R.string.event_section_hint_end)
            else -> context.getString(
                R.string.event_section_hint_done,
                sectionText(range),
                ScheduleTime.rangeText(slots, range.first, range.last).orEmpty()
            )
        }
    }

    private fun updateClockArea(range: IntRange?) {
        findViewById<View>(R.id.eventClockArea).visibility = if (exact) View.VISIBLE else View.GONE
        if (!exact || range == null) return
        findViewById<TextView>(R.id.eventClockNote).text =
            context.getString(R.string.event_clock_note, sectionText(range))
        val suggest = findViewById<TextView>(R.id.eventClockSuggest)
        val suggestion = suggestedRange()
        if (suggestion == null || suggestion == range) {
            suggest.visibility = View.GONE
            return
        }
        suggest.visibility = View.VISIBLE
        suggest.text = context.getString(R.string.event_clock_suggest, sectionText(suggestion))
    }

    /** 当前钟点按作息应当落在的节次区间；与所选节次不一致时用来给建议。 */
    private fun suggestedRange(): IntRange? {
        if (!exact) return null
        val start = currentStartMinutes()
        val end = currentEndMinutes()
        if (end <= start) return null
        return ScheduleTime.sectionSpanFor(
            ScheduleTime.formatTime(start),
            ScheduleTime.formatTime(end),
            slots
        )
    }

    private fun updateRepeatArea() {
        val once = repeatChoice.selectedIndex == REPEAT_ONCE
        findViewById<View>(R.id.eventRepeatEndArea).visibility = if (once) View.GONE else View.VISIBLE
        val weeks = weeks()
        findViewById<TextView>(R.id.eventRepeatSummary).text = if (weeks.isEmpty()) "" else
            context.getString(
                R.string.event_repeat_summary,
                ScheduleView.formatWeekSummary(weeks),
                weeks.size
            )
    }

    private fun updateAvoidHint() {
        val avoid = findViewById<TextView>(R.id.eventAvoid)
        val draft = normalized()
        if (draft == null) {
            avoid.visibility = View.GONE
            return
        }
        val conflicts = ScheduleOccurrences.conflictsForEvent(schedule, draft)
        val free = if (conflicts.isEmpty()) null
        else ScheduleOccurrences.nearestFreeStartSection(schedule, draft)
        if (free == null) {
            avoid.visibility = View.GONE
            return
        }
        avoid.visibility = View.VISIBLE
        avoid.text = context.getString(R.string.event_avoid_hint, conflicts.first().courseName, free)
        avoid.setOnClickListener {
            val span = draft.endSection - draft.startSection
            sectionPicker.setSelection(free..(free + span))
            if (exact) prefillClockFromSections()
            refresh()
        }
    }

    // ===== 草稿 =====

    private fun currentStartMinutes() = startHour.value * 60 + startMinute.value

    private fun currentEndMinutes() = endHour.value * 60 + endMinute.value

    private fun currentTimeText() = ScheduleTime.formatTime(currentStartMinutes()) +
        "–" + ScheduleTime.formatTime(currentEndMinutes())

    private fun weeks(): List<Int> {
        val end = endWeekStepper.value.coerceIn(anchorWeek, totalWeeks)
        return when (repeatChoice.selectedIndex) {
            REPEAT_ONCE -> listOf(anchorWeek)
            REPEAT_WEEKLY -> (anchorWeek..end).toList()
            REPEAT_ODD -> (anchorWeek..end).filter { it % 2 == 1 }
            REPEAT_EVEN -> (anchorWeek..end).filter { it % 2 == 0 }
            else -> listOf(anchorWeek)
        }
    }

    private fun draft(): PersonalEventDraft? {
        val range = sectionPicker.selection ?: return null
        return PersonalEventDraft(
            title = nameField.text.toString(),
            position = placeField.text.toString(),
            note = noteField.text.toString(),
            day = day,
            startSection = range.first,
            endSection = range.last,
            startTime = if (exact) ScheduleTime.formatTime(currentStartMinutes()) else null,
            endTime = if (exact) ScheduleTime.formatTime(currentEndMinutes()) else null,
            weeks = weeks(),
            color = color
        )
    }

    /** 校验通过的草稿；不合法时返回 null（实时反馈用，不弹提示）。 */
    private fun normalized(): PersonalEventDraft? = runCatching {
        PersonalEventValidator.normalize(draft() ?: return null, totalWeeks, slots)
    }.getOrNull()

    // ===== 保存 =====

    private fun submit() {
        val raw = draft()
        if (raw == null) {
            Ui.toast(context, context.getString(R.string.event_no_section))
            return
        }
        val normalized = try {
            PersonalEventValidator.normalize(raw, totalWeeks, slots)
        } catch (error: IllegalArgumentException) {
            Ui.toast(context, error.message ?: context.getString(R.string.common_load_failed))
            return
        }
        ScheduleOccurrences.overlappingEvent(events, normalized, editing?.id)?.let { existing ->
            Ui.toast(context, "这个时段已经有日程「" + existing.title + "」了")
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
        val lines = conflicts.joinToString(System.lineSeparator()) { conflict ->
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

    private fun sectionText(range: IntRange): String =
        if (range.first == range.last) range.first.toString()
        else range.first.toString() + "–" + range.last.toString()

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
        private const val REPEAT_ONCE = 0
        private const val REPEAT_WEEKLY = 1
        private const val REPEAT_ODD = 2
        private const val REPEAT_EVEN = 3

        /** 与 TimetableView 的默认日程色保持一致（冷灰蓝）。 */
        const val DEFAULT_EVENT_COLOR = 0xFF4A5A72.toInt()
    }
}
