package com.zhusijiao.app.ui.course

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
import com.zhusijiao.app.ui.common.SlotSheet
import com.zhusijiao.app.ui.common.WeeksSheet
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自己加课的编辑页：新建一门，或修改一门自己加的课（课表自带的课不在这里改）。
 *
 * 照 iOS 日历「新建事件」的路数：唯一要填的是课程名；时间和周次已经按点的那一格给好默认值，
 * 以「时间 —— 周三 · 第 3–4 节 ›」这样的行列出来，想改才点，点开是各自的底部面板。
 * 默认值：从奇数节点进来按两节连上（第 3 节 → 3–4 节），周次整个学期每周。
 * 颜色默认跟随自动配色，也可以手动选一个（只在本机生效，不会写进课表同步给同学）。
 * 与别的课时间重叠只提示一行、不拦：重修课和原班课撞在一起是常态，课表会把两门并排显示。
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
    private var day = 1
    private var sections = 1..1
    private var weeks: List<Int> = emptyList()
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
        binding.header.setTitle(getString(if (courseId == null) R.string.course_new_title else R.string.course_edit_title))
        binding.courseSave.isEnabled = false
        if (courseId == null) {
            // 新建时名称是唯一必填项，直接把键盘给出来
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            binding.courseName.requestFocus()
        }

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

        day = (old?.day ?: initialDay).coerceIn(1, 7)
        sections = old?.let { it.startSection..it.endSection } ?: defaultSections(initialSection)
        // 课默认整个学期每周都上；改的是已有的课就沿用它的周次
        weeks = old?.weeks?.filter { it in 1..totalWeeks }?.takeIf { it.isNotEmpty() } ?: (1..totalWeeks).toList()

        binding.courseName.setText(old?.name.orEmpty())
        binding.coursePosition.setText(old?.position.orEmpty())
        binding.courseTeacher.setText(old?.teacher.orEmpty())
        binding.courseName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        binding.courseTimeRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
            SlotSheet(this, slots, day, sections) { pickedDay, range ->
                day = pickedDay
                sections = range
                refresh()
            }.show()
        }
        binding.courseWeeksRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
            WeeksSheet(this, totalWeeks, from = 1, initial = weeks) { picked ->
                weeks = picked
                refresh()
            }.show()
        }
        binding.courseColorRow.setOnClickListener {
            Ui.hideKeyboard(binding.root)
            ColorPickerSheet(
                this,
                courseName = binding.courseName.text.toString().trim().ifEmpty { getString(R.string.add_entry_course) },
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

    /** 点第 n 节进来：奇数节默认和下一节连上（大多数课是两节连堂），偶数节或最后一节只占这一节。 */
    private fun defaultSections(section: Int): IntRange {
        val start = section.coerceIn(1, slots.size.coerceAtLeast(1))
        return if (start % 2 == 1 && start + 1 <= slots.size) start..start + 1 else start..start
    }

    // ===== 实时反馈 =====

    private fun refresh() {
        if (!ready) return
        binding.courseTimeValue.text = getString(
            R.string.slot_value,
            getString(R.string.slot_day, SlotSheet.DAY_NAMES[day - 1]),
            SlotSheet.sectionText(sections)
        )
        binding.courseWeeksValue.text = ScheduleView.formatWeekSummary(weeks)
        binding.courseSave.isEnabled = binding.courseName.text.isNotBlank()
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

    private fun draft() = Course(
        name = binding.courseName.text.toString(),
        teacher = binding.courseTeacher.text.toString(),
        position = binding.coursePosition.text.toString(),
        day = day,
        startSection = sections.first,
        endSection = sections.last,
        weeks = weeks,
        id = editing?.id ?: ExtraCourses.ID_PREFIX
    )

    /** 校验通过的草稿；不合法时返回 null（实时反馈用，不弹提示）。 */
    private fun normalized(): Course? = runCatching { ExtraCourses.normalize(draft(), totalWeeks) }.getOrNull()

    // ===== 保存 =====

    private fun submit() {
        val current = schedule ?: return
        val course = try {
            ExtraCourses.normalize(draft(), totalWeeks)
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

    companion object {
        private const val EXTRA_SCHEDULE_ID = "scheduleId"
        private const val EXTRA_COURSE_ID = "courseId"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_SECTION = "section"

        /** [courseId] 为 null 表示新建，[day] / [section] 是从课表上点的那一格。 */
        fun intent(context: Context, scheduleId: String, courseId: String?, day: Int, section: Int): Intent =
            Intent(context, CourseEditorActivity::class.java)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_COURSE_ID, courseId)
                .putExtra(EXTRA_DAY, day)
                .putExtra(EXTRA_SECTION, section)
    }
}
