package com.zhusijiao.app.ui.schedule

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.databinding.FragmentScheduleBinding
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.PersonalEventDraft
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.TimetableAppearance
import com.zhusijiao.app.ui.common.AppearanceSheet
import com.zhusijiao.app.ui.common.ColorPickerSheet
import com.zhusijiao.app.ui.common.CourseDetailSheet
import com.zhusijiao.app.ui.common.EventDetailSheet
import com.zhusijiao.app.ui.common.EventEditorSheet
import com.zhusijiao.app.ui.common.HolidaySheet
import com.zhusijiao.app.ui.common.Refreshable
import com.zhusijiao.app.ui.common.TimetableView
import com.zhusijiao.app.ui.common.RescheduleSheet
import com.zhusijiao.app.ui.common.WeekPickerSheet
import com.zhusijiao.app.ui.importer.ImportActivity
import com.zhusijiao.app.ui.join.JoinActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui
import java.util.Calendar
import kotlin.math.roundToInt

/** 周课表页：应用主界面。 */
class ScheduleFragment : Fragment(), Refreshable {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    private var schedule: Schedule? = null

    /** 本机私有日程，不随课表同步；与课表分开加载、分开保存。 */
    private var events: List<PersonalEvent> = emptyList()
    private var currentWeekNumber = 1
    private var currentWeek = 1
    private var restoredWeek: Int? = null
    private var syncRunning = false

    /** 打开中的调休面板：保存后就地刷新，不必关掉再进。 */
    private var holidaySheet: HolidaySheet? = null

