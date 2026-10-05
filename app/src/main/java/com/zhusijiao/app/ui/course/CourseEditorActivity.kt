package com.zhusijiao.app.ui.course

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ExtraCourseStore
import com.zhusijiao.app.data.LocalScheduleStore
import com.zhusijiao.app.databinding.ActivityCourseEditorBinding
import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.ExtraCourse
import com.zhusijiao.app.domain.ExtraCourses
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.ui.common.ColorPickerSheet
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自己加课的编辑页：新建一门，或修改一门自己加的课（课表自带的课不在这里改）。
 *
 * 和日程编辑页是同一个路数——名称、在节次网格上点出第几节、选周次——但课的默认值不一样：
 * 周次默认整个学期每周都上（日程默认从点的那一周开始），星期可以改，多一栏教师。
 * 颜色默认跟随自动配色，也可以手动选一个（只在本机生效，不会写进课表同步给同学）。
 * 与别的课时间重叠只提示不拦：重修课和原班课撞在一起是常态，课表会把两门并排显示。
 * 保存进 [ExtraCourseStore]，只在本机。见 docs/DECISIONS.md D23。
 */
class CourseEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityCourseEditorBinding

    /** 课表本身（不含自己加的课）与这份课表名下已有的自己加的课。 */
    private var schedule: Schedule? = null
    private var extras: List<ExtraCourse> = emptyList()
    private var editing: Course? = null

    /** 手动颜色；null 为跟随自动配色。 */
    private var color: String? = null
    private var slots: List<TimeSlot> = emptyList()
    private var totalWeeks = 20
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }
        Ui.liftAboveIme(binding.root)

        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID).orEmpty()
        val courseId = intent.getStringExtra(EXTRA_COURSE_ID)
        val initialDay = intent.getIntExtra(EXTRA_DAY, 1)
        val initialSection = intent.getIntExtra(EXTRA_SECTION, 1)

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LocalScheduleStore.getScheduleOrNull(scheduleId) to ExtraCourseStore.list(scheduleId)
            }
            val target = loaded.first
            val found = courseId?.let { id -> loaded.second.find { it.course.id == id } }
            if (target == null || (courseId != null && found == null)) {
                Ui.toastError(this@CourseEditorActivity, getString(R.string.common_load_failed))
                finish()
                return@launch
            }
            schedule = target
            extras = loaded.second
            editing = found?.course
            color = found?.color
            bind(initialDay, initialSection)
        }
    }

    private fun bind(initialDay: Int, initialSection: Int) {
        val current = schedule ?: return
        val old = editing
        slots = ScheduleTime.slotsOf(current.timeSlots)
        totalWeeks = current.totalWeeks.coerceAtLeast(1)

        binding.header.setTitle(getString(if (old == null) R.string.course_new_title else R.string.course_edit_title))
        binding.courseName.setText(old?.name.orEmpty())
        binding.coursePosition.setText(old?.position.orEmpty())
        binding.courseTeacher.setText(old?.teacher.orEmpty())
        binding.courseName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        binding.courseDay.configure(
            DAY_NAMES,
            ((old?.day ?: initialDay).coerceIn(1, 7)) - 1,
            getString(R.string.course_day_prefix)
        ) { refresh() }

        val initialRange = old?.let { it.startSection..it.endSection }
            ?: initialSection.coerceIn(1, ScheduleTime.MAX_SECTION).let { it..it }
        binding.courseSections.configure(slots, initialRange) { refresh() }

        // 课默认整个学期都上；改的是已有的课就沿用它的起止周
        val weeks = old?.weeks?.filter { it in 1..totalWeeks }.orEmpty()
        val startWeek = weeks.minOrNull() ?: 1
        val endWeek = weeks.maxOrNull() ?: totalWeeks
        val weekText = { week: Int -> getString(R.string.course_week_value, week) }
        binding.courseStartWeek.contentDescription = getString(R.string.course_start_week_label)
        binding.courseEndWeek.contentDescription = getString(R.string.course_end_week_label)
        binding.courseEndWeek.configure(startWeek, totalWeeks, endWeek, weekText) { refresh() }
        binding.courseStartWeek.configure(1, totalWeeks, startWeek, weekText) { start ->
            // 结束周不能早于开始周：开始往后调时把结束一起带上
            binding.courseEndWeek.configure(start, totalWeeks, maxOf(binding.courseEndWeek.value, start), weekText) { refresh() }
            refresh()
        }
        binding.courseRepeat.configure(
            listOf(
                getString(R.string.event_repeat_weekly),
                getString(R.string.event_repeat_odd),
                getString(R.string.event_repeat_even)
            ),
            when (ScheduleView.alternatingWeekBadge(weeks)) {
                "单周" -> REPEAT_ODD
                "双周" -> REPEAT_EVEN
                else -> REPEAT_WEEKLY
            },
            getString(R.string.event_repeat_label)
        ) { refresh() }

        binding.courseColorRow.setOnClickListener {
            ColorPickerSheet(
                this,
                courseName = binding.courseName.text.toString().trim().ifEmpty { getString(R.string.course_new_title) },
                autoColor = autoColor(),
                currentManual = color,
                onPick = { hex -> color = hex; paintColor() },
                onReset = { color = null; paintColor() }
            ).show()
        }
        binding.courseSave.setOnClickListener { submit() }
        ready = true
        refresh()
    }

    // ===== 实时反馈 =====

    private fun refresh() {
        if (!ready) return
        val range = binding.courseSections.selection
        val day = binding.courseDay.selectedIndex + 1
        binding.courseHeadline.text = if (range == null) {
            getString(R.string.course_headline_pending)
        } else {
            val clock = ScheduleTime.rangeText(slots, range.first, range.last)
            val sections = getString(R.string.course_sections_value, sectionText(range))
            getString(
                R.string.course_headline,
                "周" + DAY_NAMES[day - 1],
                if (clock.isNullOrEmpty()) sections else "$sections · $clock"
            )
        }
        binding.courseSectionHint.text = when {
            range == null -> getString(R.string.event_section_hint_start)
            binding.courseSections.awaitingEnd -> getString(R.string.course_section_hint_end)
            else -> getString(
                R.string.event_section_hint_done,
                sectionText(range),
                ScheduleTime.rangeText(slots, range.first, range.last).orEmpty()
            )
        }
        val weeks = weeks()
        binding.courseWeeksSummary.text = if (weeks.isEmpty()) getString(R.string.course_weeks_empty) else getString(
            R.string.event_repeat_summary,
            ScheduleView.formatWeekSummary(weeks),
            weeks.size
        )
        paintColor()
        updateConflictHint()
    }

    /** 不手动选时这门课会被自动配成什么颜色（跟课程名和课表里已有的课有关，名字改了会跟着变）。 */
    private fun autoColor(): Int {
        val name = binding.courseName.text.toString().trim()
        if (name.isEmpty()) return ScheduleView.COURSE_PALETTES[0].background
        val others = others()
        val probe = Course(name = name, teacher = "", position = "", day = 1, startSection = 1, endSection = 1, weeks = listOf(1))
        return ScheduleView.buildCoursePaletteMap(
            others.courses + others.adjustments.map { it.courseSnapshot } + probe
        )[name]?.background ?: ScheduleView.COURSE_PALETTES[0].background
    }

    private fun paintColor() {
        val manual = ScheduleView.manualPalette(color)?.background
        binding.courseColorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(manual ?: autoColor())
        }
        binding.courseColorText.setText(if (manual == null) R.string.course_color_auto else R.string.course_color_manual)
    }

    private fun updateConflictHint() {
        val draft = normalized()
        val conflicts = if (draft == null) emptyList() else ExtraCourses.conflicts(others(), draft)
        if (conflicts.isEmpty()) {
            binding.courseConflict.visibility = View.GONE
            return
        }
        binding.courseConflict.visibility = View.VISIBLE
        binding.courseConflict.text = getString(
            R.string.course_conflict_hint,
            conflicts.joinToString("、") { conflict ->
                getString(
                    R.string.course_conflict_item,
                    conflict.courseName,
                    ScheduleView.consecutiveRanges(conflict.weeks).joinToString("、")
                )
            }
        )
    }

    /** 课表加上其他自己加的课（不含正在编辑的这一门），用来查时间重叠。 */
    private fun others(): Schedule {
        val current = schedule!!
        return ExtraCourses.merge(current, extras.filterNot { it.course.id == editing?.id })
    }

    // ===== 草稿 =====

    private fun weeks(): List<Int> {
        val start = binding.courseStartWeek.value
        val end = binding.courseEndWeek.value.coerceAtLeast(start)
        return when (binding.courseRepeat.selectedIndex) {
            REPEAT_ODD -> (start..end).filter { it % 2 == 1 }
            REPEAT_EVEN -> (start..end).filter { it % 2 == 0 }
            else -> (start..end).toList()
        }
    }

    private fun draft(): Course? {
        val range = binding.courseSections.selection ?: return null
        return Course(
            name = binding.courseName.text.toString(),
            teacher = binding.courseTeacher.text.toString(),
            position = binding.coursePosition.text.toString(),
            day = binding.courseDay.selectedIndex + 1,
            startSection = range.first,
            endSection = range.last,
            weeks = weeks(),
            id = editing?.id ?: ExtraCourses.ID_PREFIX
        )
    }

    /** 校验通过的草稿；不合法时返回 null（实时反馈用，不弹提示）。 */
    private fun normalized(): Course? = runCatching {
        ExtraCourses.normalize(draft() ?: return null, totalWeeks)
    }.getOrNull()

    // ===== 保存 =====

    private fun submit() {
        val current = schedule ?: return
        val raw = draft()
        if (raw == null) {
            Ui.toastError(this, getString(R.string.course_no_section))
            return
        }
        val course = try {
            ExtraCourses.normalize(raw, totalWeeks)
        } catch (error: IllegalArgumentException) {
            Ui.toastError(this, error.message ?: getString(R.string.common_load_failed))
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ExtraCourseStore.save(current.id, course, color, editing?.id, current.totalWeeks.coerceAtLeast(1))
                }
                Ui.toastSuccess(this@CourseEditorActivity, getString(R.string.course_saved))
                setResult(RESULT_OK)
                finish()
            } catch (error: Exception) {
                Ui.toastError(this@CourseEditorActivity, error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun sectionText(range: IntRange): String =
        if (range.first == range.last) range.first.toString() else "${range.first}–${range.last}"

    companion object {
        private const val EXTRA_SCHEDULE_ID = "scheduleId"
        private const val EXTRA_COURSE_ID = "courseId"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_SECTION = "section"

        private const val REPEAT_WEEKLY = 0
        private const val REPEAT_ODD = 1
        private const val REPEAT_EVEN = 2

        private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

        /** [courseId] 为 null 表示新建，[day] / [section] 是从课表上点的那一格。 */
        fun intent(context: Context, scheduleId: String, courseId: String?, day: Int, section: Int): Intent =
            Intent(context, CourseEditorActivity::class.java)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_COURSE_ID, courseId)
                .putExtra(EXTRA_DAY, day)
                .putExtra(EXTRA_SECTION, section)
    }
}
