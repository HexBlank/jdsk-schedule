package com.zhusijiao.app.ui.event

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.databinding.ActivityEventEditorBinding
import com.zhusijiao.app.data.LocalScheduleStore
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.PersonalEventDraft
import com.zhusijiao.app.domain.PersonalEventValidator
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.ui.common.ColorPickerSheet
import com.zhusijiao.app.ui.common.TimeRangeSheet
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义日程编辑页。
 *
 * 独立成页而不是底部面板：字段多（名称、12 节网格、精确时间、重复、地点/备注/颜色），
 * 塞进抽屉会把屏幕撑满还要反复滚动，保存按钮也钉不住。
 *
 * 主路径只有三件事：起个名字、在节次网格上点出占用哪几节、选重复方式。
 * 「精确到分钟」是可选开关，打开后点一行弹滚轮选起止时间；它**只改显示文案**，
 * 块画在哪一格始终由用户点的节次决定。时间被改到明显属于别的节次时给一条可点的
 * 建议把格子挪过去，避免「写早上 8 点、块却画在晚上」。见 docs/DECISIONS.md D18。
 */
class EventEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityEventEditorBinding

    private var schedule: Schedule? = null
    private var events: List<PersonalEvent> = emptyList()
    private var editing: PersonalEvent? = null
    private var slots: List<TimeSlot> = emptyList()

    private var day = 1
    private var anchorWeek = 1
    private var totalWeeks = 20
    private var exact = false
    private var color: String? = null
    private var startMinutes = 19 * 60
    private var endMinutes = 20 * 60 + 30
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }
        // 与其它页面一致：安全区留在根布局上。
        // 不能用 padBottomNav(底栏)——它是覆盖写 paddingBottom，会把底栏自己的留白顶掉，
        // 保存按钮就贴到屏幕最底边。liftAboveIme 额外保证键盘弹出时按钮仍在输入法上方。
        Ui.liftAboveIme(binding.root)

        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID).orEmpty()
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val initialWeek = intent.getIntExtra(EXTRA_WEEK, 1)
        val initialDay = intent.getIntExtra(EXTRA_DAY, 1)
        val initialSection = intent.getIntExtra(EXTRA_SECTION, 1)

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val target = LocalScheduleStore.getScheduleOrNull(scheduleId)
                target to PersonalEventStore.list(scheduleId)
            }
            val target = loaded.first
            if (target == null) {
                Ui.toast(this@EventEditorActivity, getString(R.string.common_load_failed))
                finish()
                return@launch
            }
            schedule = target
            events = loaded.second
            editing = eventId?.let { id -> events.find { it.id == id } }
            bind(initialWeek, initialDay, initialSection)
        }
    }

    private fun bind(initialWeek: Int, initialDay: Int, initialSection: Int) {
        val current = schedule ?: return
        slots = ScheduleTime.slotsOf(current.timeSlots)
        totalWeeks = current.totalWeeks.coerceAtLeast(1)
        day = (editing?.day ?: initialDay).coerceIn(1, 7)
        anchorWeek = (editing?.weeks?.minOrNull() ?: initialWeek).coerceIn(1, totalWeeks)
        exact = editing?.startTime != null
        color = editing?.color

        binding.header.setTitle(
            getString(if (editing == null) R.string.event_new_title else R.string.event_edit_title)
        )
        binding.eventName.setText(editing?.title.orEmpty())
        binding.eventPlace.setText(editing?.position.orEmpty())
        binding.eventNote.setText(editing?.note.orEmpty())

        configureSections(initialSection)
        configureClock()
        configureRepeat()
        configureMore()
        ready = true
        refresh()

        binding.eventSave.setOnClickListener { submit() }
    }

    // ===== 各段输入 =====

    private fun configureSections(initialSection: Int) {
        val initial = editing?.let { it.startSection..it.endSection }
            ?: initialSection.coerceIn(1, ScheduleTime.MAX_SECTION).let { it..it }
        binding.eventSections.configure(slots, initial) { refresh() }
    }

    private fun configureClock() {
        val range = binding.eventSections.selection
        startMinutes = ScheduleTime.minutesOf(editing?.startTime)
            ?: ScheduleTime.minutesOf(slots.find { it.number == range?.first }?.startTime)
            ?: (19 * 60)
        endMinutes = ScheduleTime.minutesOf(editing?.endTime)
            ?: ScheduleTime.minutesOf(slots.find { it.number == range?.last }?.endTime)
            ?: (startMinutes + 90)
        binding.eventExactToggle.setChecked(exact, animate = false)
        binding.eventExactToggle.onCheckedChange = { checked ->
            exact = checked
            // 刚打开时按当前所选节次预填，多数人不用再动
            if (checked) prefillClockFromSections()
            refresh()
        }
        binding.eventExactRow.setOnClickListener { binding.eventExactToggle.toggle() }
        binding.eventClockRow.setOnClickListener { openTimeSheet() }
        binding.eventClockSuggest.setOnClickListener {
            suggestedRange()?.let { binding.eventSections.setSelection(it); refresh() }
        }
    }

    private fun openTimeSheet() {
        TimeRangeSheet(this, startMinutes, endMinutes) { start, end ->
            startMinutes = start
            endMinutes = end
            refresh()
        }.show()
    }

    private fun prefillClockFromSections() {
        val range = binding.eventSections.selection ?: return
        ScheduleTime.minutesOf(slots.find { it.number == range.first }?.startTime)
            ?.let { startMinutes = it }
        ScheduleTime.minutesOf(slots.find { it.number == range.last }?.endTime)
            ?.let { endMinutes = it }
    }

    private fun configureRepeat() {
        binding.eventRepeat.configure(
            items = listOf(
                getString(R.string.event_repeat_once),
                getString(R.string.event_repeat_weekly),
                getString(R.string.event_repeat_odd),
                getString(R.string.event_repeat_even)
            ),
            initialIndex = repeatIndexOf(editing),
            contentDescriptionPrefix = getString(R.string.event_repeat_label)
        ) { refresh() }
        val end = (editing?.weeks?.maxOrNull() ?: totalWeeks).coerceIn(anchorWeek, totalWeeks)
        binding.eventEndWeek.contentDescription = getString(R.string.event_repeat_end_label)
        binding.eventEndWeek.configure(anchorWeek, totalWeeks, end, {
            getString(R.string.event_repeat_end_value, it)
        }) { refresh() }
    }

    private fun configureMore() {
        // 编辑已有日程时这些字段有值就直接展开，免得用户以为内容丢了
        val hasContent = !editing?.position.isNullOrBlank() ||
            !editing?.note.isNullOrBlank() || editing?.color != null
        binding.eventMoreArea.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.eventMoreToggle.visibility = if (hasContent) View.GONE else View.VISIBLE
        binding.eventMoreToggle.setOnClickListener {
            binding.eventMoreArea.visibility = View.VISIBLE
            binding.eventMoreToggle.visibility = View.GONE
        }
        binding.eventColorRow.setOnClickListener {
            ColorPickerSheet(
                this,
                courseName = binding.eventName.text.toString().trim()
                    .ifEmpty { getString(R.string.event_badge) },
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
        binding.eventColorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(value)
        }
    }

    // ===== 实时反馈 =====

    private fun refresh() {
        if (!ready) return
        val range = binding.eventSections.selection
        updateHeadline(range)
        updateSectionHint(range)
        updateClockArea(range)
        updateRepeatArea()
        updateAvoidHint()
    }

    private fun updateHeadline(range: IntRange?) {
        if (range == null) {
            binding.eventHeadline.setText(R.string.event_headline_pending)
            return
        }
        binding.eventHeadline.text =
            getString(R.string.event_headline, "周" + dayName(day), timeSummary(range))
    }

    /** 顶部摘要里的时间部分：开了精确时间就用它，否则用节次作息。 */
    private fun timeSummary(range: IntRange): String {
        val sections = sectionText(range)
        val clock = if (exact) currentTimeText()
        else ScheduleTime.rangeText(slots, range.first, range.last)
        return if (clock.isNullOrEmpty()) "第 $sections 节" else "第 $sections 节 · $clock"
    }

    private fun updateSectionHint(range: IntRange?) {
        binding.eventSectionHint.text = when {
            range == null -> getString(R.string.event_section_hint_start)
            binding.eventSections.awaitingEnd -> getString(R.string.event_section_hint_end)
            else -> getString(
                R.string.event_section_hint_done,
                sectionText(range),
                ScheduleTime.rangeText(slots, range.first, range.last).orEmpty()
            )
        }
    }

    private fun updateClockArea(range: IntRange?) {
        binding.eventClockArea.visibility = if (exact) View.VISIBLE else View.GONE
        if (!exact || range == null) return
        binding.eventClockValue.text = currentTimeText()
        binding.eventClockNote.text = getString(R.string.event_clock_note, sectionText(range))
        val suggestion = suggestedRange()
        if (suggestion == null || suggestion == range) {
            binding.eventClockSuggest.visibility = View.GONE
            return
        }
        binding.eventClockSuggest.visibility = View.VISIBLE
        binding.eventClockSuggest.text =
            getString(R.string.event_clock_suggest, sectionText(suggestion))
    }

    /** 当前钟点按作息应当落在的节次区间；与所选节次不一致时用来给建议。 */
    private fun suggestedRange(): IntRange? {
        if (!exact || endMinutes <= startMinutes) return null
        return ScheduleTime.sectionSpanFor(
            ScheduleTime.formatTime(startMinutes),
            ScheduleTime.formatTime(endMinutes),
            slots
        )
    }

    private fun updateRepeatArea() {
        val once = binding.eventRepeat.selectedIndex == REPEAT_ONCE
        binding.eventRepeatEndArea.visibility = if (once) View.GONE else View.VISIBLE
        val weeks = weeks()
        binding.eventRepeatSummary.text = if (weeks.isEmpty()) "" else getString(
            R.string.event_repeat_summary,
            ScheduleView.formatWeekSummary(weeks),
            weeks.size
        )
    }

    private fun updateAvoidHint() {
        val current = schedule
        val draft = normalized()
        if (current == null || draft == null) {
            binding.eventAvoid.visibility = View.GONE
            return
        }
        val conflicts = ScheduleOccurrences.conflictsForEvent(current, draft)
        val free = if (conflicts.isEmpty()) null
        else ScheduleOccurrences.nearestFreeStartSection(current, draft)
        if (free == null) {
            binding.eventAvoid.visibility = View.GONE
            return
        }
        binding.eventAvoid.visibility = View.VISIBLE
        binding.eventAvoid.text =
            getString(R.string.event_avoid_hint, conflicts.first().courseName, free)
        binding.eventAvoid.setOnClickListener {
            val span = draft.endSection - draft.startSection
            binding.eventSections.setSelection(free..(free + span))
            if (exact) prefillClockFromSections()
            refresh()
        }
    }

    // ===== 草稿 =====

    private fun currentTimeText() = ScheduleTime.formatTime(startMinutes) +
        "–" + ScheduleTime.formatTime(endMinutes)

    private fun weeks(): List<Int> {
        val end = binding.eventEndWeek.value.coerceIn(anchorWeek, totalWeeks)
        return when (binding.eventRepeat.selectedIndex) {
            REPEAT_ONCE -> listOf(anchorWeek)
            REPEAT_WEEKLY -> (anchorWeek..end).toList()
            REPEAT_ODD -> (anchorWeek..end).filter { it % 2 == 1 }
            REPEAT_EVEN -> (anchorWeek..end).filter { it % 2 == 0 }
            else -> listOf(anchorWeek)
        }
    }

    private fun draft(): PersonalEventDraft? {
        val range = binding.eventSections.selection ?: return null
        return PersonalEventDraft(
            title = binding.eventName.text.toString(),
            position = binding.eventPlace.text.toString(),
            note = binding.eventNote.text.toString(),
            day = day,
            startSection = range.first,
            endSection = range.last,
            startTime = if (exact) ScheduleTime.formatTime(startMinutes) else null,
            endTime = if (exact) ScheduleTime.formatTime(endMinutes) else null,
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
        val current = schedule ?: return
        val raw = draft()
        if (raw == null) {
            Ui.toast(this, getString(R.string.event_no_section))
            return
        }
        val normalized = try {
            PersonalEventValidator.normalize(raw, totalWeeks, slots)
        } catch (error: IllegalArgumentException) {
            Ui.toast(this, error.message ?: getString(R.string.common_load_failed))
            return
        }
        ScheduleOccurrences.overlappingEvent(events, normalized, editing?.id)?.let { existing ->
            Ui.toast(this, getString(R.string.event_overlap_exists, existing.title))
            return
        }
        val conflicts = ScheduleOccurrences.conflictsForEvent(current, normalized)
        if (conflicts.isEmpty()) {
            persist(normalized)
            return
        }
        val lines = conflicts.joinToString(System.lineSeparator()) { conflict ->
            getString(
                R.string.event_conflict_line,
                conflict.courseName,
                ScheduleView.consecutiveRanges(conflict.weeks).joinToString("、")
            )
        }
        Ui.confirm(
            this,
            getString(R.string.event_conflict_title),
            getString(R.string.event_conflict_content, lines),
            confirmText = getString(R.string.event_conflict_continue)
        ) { persist(normalized) }
    }

    private fun persist(draft: PersonalEventDraft) {
        val current = schedule ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PersonalEventStore.save(
                        scheduleId = current.id,
                        draft = draft,
                        eventId = editing?.id,
                        totalWeeks = current.totalWeeks,
                        slots = slots
                    )
                }
                Ui.toast(this@EventEditorActivity, getString(R.string.event_saved))
                setResult(RESULT_OK)
                finish()
            } catch (error: Exception) {
                Ui.toast(
                    this@EventEditorActivity,
                    error.message ?: getString(R.string.common_load_failed)
                )
            }
        }
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
        private const val EXTRA_SCHEDULE_ID = "scheduleId"
        private const val EXTRA_EVENT_ID = "eventId"
        private const val EXTRA_WEEK = "week"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_SECTION = "section"

        private const val REPEAT_ONCE = 0
        private const val REPEAT_WEEKLY = 1
        private const val REPEAT_ODD = 2
        private const val REPEAT_EVEN = 3

        /** 与 TimetableView 的默认日程色保持一致（冷灰蓝）。 */
        const val DEFAULT_EVENT_COLOR = 0xFF4A5A72.toInt()

        fun intent(
            context: Context,
            scheduleId: String,
            eventId: String?,
            week: Int,
            day: Int,
            section: Int
        ): Intent = Intent(context, EventEditorActivity::class.java)
            .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_WEEK, week)
            .putExtra(EXTRA_DAY, day)
            .putExtra(EXTRA_SECTION, section)
    }
}
