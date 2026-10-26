package com.zhusijiao.app.ui.event

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ExtraCourseStore
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.databinding.ActivityEventEditorBinding
import com.zhusijiao.app.data.LocalScheduleStore
import com.zhusijiao.app.domain.ExtraCourses
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
import com.zhusijiao.app.ui.common.SlotSheet
import com.zhusijiao.app.ui.common.TimeRangeSheet
import com.zhusijiao.app.ui.common.WeeksSheet
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义日程编辑页。
 *
 * 与加课同一套结构（iOS 日历「新建事件」的路数）：唯一要填的是名称；时间、周次按点的那一格给好默认值，
 * 以「字段名 —— 当前值 ›」的行列出，点开是各自的底部面板（[SlotSheet] / [WeeksSheet]）。
 *
 * 「精确时间」是可选开关，打开后多一行钟点，点它弹滚轮。钟点**只改块上的文案**，块画在哪一格由节次决定；
 * 改了钟点就自动把节次挪到最接近的那一段，不再让用户确认。见 docs/DECISIONS.md D18。
 * 与课重叠时下面出现一行提示，有空位就整行可点、直接挪过去；保存时不再二次确认。
 */
class EventEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityEventEditorBinding

    private var schedule: Schedule? = null
    private var events: List<PersonalEvent> = emptyList()
    private var editing: PersonalEvent? = null
    private var slots: List<TimeSlot> = emptyList()

    private var day = 1
    private var sections = 1..1
    private var weeks: List<Int> = emptyList()
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
        // 安全区留在根布局上；键盘弹出时保存按钮仍在输入法上方
        Ui.liftAboveIme(binding.root)

        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID).orEmpty()
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val initialWeek = intent.getIntExtra(EXTRA_WEEK, 1)
        val initialDay = intent.getIntExtra(EXTRA_DAY, 1)
        val initialSection = intent.getIntExtra(EXTRA_SECTION, 1)
        binding.header.setTitle(getString(if (eventId == null) R.string.event_new_title else R.string.event_edit_title))
        binding.eventSave.isEnabled = false
        if (eventId == null) {
            // 新建时名称是唯一必填项，直接把键盘给出来
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            binding.eventName.requestFocus()
        }

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // 查日程和课的时间重叠时，自己加的课也算课
                val target = LocalScheduleStore.getScheduleOrNull(scheduleId)
                    ?.let { ExtraCourses.merge(it, ExtraCourseStore.list(scheduleId)) }
                target to PersonalEventStore.list(scheduleId)
            }
            val target = loaded.first
            if (target == null) {
                Ui.toastError(this@EventEditorActivity, getString(R.string.common_load_failed))
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
        val old = editing
        slots = ScheduleTime.slotsOf(current.timeSlots)
        totalWeeks = current.totalWeeks.coerceAtLeast(1)
        day = (old?.day ?: initialDay).coerceIn(1, 7)
        sections = old?.let { it.startSection..it.endSection }
            ?: initialSection.coerceIn(1, ScheduleTime.MAX_SECTION).let { it..it }
        anchorWeek = (old?.weeks?.minOrNull() ?: initialWeek).coerceIn(1, totalWeeks)
        // 新建默认从这一周起每周；编辑沿用原来的周次
        weeks = old?.weeks?.filter { it in 1..totalWeeks }?.takeIf { it.isNotEmpty() }
            ?: (anchorWeek..totalWeeks).toList()
        exact = old?.startTime != null
        color = old?.color

        binding.eventName.setText(old?.title.orEmpty())
        binding.eventPlace.setText(old?.position.orEmpty())
        binding.eventNote.setText(old?.note.orEmpty())
        binding.eventName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        configureTime()
        binding.eventWeeksRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
            WeeksSheet(this, totalWeeks, from = anchorWeek, initial = weeks, onlyWeek = anchorWeek) { picked ->
                weeks = picked
                refresh()
            }.show()
        }
        binding.eventColorRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
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
        binding.eventSave.setOnClickListener { submit() }
        ready = true
        refresh()
    }

    // ===== 时间 =====

    private fun configureTime() {
        startMinutes = ScheduleTime.minutesOf(editing?.startTime) ?: slotStart(sections) ?: (19 * 60)
        endMinutes = ScheduleTime.minutesOf(editing?.endTime) ?: slotEnd(sections) ?: (startMinutes + 90)
        binding.eventTimeRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
            SlotSheet(this, slots, day, sections) { pickedDay, range ->
                val moved = range != sections
                day = pickedDay
                sections = range
                // 节次是粗调、钟点是细调：换了节次就让钟点跟着新节次重来
                if (moved && exact) prefillClockFromSections()
                refresh()
            }.show()
        }
        binding.eventExactToggle.setChecked(exact, animate = false)
        binding.eventExactToggle.onCheckedChange = { checked ->
            exact = checked
            // 刚打开时按当前所选节次预填，多数人不用再动
            if (checked) prefillClockFromSections()
            refresh()
        }
        binding.eventExactRow.setOnClickListener { binding.eventExactToggle.toggle() }
        binding.eventClockRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
            TimeRangeSheet(this, startMinutes, endMinutes) { start, end ->
                startMinutes = start
                endMinutes = end
                // 钟点决定块该画在哪几节：自动挪过去，不让用户再确认一次
                sections = ScheduleTime.sectionSpanFor(
                    ScheduleTime.formatTime(start),
                    ScheduleTime.formatTime(end),
                    slots
                )
                refresh()
            }.show()
        }
    }

    private fun slotStart(range: IntRange) =
        ScheduleTime.minutesOf(slots.find { it.number == range.first }?.startTime)

    private fun slotEnd(range: IntRange) =
        ScheduleTime.minutesOf(slots.find { it.number == range.last }?.endTime)

    private fun prefillClockFromSections() {
        slotStart(sections)?.let { startMinutes = it }
        slotEnd(sections)?.let { endMinutes = it }
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
        binding.eventTimeValue.text = getString(
            R.string.slot_value,
            getString(R.string.slot_day, SlotSheet.DAY_NAMES[day - 1]),
            SlotSheet.sectionText(sections)
        )
        binding.eventClockArea.visibility = if (exact) View.VISIBLE else View.GONE
        binding.eventClockValue.text = currentTimeText()
        binding.eventWeeksValue.text = ScheduleView.formatWeekSummary(weeks)
        binding.eventSave.isEnabled = binding.eventName.text.isNotBlank()
        paintSwatch()
        updateAvoidHint()
    }

    private fun updateAvoidHint() {
        val current = schedule
        val draft = normalized()
        val conflicts = if (current == null || draft == null) emptyList()
        else ScheduleOccurrences.conflictsForEvent(current, draft)
        if (current == null || draft == null || conflicts.isEmpty()) {
            binding.eventAvoid.visibility = View.GONE
            return
        }
        binding.eventAvoid.visibility = View.VISIBLE
        val name = conflicts.first().courseName
        val free = ScheduleOccurrences.nearestFreeStartSection(current, draft)
        if (free == null) {
            binding.eventAvoid.text = getString(R.string.event_overlap_course, name)
            binding.eventAvoid.setOnClickListener(null)
            binding.eventAvoid.isClickable = false
            return
        }
        binding.eventAvoid.text = getString(R.string.event_avoid_hint, name, free)
        binding.eventAvoid.setOnClickListener {
            val span = draft.endSection - draft.startSection
            sections = free..(free + span)
            if (exact) prefillClockFromSections()
            refresh()
        }
    }

    // ===== 草稿 =====

    private fun currentTimeText() = ScheduleTime.formatTime(startMinutes) +
        " – " + ScheduleTime.formatTime(endMinutes)

    private fun draft() = PersonalEventDraft(
        title = binding.eventName.text.toString(),
        position = binding.eventPlace.text.toString(),
        note = binding.eventNote.text.toString(),
        day = day,
        startSection = sections.first,
        endSection = sections.last,
        startTime = if (exact) ScheduleTime.formatTime(startMinutes) else null,
        endTime = if (exact) ScheduleTime.formatTime(endMinutes) else null,
        weeks = weeks,
        color = color
    )

    /** 校验通过的草稿；不合法时返回 null（实时反馈用，不弹提示）。 */
    private fun normalized(): PersonalEventDraft? = runCatching {
        PersonalEventValidator.normalize(draft(), totalWeeks, slots)
    }.getOrNull()

    // ===== 保存 =====

    private fun submit() {
        val normalized = try {
            PersonalEventValidator.normalize(draft(), totalWeeks, slots)
        } catch (error: IllegalArgumentException) {
            Ui.toastError(this, error.message ?: getString(R.string.common_load_failed))
            return
        }
        ScheduleOccurrences.overlappingEvent(events, normalized, editing?.id)?.let { existing ->
            Ui.toastError(this, getString(R.string.event_overlap_exists, existing.title))
            return
        }
        // 和课重叠已经在页面上提示过了，这里不再弹确认：日程会和课并排显示
        persist(normalized)
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
                Ui.toastSuccess(this@EventEditorActivity, getString(R.string.event_saved))
                setResult(RESULT_OK)
                finish()
            } catch (error: Exception) {
                Ui.toastError(
                    this@EventEditorActivity,
                    error.message ?: getString(R.string.common_load_failed)
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SCHEDULE_ID = "scheduleId"
        private const val EXTRA_EVENT_ID = "eventId"
        private const val EXTRA_WEEK = "week"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_SECTION = "section"

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