    /** 从设置页跳过来时先记下，等课表视图就绪再弹外观面板。 */
    private var pendingAppearanceSheet = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoredWeek = savedInstanceState?.getInt(STATE_WEEK)?.takeIf { it > 0 }
        binding.header.setTitle(getString(R.string.index_default_title))
        binding.timetable.onCourseClick = { click -> showCourse(click) }
        binding.timetable.onCourseLongClick = { click -> showCourseColorSheet(click) }
        binding.timetable.onDayClick = { click -> showHolidaySheet(click.week, click.day) }
        binding.timetable.onEventClick = { click -> showEvent(click) }
        binding.timetable.onEmptySlotClick = { week, day, section ->
            showEventEditor(null, week, day, section)
        }
        binding.timetable.onWeekChanged = { week, isFirst, isLast -> updateWeekBar(week, isFirst, isLast) }
        binding.prevWeek.setOnClickListener { binding.timetable.previousWeek() }
        binding.nextWeek.setOnClickListener { binding.timetable.nextWeek() }
        binding.weekCenter.setOnClickListener { showWeekPicker() }
        binding.emptyImport.setOnClickListener {
            startActivity(Intent(requireContext(), ImportActivity::class.java))
        }
        binding.emptyJoin.setOnClickListener {
            if (ApiClient.isLocalMode) Ui.alert(
                requireContext(),
                getString(R.string.import_helper_unavailable_title),
                getString(R.string.local_sharing_unavailable)
            ) else startActivity(Intent(requireContext(), JoinActivity::class.java))
        }
        if (ApiClient.isLocalMode) binding.emptyJoin.text = getString(R.string.library_join_offline)
        binding.errorRetry.setOnClickListener { load(silent = false) }
        binding.timetable.setAppearance(Prefs.timetableAppearance)
        // 「铺满一屏」要按可视高度平分行高；用始终可见的内容区测量，首帧就是最终高度，
        // 容器高度变化（分屏、系统字体缩放）时也会再次告知
        binding.contentArea.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            binding.timetable.setViewportHeight(bottom - top)
        }
        if (pendingAppearanceSheet) {
            pendingAppearanceSheet = false
            binding.timetable.post { showAppearanceSheet() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        holidaySheet?.dismiss()
        holidaySheet = null
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_WEEK, currentWeek)
        super.onSaveInstanceState(outState)
    }

    override fun refresh() {
        if (_binding != null) load(silent = true)
    }

    private fun load(silent: Boolean, syncRemote: Boolean = true) {
        if (!silent) setState(STATE_LOADING)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val schedules = ApiClient.listSchedules()
                if (schedules.isEmpty()) {
                    schedule = null
                    events = emptyList()
                    binding.timetable.setSchedule(null)
                    binding.header.setTitle(getString(R.string.index_default_title))
                    binding.header.clearActions()
                    setState(STATE_EMPTY)
                    Prefs.removeActiveSchedule()
                    syncRemoteThenReload(syncRemote)
                    return@launch
                }
                val activeId = Prefs.activeScheduleId
                val summary = schedules.find { it.id == activeId } ?: schedules[0]
                val loadedSchedule = ApiClient.getSchedule(summary.id)
                events = withContext(Dispatchers.IO) { PersonalEventStore.list(loadedSchedule.id) }
                // 同一份课表刷新（切后台回前台、同步完成等）时保持用户正在浏览的周次，
                // 不能强制跳回本周；只有切换到另一份课表时才回到本周。
                val sameSchedule = schedule?.id == loadedSchedule.id
                schedule = loadedSchedule
                Prefs.activeScheduleId = loadedSchedule.id
                currentWeekNumber = DateUtils.currentWeek(loadedSchedule.semesterStart, loadedSchedule.totalWeeks)
                binding.header.setTitle(loadedSchedule.name)
                updateHeaderActions()
                binding.timetable.setSchedule(
                    loadedSchedule,
                    events = events,
                    jumpToCurrent = !sameSchedule,
                    weekendMode = Prefs.weekendDisplayMode
                )
                if (sameSchedule) {
                    // 周数可能在同步后变化，收敛到合法范围；与当前周相同则不会触发跳转
                    binding.timetable.goToWeek(currentWeek.coerceIn(1, loadedSchedule.totalWeeks))
                }
                restoredWeek?.let {
                    binding.timetable.goToWeek(it.coerceIn(1, loadedSchedule.totalWeeks))
                    restoredWeek = null
                }
                setState(STATE_CONTENT)
                syncRemoteThenReload(syncRemote)
            } catch (e: Exception) {
                binding.errorMessage.text = e.message ?: getString(R.string.index_load_failed_title)
                setState(STATE_ERROR)
            }
        }
    }

    private fun setState(state: Int) {
        binding.weekBar.visibility = if (state == STATE_CONTENT) View.VISIBLE else View.GONE
        binding.scroll.visibility = if (state == STATE_CONTENT) View.VISIBLE else View.GONE
        binding.loading.visibility = if (state == STATE_LOADING) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (state == STATE_EMPTY) View.VISIBLE else View.GONE
        binding.errorState.visibility = if (state == STATE_ERROR) View.VISIBLE else View.GONE
    }

    private fun updateWeekBar(week: Int, isFirst: Boolean, isLast: Boolean) {
        currentWeek = week
        binding.weekTitle.text = getString(R.string.index_week_title, week)
        binding.weekCaption.text = captionForWeek(week)
        tint(binding.prevIcon, if (isFirst) R.color.tt_chevron_off else R.color.tt_chevron)
        tint(binding.nextIcon, if (isLast) R.color.tt_chevron_off else R.color.tt_chevron)
    }

    private fun tint(view: ImageView, colorRes: Int) {
        view.setColorFilter(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun captionForWeek(week: Int): String {
        val s = schedule ?: return ""
        val dates = DateUtils.datesForWeek(s.semesterStart, week)
        if (dates.isEmpty()) return if (week == currentWeekNumber) getString(R.string.index_current_week) else ""
        val first = dates.first()
        val last = dates.last()
        val range = "${first.month}.${first.day}–${last.month}.${last.day}"
        return if (week == currentWeekNumber) "${getString(R.string.index_current_week)} · $range" else range
    }

    private fun showWeekPicker() {
        val s = schedule ?: return
        val total = s.totalWeeks.coerceAtLeast(1)
        WeekPickerSheet(
            requireContext(),
            totalWeeks = total,
            semesterStart = s.semesterStart,
            selectedWeek = currentWeek,
            currentWeekNumber = currentWeekNumber,
            weekendMode = Prefs.weekendDisplayMode,
            onWeekendModeChanged = { mode ->
                Prefs.weekendDisplayMode = mode
                binding.timetable.setWeekendDisplayMode(mode)
            }
        ) { week -> binding.timetable.goToWeek(week) }.show()
    }

    private suspend fun syncRemoteThenReload(enabled: Boolean) {
        if (!enabled || ApiClient.isLocalMode || syncRunning) return
        syncRunning = true
        val synced = ApiClient.syncSchedules()
        syncRunning = false
        if (synced && _binding != null) load(silent = true, syncRemote = false)
    }

    private fun showCourse(click: com.zhusijiao.app.ui.common.TimetableView.CourseClick) {
        val current = schedule ?: return
        CourseDetailSheet(
            requireContext(),
            click,
            canEdit = current.isOwner,
            onReschedule = {
                RescheduleSheet(requireContext(), current, click) { draft ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val updated = ApiClient.saveAdjustment(
                                current.id, draft, click.adjustment?.id, current.revision
                            )
                            applyUpdatedSchedule(updated)
                            Ui.toast(requireContext(), getString(R.string.reschedule_saved))
                        } catch (error: Exception) {
                            Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
                        }
                    }
                }.show()
            },
            onUndo = {
                val adjustment = click.adjustment ?: return@CourseDetailSheet
                Ui.confirm(
                    requireContext(),
                    getString(R.string.reschedule_undo_title),
                    getString(R.string.reschedule_undo_content),
                    confirmText = getString(R.string.reschedule_undo)
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val updated = ApiClient.deleteAdjustment(current.id, adjustment.id, current.revision)
                            applyUpdatedSchedule(updated)
                            Ui.toast(requireContext(), getString(R.string.reschedule_undone))
                        } catch (error: Exception) {
                            Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
                        }
                    }
                }
            }
        ).show()
    }

    private fun applyUpdatedSchedule(updated: Schedule) {
        schedule = updated
        binding.header.setTitle(updated.name)
        updateHeaderActions()
        binding.timetable.setSchedule(
            updated,
            events = events,
            jumpToCurrent = false,
            weekendMode = Prefs.weekendDisplayMode
        )
        binding.timetable.goToWeek(currentWeek)
    }

    // ===== 自定义日程 =====

    /**
     * 点空格子新建、或从详情进入编辑。
     * 不判断 isOwner——日程是本机私有数据，订阅别人课表的同学同样可以加自己的安排。
     */
    private fun showEventEditor(
        editing: PersonalEvent?,
        week: Int,
        day: Int,
        section: Int
    ) {
        val current = schedule ?: return
        EventEditorSheet(
            requireContext(),
            schedule = current,
            events = events,
            editing = editing,
            initialWeek = week,
            initialDay = day,
            initialSection = section
        ) { draft -> persistEvent(draft, editing?.id) }.show()
    }

    private fun showEvent(click: TimetableView.EventClick) {
        val current = schedule ?: return
        EventDetailSheet(
            requireContext(),
            data = click,
            slots = ScheduleTime.slotsOf(current.timeSlots),
            onEdit = {
                showEventEditor(click.event, click.week, click.event.day, click.event.startSection)
            },
            onDelete = {
                Ui.confirm(
                    requireContext(),
                    getString(R.string.event_delete_title),
                    getString(R.string.event_delete_content),
                    confirmText = getString(R.string.event_delete),
                    confirmColor = 1
                ) { deleteEvent(click.event.id) }
            }
        ).show()
    }

    private fun persistEvent(draft: PersonalEventDraft, eventId: String?) {
        val current = schedule ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PersonalEventStore.save(
                        scheduleId = current.id,
                        draft = draft,
                        eventId = eventId,
                        totalWeeks = current.totalWeeks,
                        slots = ScheduleTime.slotsOf(current.timeSlots)
                    )
                }
                reloadEvents(current.id)
                Ui.toast(requireContext(), getString(R.string.event_saved))
            } catch (error: Exception) {
                Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun deleteEvent(eventId: String) {
        val current = schedule ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { PersonalEventStore.delete(current.id, eventId) }
                reloadEvents(current.id)
                Ui.toast(requireContext(), getString(R.string.event_deleted))
            } catch (error: Exception) {
                Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    /** 日程改动后就地重绘：课表本身没变，不必走整页 load。 */
    private suspend fun reloadEvents(scheduleId: String) {
        val loaded = withContext(Dispatchers.IO) { PersonalEventStore.list(scheduleId) }
        if (_binding == null) return
        events = loaded
        val current = schedule ?: return
        binding.timetable.setSchedule(
            current,
            events = events,
            jumpToCurrent = false,
            weekendMode = Prefs.weekendDisplayMode
        )
        binding.timetable.goToWeek(currentWeek)
    }

    /**
     * 页头操作区：左「课表外观」图标（所有人可见），右「调休」文字（仅发布者）。
     * 外观用图标而非文字，避免两个中文按钮把居中的课表名挤掉。
     */
    private fun updateHeaderActions() {
        val header = binding.header
        header.clearActions()
        header.addAction(appearanceAction())
        if (schedule?.isOwner != true) return
        val action = TextView(requireContext()).apply {
            text = getString(R.string.header_holiday_action)
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.sub_6c))
            val borderless = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderless, true)
            setBackgroundResource(borderless.resourceId)
            gravity = Gravity.CENTER
            setPadding(Rpx.dp(10f), Rpx.dp(6f), Rpx.dp(10f), Rpx.dp(6f))
            minWidth = Rpx.dp(40f)
            minimumHeight = Rpx.dp(40f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.holiday_title)
            setOnClickListener { showHolidaySheet(currentWeek, defaultHolidayDay()) }
        }
        header.addAction(action)
    }

    private fun appearanceAction(): View = AppCompatImageView(requireContext()).apply {
        setImageResource(R.drawable.ic_appearance)
        val borderless = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderless, true)
        setBackgroundResource(borderless.resourceId)
        val pad = Rpx.dp(12f)
        setPadding(pad, pad, pad, pad)
        minimumWidth = Rpx.dp(44f)
        minimumHeight = Rpx.dp(44f)
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.appearance_action)
        setOnClickListener { showAppearanceSheet() }
    }

    /** 供设置页「课表外观」跳转过来时直接弹面板；视图未就绪则记下待办。 */
    fun requestAppearanceSheet() {
        if (_binding == null) {
            pendingAppearanceSheet = true
            return
        }
        binding.timetable.post { showAppearanceSheet() }
    }

    /**
     * 课表外观面板：改一下即时生效并写入本机偏好。
     * 行高变化后按新旧行高比例换算滚动位置，用户正在看的节次不会因为变高变矮而跳走。
     */
    private fun showAppearanceSheet() {
        if (_binding == null) return
        AppearanceSheet(requireContext(), Prefs.timetableAppearance) { value ->
            Prefs.timetableAppearance = value
            applyAppearance(value)
        }.show()
    }

    private fun applyAppearance(value: TimetableAppearance) {
        if (_binding == null) return
        val scroll = binding.scroll
        val beforeRow = binding.timetable.currentRowHeightPx()
        val beforeScroll = scroll.scrollY
        binding.timetable.setAppearance(value)
        if (beforeRow <= 0f || beforeScroll <= 0) return
        scroll.post {
            if (_binding == null) return@post
            val afterRow = binding.timetable.currentRowHeightPx()
            if (afterRow > 0f) scroll.scrollTo(0, (beforeScroll * afterRow / beforeRow).roundToInt())
        }
    }

    /** 页头进入调休时的默认选中日：正看本周就选今天，否则选该周周一。 */
    private fun defaultHolidayDay(): Int {
        if (currentWeek != currentWeekNumber) return 1
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
    }

    /** 停课/补课管理：从表头日期或页头「调休」进入；保存后就地刷新，不关闭面板。 */
    private fun showHolidaySheet(initialWeek: Int, initialDay: Int) {
        val current = schedule ?: return
        val sheet = HolidaySheet(
            requireContext(),
            current,
            initialWeek = initialWeek,
            initialDay = initialDay,
            canEdit = current.isOwner,
            onAddHolidays = { drafts ->
                persistCalendar({ updated ->
                    getString(R.string.holiday_saved_count, updated.holidays.size - current.holidays.size)
                }) { ApiClient.saveHolidays(current.id, drafts, current.revision) }
            },
            onDeleteHoliday = { holiday ->
                persistCalendar({ getString(R.string.holiday_deleted) }) {
                    ApiClient.deleteHoliday(current.id, holiday.id, current.revision)
                }
            },
            onAddMakeup = { draft ->
                persistCalendar({ getString(R.string.holiday_saved) }) {
                    ApiClient.saveMakeup(current.id, draft, current.revision)
                }
            },
            onDeleteMakeup = { makeup ->
                persistCalendar({ getString(R.string.holiday_deleted) }) {
                    ApiClient.deleteMakeup(current.id, makeup.id, current.revision)
                }
            }
        )
        sheet.setOnDismissListener { if (holidaySheet === sheet) holidaySheet = null }
        holidaySheet = sheet
        sheet.show()
    }

    /** 长按课程手动选色：手动颜色优先于自动配色，随课表同步给加入的同学。 */
    private fun showCourseColorSheet(click: TimetableView.CourseClick) {
        val current = schedule ?: return
        if (!current.isOwner) {
            Ui.toast(requireContext(), getString(R.string.course_colors_owner_only))
            return
        }
        ColorPickerSheet(
            requireContext(),
            courseName = click.course.name,
            autoColor = click.backgroundColor,
            currentManual = current.courseColors[click.course.name],
            onPick = { hex -> persistColors(current.courseColors + (click.course.name to hex)) },
            onReset = { persistColors(current.courseColors - click.course.name) }
        ).show()
    }

    private fun persistColors(colors: Map<String, String>) {
        val current = schedule ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = ApiClient.setCourseColors(current.id, colors, current.revision)
                applyUpdatedSchedule(updated)
                Ui.toast(requireContext(), getString(R.string.course_colors_updated))
            } catch (error: Exception) {
                Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun persistCalendar(successMessage: (Schedule) -> String, block: suspend () -> Schedule) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = block()
                applyUpdatedSchedule(updated)
                holidaySheet?.applySchedule(updated)
                Ui.toast(requireContext(), successMessage(updated))
            } catch (error: Exception) {
                Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    companion object {
        private const val STATE_LOADING = 0
        private const val STATE_CONTENT = 1
        private const val STATE_EMPTY = 2
        private const val STATE_ERROR = 3
        private const val STATE_WEEK = "selectedWeek"
    }
}
