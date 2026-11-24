package com.zhusijiao.app.ui.schedule

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.ExtraCourseStore
import com.zhusijiao.app.data.LeaveStore
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.data.ScheduleSyncStore
import com.zhusijiao.app.data.SyncLog
import com.zhusijiao.app.databinding.FragmentScheduleBinding
import com.zhusijiao.app.databinding.ItemDayCourseCardBinding
import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.ExtraCourse
import com.zhusijiao.app.domain.ExtraCourses
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.NextClass
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimetableAppearance
import com.zhusijiao.app.ui.common.ActionSheet
import com.zhusijiao.app.ui.common.AddEntrySheet
import com.zhusijiao.app.ui.common.AppToast
import com.zhusijiao.app.ui.common.AppearanceSheet
import com.zhusijiao.app.ui.common.BlockStyles
import com.zhusijiao.app.ui.common.BottomNavView
import com.zhusijiao.app.ui.common.ColorPickerSheet
import com.zhusijiao.app.ui.common.CourseDetailSheet
import com.zhusijiao.app.ui.common.EventDetailSheet
import com.zhusijiao.app.ui.common.ExportFlow
import com.zhusijiao.app.ui.common.HolidaySheet
import com.zhusijiao.app.ui.common.LeaveFlow
import com.zhusijiao.app.ui.common.Refreshable
import com.zhusijiao.app.ui.common.TimetableView
import com.zhusijiao.app.ui.common.RescheduleSheet
import com.zhusijiao.app.ui.common.WeekPickerSheet
import com.zhusijiao.app.ui.course.CourseEditorActivity
import com.zhusijiao.app.ui.event.EventEditorActivity
import com.zhusijiao.app.ui.importer.ImportActivity
import com.zhusijiao.app.ui.join.JoinActivity
import com.zhusijiao.app.ui.share.ShareActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** 周课表页：应用主界面。 */
class ScheduleFragment : Fragment(), Refreshable {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    private var schedule: Schedule? = null

    /** 本机私有日程，不随课表同步；与课表分开加载、分开保存。 */
    private var events: List<PersonalEvent> = emptyList()

    /** 本机的请假记录，不随课表同步、也不按课表分组（请假说的是「我这段时间不在」）。 */
    private var leaves: List<Leave> = emptyList()

    /**
     * 自己加的课（本机私有）。[schedule] 里存的是已经把它们并进去的课表，
     * 所以显示、调课冲突、请假、下一节课都不用再单独考虑；保存类操作只传 id 和草稿，不受影响。
     */
    private var extras: List<ExtraCourse> = emptyList()

    /**
     * 课表自己的手动配色（发布者设的、同步给全班的那份）。[schedule] 的 courseColors 里还并了
     * 自己加的课的私人颜色，只能拿来显示；保存课表配色必须基于这一份，否则私人颜色会被写回去同步出去。
     */
    private var sharedColors: Map<String, String> = emptyMap()
    private var currentWeekNumber = 1
    private var currentWeek = 1
    /** 今日视图正在查看的星期（1=周一..7=周日）。 */
    private var selectedDay = todayDayOfWeek()
    /** 视图模式：false = 周视图，true = 今日视图。 */
    private var dayViewMode = false
    private var restoredWeek: Int? = null
    private var contentState = STATE_LOADING
    private var syncRunning = false

    /** 本机全部课表的摘要，页头切换课表用。 */
    private var allSchedules: List<Schedule> = emptyList()
    private val exportFlow = ExportFlow(this)

    /** 打开中的调休面板：保存后就地刷新，不必关掉再进。 */
    private var holidaySheet: HolidaySheet? = null

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
        binding.timetable.canLongClickCourse = { course -> canRecolor(course) }
        binding.timetable.onDayClick = { click -> showHolidaySheet(click.week, click.day) }
        binding.timetable.onEventClick = { click -> showEvent(click) }
        binding.timetable.onEmptySlotClick = { week, day, section -> showAddChoice(week, day, section) }
        binding.timetable.onWeekChanged = { week, isFirst, isLast -> updateWeekBar(week, isFirst, isLast) }
        binding.timetable.onPageScroll = { shown, local -> followPage(shown, local) }
        binding.weekCenter.setOnClickListener { showWeekPicker() }
        binding.backToCurrentWeek.setOnClickListener {
            selectedDay = todayDayOfWeek()
            if (currentWeek != currentWeekNumber) binding.timetable.goToWeek(currentWeekNumber)
            else renderDayView()
            updateBackButton()
        }
        binding.weekMoreBtn.setOnClickListener { showMoreActions() }
        dayViewMode = Prefs.scheduleDayView
        binding.modeWeekBtn.setOnClickListener { setDayViewMode(false) }
        binding.modeDayBtn.setOnClickListener { setDayViewMode(true) }
        renderViewModeSegment()
        binding.nextClassBar.setOnClickListener { openNextClass() }
        binding.emptyImport.setOnClickListener {
            startActivity(Intent(requireContext(), ImportActivity::class.java))
        }
        binding.emptyJoin.setOnClickListener { startActivity(Intent(requireContext(), JoinActivity::class.java)) }
        // 本机模式没有数据服务，加入永远走不通：不放一个点了才报错的按钮
        binding.emptyJoin.visibility = if (ApiClient.isLocalMode) View.GONE else View.VISIBLE
        binding.errorRetry.setOnClickListener { load(silent = false) }
        binding.timetable.setAppearance(Prefs.timetableAppearance)
        // 内容区铺到屏幕底、悬浮底栏盖在上面：可视高度要扣掉底栏占的那一段
        BottomNavView.padScrollContent(binding.scroll)
        BottomNavView.padScrollContent(binding.dayScroll)
        binding.scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.timetable.setScrollOffset(scrollY)
        }
        binding.contentArea.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            binding.timetable.setViewportHeight(bottom - top - BottomNavView.contentInset(v))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.root.removeCallbacks(nextClassTick)
        holidaySheet?.dismiss()
        holidaySheet = null
        leaveFlow.dismiss()
        exportFlow.dismiss()
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
                allSchedules = schedules
                val activeId = Prefs.activeScheduleId
                if (schedules.isEmpty()) {
                    if (activeId.isNotBlank()) notifyMissingSchedule(activeId, fallbackName = null)
                    schedule = null
                    events = emptyList()
                    leaves = emptyList()
                    extras = emptyList()
                    binding.timetable.setSchedule(null)
                    binding.header.clearActions()
                    setState(STATE_EMPTY)
                    Prefs.removeActiveSchedule()
                    syncRemoteThenReload(syncRemote)
                    return@launch
                }
                val requested = schedules.find { it.id == activeId }
                val summary = requested ?: schedules[0]
                val stored = ApiClient.getSchedule(summary.id)
                extras = withContext(Dispatchers.IO) { ExtraCourseStore.list(stored.id) }
                sharedColors = stored.courseColors
                val loadedSchedule = ExtraCourses.merge(stored, extras)
                events = withContext(Dispatchers.IO) { PersonalEventStore.list(loadedSchedule.id) }
                leaves = withContext(Dispatchers.IO) { LeaveStore.list() }
                // 同一份课表刷新（切后台回前台、同步完成等）时保持用户正在浏览的周次，
                // 不能强制跳回本周；只有切换到另一份课表时才回到本周。
                val sameSchedule = schedule?.id == loadedSchedule.id
                schedule = loadedSchedule
                Prefs.activeScheduleId = loadedSchedule.id
                currentWeekNumber = DateUtils.currentWeek(loadedSchedule.semesterStart, loadedSchedule.totalWeeks)
                updateHeaderActions()
                binding.timetable.setSchedule(
                    loadedSchedule,
                    events = events,
                    jumpToCurrent = !sameSchedule,
                    weekendMode = Prefs.weekendDisplayMode,
                    leaves = leaves
                )
                if (sameSchedule) {
                    // 周数可能在同步后变化，收敛到合法范围；与当前周相同则不会触发跳转
                    binding.timetable.goToWeek(currentWeek.coerceIn(1, loadedSchedule.totalWeeks))
                }
                restoredWeek?.let {
                    binding.timetable.goToWeek(it.coerceIn(1, loadedSchedule.totalWeeks))
                    restoredWeek = null
                }
                if (introScheduleId == loadedSchedule.id) {
                    introScheduleId = null
                    binding.timetable.playIntro()
                }
                setState(STATE_CONTENT)
                // 要打开的课表已经不在本机时必须明说，否则用户只会看到「打开的是别的课表」
                if (activeId.isNotBlank() && requested == null) notifyMissingSchedule(activeId, loadedSchedule.name)
                else maybePromptGone(loadedSchedule)
                maybeShowStateStyleTip()
                syncRemoteThenReload(syncRemote)
            } catch (e: Exception) {
                binding.errorMessage.text = e.message ?: getString(R.string.index_load_failed_title)
                setState(STATE_ERROR)
            }
        }
    }

    private fun setState(state: Int) {
        contentState = state
        val hasContent = state == STATE_CONTENT
        // 有课表时页头由 weekBar 接管（左侧第 N 周 + 日期课表名，右侧周/日切换 + 更多）
        binding.weekBar.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.header.setTitle(if (hasContent) "" else getString(R.string.index_default_title))
        binding.scroll.visibility = if (hasContent && !dayViewMode) View.VISIBLE else View.GONE
        binding.dayViewContainer.visibility = if (hasContent && dayViewMode) View.VISIBLE else View.GONE
        binding.loading.visibility = if (state == STATE_LOADING) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (state == STATE_EMPTY) View.VISIBLE else View.GONE
        binding.errorState.visibility = if (state == STATE_ERROR) View.VISIBLE else View.GONE
        if (hasContent && dayViewMode) renderDayView()
        updateBackButton()
        updateNextClass()
    }

    private fun setDayViewMode(dayMode: Boolean) {
        if (dayViewMode == dayMode) return
        dayViewMode = dayMode
        Prefs.scheduleDayView = dayMode
        if (dayMode && currentWeek == currentWeekNumber) selectedDay = todayDayOfWeek()
        renderViewModeSegment()
        if (contentState == STATE_CONTENT) {
            binding.scroll.visibility = if (dayMode) View.GONE else View.VISIBLE
            binding.dayViewContainer.visibility = if (dayMode) View.VISIBLE else View.GONE
            if (dayMode) renderDayView()
            updateBackButton()
            updateNextClass()
        }
    }

    private fun renderViewModeSegment() {
        val ctx = context ?: return
        fun styleBtn(btn: TextView, active: Boolean) {
            btn.setBackgroundResource(if (active) R.drawable.bg_segment_pill else 0)
            btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            btn.setTextColor(ContextCompat.getColor(ctx, if (active) R.color.text_primary else R.color.text_secondary))
        }
        styleBtn(binding.modeWeekBtn, !dayViewMode)
        styleBtn(binding.modeDayBtn, dayViewMode)
    }

    private fun updateBackButton() {
        val show = if (dayViewMode) {
            currentWeek != currentWeekNumber || selectedDay != todayDayOfWeek()
        } else {
            currentWeek != currentWeekNumber
        }
        binding.backToCurrentWeek.visibility = if (show) View.VISIBLE else View.GONE
        binding.backToCurrentWeek.setText(if (dayViewMode) R.string.couple_back_today else R.string.index_back_current_week)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateWeekBar(week: Int, isFirst: Boolean, isLast: Boolean) {
        currentWeek = week
        showWeekTitle(week)
        if (dayViewMode) renderDayView()
        updateBackButton()
        updateNextClass()
    }

    /** 页头正在显示哪一周（翻页途中跟随露出更多的那一页，可能还不是 [currentWeek]）。 */
    private var titleWeek = 0

    private fun showWeekTitle(week: Int) {
        titleWeek = week
        binding.weekTitle.text = getString(R.string.index_week_title, week)
        binding.weekCaption.text = captionForWeek(week)
    }

    /**
     * 页头「第 N 周」与课表页面同一个进度：页面往左翻，标题也轻柔往左微移并淡出，
     * 过了一半换成下一周的字再淡入回位——两者步调一致，且位移小于左留白不会撞边裁切。
     */
    private fun followPage(shown: Int, local: Float) {
        if (shown != titleWeek) showWeekTitle(shown)
        val center = binding.weekCenter
        center.translationX = local * WEEK_TITLE_SHIFT_DP * resources.displayMetrics.density
        center.alpha = (1f - abs(local) * 2.2f).coerceIn(0f, 1f)
    }

    /** 已在课表页时再点底栏的「课表」：回到本周（日视图同步回到今天）；已经在本周就滚回顶部。 */
    fun onReselected() {
        if (_binding == null || schedule == null) return
        val today = todayDayOfWeek()
        if (currentWeek != currentWeekNumber) {
            selectedDay = today
            binding.timetable.goToWeek(currentWeekNumber)
        } else if (dayViewMode && selectedDay != today) {
            selectedDay = today
            renderDayView()
            updateBackButton()
        } else if (dayViewMode) {
            binding.dayScroll.smoothScrollTo(0, 0)
        } else {
            binding.scroll.smoothScrollTo(0, 0)
        }
    }

    /** 供设置页「课表外观」跳转课表页后直接呼出外观面板。 */
    fun openAppearanceFromSettings() {
        if (_binding == null) return
        if (dayViewMode) setDayViewMode(false)
        binding.root.post { showAppearanceSheet() }
    }

    // ===== 请假 =====

    /**
     * 课表右上角菜单、课程详情和设置页共用 [LeaveFlow]，保存后刷新课表上的请假状态。
     */
    private val leaveFlow = LeaveFlow(this) { loaded ->
        if (_binding != null) {
            leaves = loaded
            schedule?.let { current ->
                binding.timetable.setSchedule(
                    current,
                    events = events,
                    jumpToCurrent = false,
                    weekendMode = Prefs.weekendDisplayMode,
                    leaves = leaves
                )
                binding.timetable.goToWeek(currentWeek)
            }
            updateNextClass()
        }
    }

    // ===== 下一节课提示条 =====

    private var nextClass: NextClass.Info? = null
    private val nextClassTick = Runnable { updateNextClass() }

    /** 只在周视图、看本周、今天还有没下课的课时显示（课表外观里可整条关掉）；每到整分钟刷新一次倒计时。 */
    private fun updateNextClass() {
        val b = _binding ?: return
        b.root.removeCallbacks(nextClassTick)
        val s = schedule
        val now = System.currentTimeMillis()
        val enabled = Prefs.timetableAppearance.showNextClass
        val info = if (enabled && !dayViewMode && s != null && contentState == STATE_CONTENT && currentWeek == currentWeekNumber) {
            (NextClass.find(s, events, now, leaves) ?: NextClass.firstTomorrow(s, events, now, leaves))
                ?.takeIf { it.week == currentWeekNumber }
        } else null
        nextClass = info
        if (info == null) {
            b.nextClassBar.visibility = View.GONE
            return
        }
        val item = info.item
        val ctx = requireContext()
        b.nextClassTitle.text = item.title
        val timeRange = listOf(item.startTime, item.endTime).filter { it.isNotBlank() }.joinToString("–")
        b.nextClassSub.text = listOfNotNull(
            item.position.takeIf { it.isNotBlank() },
            timeRange.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        b.nextClassTime.text = when {
            info.tomorrow -> "${getString(R.string.widget_tomorrow)} ${item.startTime}"
            info.ongoing -> getString(if (item.isEvent) R.string.next_class_event_ends_at else R.string.next_class_ends_at, item.endTime)
            info.minutesUntil <= 0 -> getString(R.string.next_class_now)
            info.minutesUntil <= 60 -> getString(R.string.next_class_in_minutes, info.minutesUntil)
            else -> getString(R.string.next_class_starts_at, item.startTime)
        }
        val (pillBg, pillText) = when {
            info.ongoing -> R.color.couple_status_busy_bg to R.color.danger
            info.tomorrow -> R.color.surface_muted to R.color.text_secondary
            else -> R.color.accent_soft to R.color.accent_strong
        }
        b.nextClassTime.background = GradientDrawable().apply {
            cornerRadius = Rpx.dp(100f).toFloat()
            setColor(ContextCompat.getColor(ctx, pillBg))
        }
        b.nextClassTime.setTextColor(ContextCompat.getColor(ctx, pillText))
        b.nextClassBar.visibility = View.VISIBLE
        b.root.postDelayed(nextClassTick, 60_000L - now % 60_000L + 500L)
    }

    // ===== 今日视图（日视图） =====

    private fun todayDayOfWeek(): Int {
        val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    /** 渲染今日视图：顶部居中对齐的本周七天选择条 + 左侧时间线 + 清晰课程卡片流。 */
    private fun renderDayView() {
        val b = _binding ?: return
        val s = schedule ?: return
        val ctx = context ?: return
        val dates = DateUtils.datesForWeek(s.semesterStart, currentWeek)
        val shortNames = listOf("一", "二", "三", "四", "五", "六", "日")
        val slots = ScheduleTime.slotsOf(s.timeSlots)
        val palette = ScheduleView.buildCoursePaletteMap(
            s.courses + s.adjustments.map { it.courseSnapshot }, s.courseColors
        )

        // 1. 顶部一周 7 天快速切换条（星期、日期数字、有课圆点严格同轴居中对齐）
        b.dayStrip.removeAllViews()
        for (day in 1..7) {
            val dateInfo = dates.getOrNull(day - 1)
            val isSelected = day == selectedDay
            val isToday = dateInfo?.today == true
            val hasItems = ScheduleOccurrences.occurrencesOn(s, currentWeek, day).isNotEmpty() ||
                events.any { it.day == day && it.occursIn(currentWeek) }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = Rpx.dp(2f); marginEnd = Rpx.dp(2f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = Rpx.dp(14f).toFloat()
                    setColor(
                        when {
                            isSelected -> ContextCompat.getColor(ctx, R.color.accent)
                            isToday -> ContextCompat.getColor(ctx, R.color.accent_soft)
                            else -> Color.TRANSPARENT
                        }
                    )
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedDay != day) {
                        selectedDay = day
                        renderDayView()
                        updateBackButton()
                    }
                }
            }
            val wrap = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            cell.addView(TextView(ctx).apply {
                layoutParams = wrap
                gravity = Gravity.CENTER
                includeFontPadding = false
                text = shortNames[day - 1]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        when {
                            isSelected -> R.color.surface
                            isToday -> R.color.accent_strong
                            else -> R.color.text_tertiary
                        }
                    )
                )
            })
            cell.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Rpx.dp(3f) }
                gravity = Gravity.CENTER
                includeFontPadding = false
                text = dateInfo?.day?.toString() ?: shortNames[day - 1]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = if (isSelected || isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        when {
                            isSelected -> R.color.surface
                            isToday -> R.color.accent_strong
                            else -> R.color.text_primary
                        }
                    )
                )
            })
            cell.addView(View(ctx).apply {
                val size = Rpx.dp(4f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = Rpx.dp(4f) }
                visibility = if (hasItems) View.VISIBLE else View.INVISIBLE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        ContextCompat.getColor(
                            ctx,
                            if (isSelected) R.color.surface else R.color.accent
                        )
                    )
                }
            })
            b.dayStrip.addView(cell)
        }

        // 2. 当天课程与日程列表（使用独立的日视图已上状态开关 Prefs.timetableAppearance.showDayFinished）
        b.dayCardList.removeAllViews()
        val dateIso = dates.getOrNull(selectedDay - 1)?.iso
        val todayIso = DateUtils.formatDate(Calendar.getInstance())
        val isToday = dates.getOrNull(selectedDay - 1)?.today == true
        val isPastDay = dateIso != null && dateIso < todayIso
        val showDayFinished = Prefs.timetableAppearance.showDayFinished
        val nowMinutes = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val suspended = s.holidays.any { it.week == currentWeek && it.day == selectedDay }

        data class DayEntry(
            val title: String,
            val position: String,
            val teacher: String,
            val startSection: Int,
            val endSection: Int,
            val startTime: String,
            val endTime: String,
            val color: Int,
            val isEvent: Boolean,
            val badge: String,
            val ongoing: Boolean,
            val faded: Boolean
        )

        val entries = mutableListOf<DayEntry>()
        ScheduleOccurrences.occurrencesOn(s, currentWeek, selectedDay).forEach { occ ->
            val course = occ.course
            val startTime = slots.find { it.number == course.startSection }?.startTime.orEmpty()
            val endTime = slots.find { it.number == course.endSection }?.endTime.orEmpty()
            val leave = Leaves.leaveFor(leaves, s, dateIso, course)
            val startMin = parseMinutes(startTime)
            val endMin = parseMinutes(endTime)
            val ongoing = isToday && leave == null && startMin != null && endMin != null && nowMinutes in startMin until endMin
            val finished = showDayFinished && leave == null &&
                (isPastDay || (isToday && endMin != null && nowMinutes >= endMin))
            val badge = when {
                leave != null -> leave.type.label
                ongoing -> getString(R.string.next_class_ongoing)
                finished -> getString(R.string.day_view_badge_finished)
                occ.source == ScheduleOccurrences.Source.ADJUSTED -> getString(R.string.day_view_badge_adjusted)
                occ.source == ScheduleOccurrences.Source.MAKEUP -> getString(R.string.day_view_badge_makeup)
                else -> ScheduleView.alternatingWeekBadge(course.weeks)
            }
            val tone = palette[course.name]?.background ?: ContextCompat.getColor(ctx, R.color.accent)
            entries += DayEntry(
                title = course.name,
                position = course.position,
                teacher = course.teacher,
                startSection = course.startSection,
                endSection = course.endSection,
                startTime = startTime,
                endTime = endTime,
                color = tone,
                isEvent = false,
                badge = badge,
                ongoing = ongoing,
                faded = leave != null || finished
            )
        }
        events.filter { it.day == selectedDay && it.occursIn(currentWeek) }.forEach { ev ->
            val startTime = ev.startTime ?: slots.find { it.number == ev.startSection }?.startTime.orEmpty()
            val endTime = ev.endTime ?: slots.find { it.number == ev.endSection }?.endTime.orEmpty()
            val startMin = parseMinutes(startTime)
            val endMin = parseMinutes(endTime)
            val ongoing = isToday && startMin != null && endMin != null && nowMinutes in startMin until endMin
            val finished = showDayFinished &&
                (isPastDay || (isToday && endMin != null && nowMinutes >= endMin))
            val tone = ScheduleView.manualPalette(ev.color)?.background ?: BlockStyles.DEFAULT_EVENT_COLOR
            entries += DayEntry(
                title = ev.title,
                position = ev.position,
                teacher = ev.note,
                startSection = ev.startSection,
                endSection = ev.endSection,
                startTime = startTime,
                endTime = endTime,
                color = tone,
                isEvent = true,
                badge = when {
                    ongoing -> getString(R.string.next_class_ongoing)
                    finished -> getString(R.string.day_view_badge_finished)
                    else -> getString(R.string.event_badge)
                },
                ongoing = ongoing,
                faded = finished
            )
        }

        entries.sortWith(compareBy({ it.startSection }, { it.isEvent }, { it.startTime }))

        if (entries.isEmpty()) {
            b.dayEmptyCard.visibility = View.VISIBLE
            b.dayEmptyTitle.setText(if (suspended) R.string.day_view_suspended else R.string.day_view_empty)
            b.dayEmptySub.visibility = View.GONE
            return
        }
        b.dayEmptyCard.visibility = View.GONE

        entries.forEachIndexed { index, item ->
            val card = ItemDayCourseCardBinding.inflate(layoutInflater, b.dayCardList, false)
            card.dayStartTime.text = item.startTime.ifBlank { "-" }
            card.dayEndTime.text = item.endTime.ifBlank { "-" }
            card.dayStartTime.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        item.ongoing -> R.color.couple_now_line
                        item.faded -> R.color.text_tertiary
                        else -> R.color.text_primary
                    }
                )
            )

            // 侧边时间线：首项隐藏上段，末项隐藏下段；圆点区分已上/正在上/未上
            card.timelineTopLine.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
            card.timelineBottomLine.visibility = if (index == entries.lastIndex) View.INVISIBLE else View.VISIBLE
            val dotStrokePx = Rpx.dp(2f)
            card.timelineDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    item.ongoing -> {
                        setColor(ContextCompat.getColor(ctx, R.color.couple_now_line))
                        setStroke(dotStrokePx, ContextCompat.getColor(ctx, R.color.couple_status_busy_bg))
                    }
                    item.faded -> {
                        setColor(ContextCompat.getColor(ctx, R.color.background))
                        setStroke(dotStrokePx, ContextCompat.getColor(ctx, R.color.text_disabled))
                    }
                    else -> {
                        setColor(BlockStyles.courseSoftAccent(item.color))
                        setStroke(dotStrokePx, ContextCompat.getColor(ctx, R.color.background))
                    }
                }
            }

            card.dayTitle.text = item.title
            card.dayCardRoot.alpha = if (item.faded) 0.56f else 1f
            if (item.badge.isNotBlank()) {
                card.dayBadge.visibility = View.VISIBLE
                card.dayBadge.text = item.badge
                val (bgColor, fgColor) = when {
                    item.ongoing -> ContextCompat.getColor(ctx, R.color.couple_status_busy_bg) to ContextCompat.getColor(ctx, R.color.danger)
                    item.faded -> ContextCompat.getColor(ctx, R.color.surface_muted) to ContextCompat.getColor(ctx, R.color.text_tertiary)
                    else -> BlockStyles.courseSoftFill(item.color) to BlockStyles.courseSoftText(item.color)
                }
                card.dayBadge.background = GradientDrawable().apply {
                    cornerRadius = Rpx.dp(100f).toFloat()
                    setColor(bgColor)
                }
                card.dayBadge.setTextColor(fgColor)
            }

            val sectionLabel = if (item.startSection == item.endSection) {
                getString(R.string.day_view_section_single, item.startSection)
            } else {
                getString(R.string.day_view_section_range, item.startSection, item.endSection)
            }
            card.dayPlace.text = listOfNotNull(
                item.position.takeIf { it.isNotBlank() },
                sectionLabel
            ).joinToString(" · ")

            if (item.teacher.isNotBlank()) {
                card.dayTeacherRow.visibility = View.VISIBLE
                card.dayTeacher.text = item.teacher
            }
            card.dayCardRoot.setOnClickListener {
                binding.timetable.openDayItem(selectedDay, item.title, item.isEvent, item.startSection)
            }
            b.dayCardList.addView(card.root)
        }
    }

    private fun parseMinutes(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun openNextClass() {
        val info = nextClass ?: return
        val item = info.item
        binding.timetable.openBlockAt(info.day, item.title, item.isEvent, item.startTime)
    }

    /** 周次下面那一行：「本周 · 11.9–11.15 · 课表名」，没有的部分不写；太长时从末尾的课表名开始省略。 */
    private fun captionForWeek(week: Int): String {
        val s = schedule ?: return ""
        val dates = DateUtils.datesForWeek(s.semesterStart, week)
        return listOfNotNull(
            getString(R.string.index_current_week).takeIf { week == currentWeekNumber },
            dates.takeIf { it.isNotEmpty() }?.let { "${it.first().month}.${it.first().day}–${it.last().month}.${it.last().day}" },
            s.name.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    private fun showWeekPicker() {
        val s = schedule ?: return
        val total = s.totalWeeks.coerceAtLeast(1)
        WeekPickerSheet(
            requireContext(),
            totalWeeks = total,
            semesterStart = s.semesterStart,
            selectedWeek = currentWeek,
            currentWeekNumber = currentWeekNumber
        ) { week -> binding.timetable.goToWeek(week) }.show()
    }

    private suspend fun syncRemoteThenReload(enabled: Boolean) {
        if (!enabled || ApiClient.isLocalMode || syncRunning) return
        syncRunning = true
        val synced = ApiClient.syncSchedules()
        syncRunning = false
        if (_binding == null) return
        if (!synced) ApiClient.takeNewSyncError()?.let { Ui.toastError(requireContext(), it) }
        // 失败也重载：单条写入失败时远端课表仍可能已拉取，或有订阅课表被清理
        load(silent = true, syncRemote = false)
    }

    /**
     * 正在看的订阅课表在服务端已失效（发布者删除 / 已不在同步名单）：问用户要不要移除。
     * 选「暂不移除」后不再追问（课表库卡片仍有标识）；同一进程内同一份只弹一次，
     * 避免同步完成后的重载在弹窗还开着时又弹一个。
     */
    private fun maybePromptGone(current: Schedule) {
        if (current.isOwner || !ApiClient.needsGonePrompt(current.id)) return
        if (!promptedGone.add(current.id)) return
        val deleted = ApiClient.remoteGone(current.id) == ScheduleSyncStore.RemoteGone.DELETED
        Ui.confirm(
            requireContext(),
            getString(if (deleted) R.string.gone_deleted_title else R.string.gone_not_member_title),
            getString(if (deleted) R.string.gone_deleted_content else R.string.gone_not_member_content, current.name),
            confirmText = getString(R.string.gone_remove),
            cancelText = getString(R.string.gone_keep),
            confirmColor = ContextCompat.getColor(requireContext(), R.color.danger_confirm),
            onCancel = { ApiClient.acknowledgeGone(current.id) }
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    ApiClient.leaveSchedule(current.id)
                    if (Prefs.activeScheduleId == current.id) Prefs.removeActiveSchedule()
                    Ui.toastSuccess(requireContext(), getString(R.string.gone_removed))
                    load(silent = true, syncRemote = false)
                } catch (e: Exception) {
                    SyncLog.log("移除课表失败", "local=${current.id} ${e.javaClass.name}: ${e.message}")
                    promptedGone.remove(current.id)
                    Ui.toastError(requireContext(), e.message ?: getString(R.string.common_load_failed))
                }
            }
        }
    }

    /** 当前课表指针指向的课表已不在本机：说明原因，以及现在显示的是哪份。 */
    private fun notifyMissingSchedule(missingId: String, fallbackName: String?) {
        SyncLog.log("要打开的课表不在本机", "local=$missingId fallback=${fallbackName ?: "-"}")
        val reason = getString(R.string.schedule_missing_generic)
        val message = if (fallbackName != null) {
            reason + "\n\n" + getString(R.string.schedule_missing_switched, fallbackName)
        } else reason
        Ui.alert(requireContext(), getString(R.string.schedule_missing_title), message)
    }

    private fun showCourse(click: com.zhusijiao.app.ui.common.TimetableView.CourseClick) {
        val current = schedule ?: return
        if (ExtraCourses.isExtra(click.course)) {
            showExtraCourse(current, click)
            return
        }
        CourseDetailSheet(
            requireContext(),
            click,
            canEdit = current.isOwner,
            onColor = if (canRecolor(click.course)) ({ showCourseColorSheet(click) }) else null,
            // 请假是本机私有的，订阅来的课表同样可以请；没有开学日期时详情里不给入口
            onLeave = { leaveFlow.showForCourse(current, click) },
            onReschedule = {
                RescheduleSheet(requireContext(), current, click) { draft ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val updated = ApiClient.saveAdjustment(
                                current.id, draft, click.adjustment?.id, current.revision
                            )
                            applyUpdatedSchedule(updated)
                            // 新建的调课可以一键撤销；修改已有调课不提供（撤销要还原的是上一次的草稿，容易误解）
                            val created = if (click.adjustment == null) {
                                updated.adjustments.firstOrNull { new -> current.adjustments.none { it.id == new.id } }
                            } else null
                            Ui.toastSuccess(
                                requireContext(),
                                getString(R.string.reschedule_saved),
                                created?.let { adjustment ->
                                    AppToast.Action(getString(R.string.common_undo)) { undoAdjustment(adjustment.id) }
                                }
                            )
                        } catch (error: Exception) {
                            Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
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
                            Ui.toastSuccess(requireContext(), getString(R.string.reschedule_undone))
                        } catch (error: Exception) {
                            Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
                        }
                    }
                }
            }
        ).show()
    }

    /** 轻提示里的「撤销」：删掉刚新建的那条调课。 */
    private fun undoAdjustment(adjustmentId: String) {
        if (_binding == null) return
        val latest = schedule ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = ApiClient.deleteAdjustment(latest.id, adjustmentId, latest.revision)
                applyUpdatedSchedule(updated)
                Ui.toastSuccess(requireContext(), getString(R.string.reschedule_undone))
            } catch (error: Exception) {
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    /** 调课、调休、改色保存后拿到的是课表本身，要重新并入自己加的课再显示。 */
    private fun applyUpdatedSchedule(stored: Schedule) {
        sharedColors = stored.courseColors
        val updated = ExtraCourses.merge(stored, extras)
        schedule = updated
        showWeekTitle(titleWeek.takeIf { it > 0 } ?: currentWeek)
        updateHeaderActions()
        binding.timetable.setSchedule(
            updated,
            events = events,
            jumpToCurrent = false,
            weekendMode = Prefs.weekendDisplayMode,
            leaves = leaves
        )
        binding.timetable.goToWeek(currentWeek)
        if (dayViewMode) renderDayView()
    }

    // ===== 自己加的课 =====

    /** 点空格子：先问加一节课还是加一条日程（都只存在本机，订阅来的课表同样可以加）。 */
    private fun showAddChoice(week: Int, day: Int, section: Int) {
        val current = schedule ?: return
        AddEntrySheet(
            requireContext(),
            title = getString(R.string.add_entry_title, dayNames.getOrElse(day - 1) { "" }, section),
            onCourse = {
                startActivity(CourseEditorActivity.intent(requireContext(), current.id, null, day, section))
            },
            onEvent = { showEventEditor(null, week, day, section) }
        ).show()
    }

    /**
     * 自己加的课的详情：不能调课（它不在课表里），底部换成「编辑」和「删除」。
     * 编辑页保存后回到本页，MainActivity.onResume 会触发 refresh() 重新读取。
     */
    private fun showExtraCourse(current: Schedule, click: TimetableView.CourseClick) {
        CourseDetailSheet(
            requireContext(),
            click,
            canEdit = false,
            readOnlyNote = getString(R.string.course_extra_note),
            onLeave = { leaveFlow.showForCourse(current, click) },
            onEditExtra = {
                startActivity(
                    CourseEditorActivity.intent(
                        requireContext(), current.id, click.course.id, click.course.day, click.course.startSection
                    )
                )
            },
            onDeleteExtra = { deleteExtraCourse(current.id, click.course.id) },
            onColor = { showCourseColorSheet(click) }
        ).show()
    }

    /** 删除自己加的课：直接删，轻提示里可以撤销。 */
    private fun deleteExtraCourse(scheduleId: String, courseId: String) {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    ExtraCourseStore.list(scheduleId).find { it.course.id == courseId }
                        .also { ExtraCourseStore.delete(scheduleId, courseId) }
                }
                load(silent = true, syncRemote = false)
                Ui.toastSuccess(
                    requireContext(),
                    getString(R.string.course_extra_deleted),
                    removed?.let { extra ->
                        AppToast.Action(getString(R.string.common_undo)) { restoreExtraCourse(scheduleId, extra) }
                    }
                )
            } catch (error: Exception) {
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun restoreExtraCourse(scheduleId: String, extra: ExtraCourse) {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ExtraCourseStore.restore(scheduleId, extra) }
                load(silent = true, syncRemote = false)
                Ui.toastSuccess(requireContext(), getString(R.string.course_extra_restored))
            } catch (error: Exception) {
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    /**
     * 长按自己加的课选色，和课表自带的课一样点色块即时预览、点「完成」才保存。
     * 颜色存在本机（[ExtraCourseStore]），不写进课表的配色，所以订阅来的课表也能选。
     */
    private fun showExtraColorSheet(current: Schedule, click: TimetableView.CourseClick) {
        val name = click.course.name
        val saved = extras.find { it.course.id == click.course.id }?.color
        // 「跟随默认配色」预览的是不带任何手动色时这门课的自动色
        val autoColor = ScheduleView.buildCoursePaletteMap(
            current.courses + current.adjustments.map { it.courseSnapshot }
        )[name]?.background ?: click.backgroundColor
        ColorPickerSheet(
            requireContext(),
            courseName = name,
            autoColor = autoColor,
            currentManual = saved,
            onPick = { hex -> persistExtraColor(current.id, click.course.id, hex, saved) },
            onReset = { persistExtraColor(current.id, click.course.id, null, saved) },
            onPreview = { hex ->
                val colors = if (hex == null) current.courseColors - name else current.courseColors + (name to hex)
                previewSchedule(current.copy(courseColors = colors))
            },
            onPreviewCancel = { previewSchedule(schedule ?: current) }
        ).show()
    }

    /** 保存自己加的课的颜色；[previous] 非 [color] 时轻提示带「撤销」。 */
    private fun persistExtraColor(scheduleId: String, courseId: String, color: String?, previous: String?, undoable: Boolean = true) {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ExtraCourseStore.setColor(scheduleId, courseId, color) }
                load(silent = true, syncRemote = false)
                Ui.toastSuccess(
                    requireContext(),
                    getString(if (undoable) R.string.course_colors_updated else R.string.course_colors_restored),
                    if (undoable && previous != color) {
                        AppToast.Action(getString(R.string.common_undo)) {
                            persistExtraColor(scheduleId, courseId, previous, color, undoable = false)
                        }
                    } else null
                )
            } catch (error: Exception) {
                // 课表上可能还是预览的颜色，保存失败要恢复成实际的样子
                schedule?.let(::previewSchedule)
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    // ===== 自定义日程 =====

    /**
     * 点空格子新建、或从详情进入编辑。字段多，用独立页面而不是底部面板。
     * 不判断 isOwner——日程是本机私有数据，订阅别人课表的同学同样可以加自己的安排。
     * 保存后返回本页，MainActivity.onResume 会触发 refresh() 重新读日程。
     */
    private fun showEventEditor(
        editing: PersonalEvent?,
        week: Int,
        day: Int,
        section: Int
    ) {
        val current = schedule ?: return
        startActivity(
            EventEditorActivity.intent(
                requireContext(),
                scheduleId = current.id,
                eventId = editing?.id,
                week = week,
                day = day,
                section = section
            )
        )
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
            // 与删除自己加的课一致：直接删，轻提示里可以撤销
            onDelete = { deleteEvent(click.event.id) }
        ).show()
    }

    private fun deleteEvent(eventId: String) {
        val current = schedule ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    PersonalEventStore.get(current.id, eventId).also { PersonalEventStore.delete(current.id, eventId) }
                }
                reloadEvents(current.id)
                Ui.toastSuccess(
                    requireContext(),
                    getString(R.string.event_deleted),
                    removed?.let { event ->
                        AppToast.Action(getString(R.string.common_undo)) { restoreEvent(current.id, event) }
                    }
                )
            } catch (error: Exception) {
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    /** 日程改动后就地重绘：课表本身没变，不必走整页 load。 */
    /** 轻提示里的「撤销」：把刚删掉的日程放回去。 */
    private fun restoreEvent(scheduleId: String, event: PersonalEvent) {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { PersonalEventStore.restore(scheduleId, event) }
                reloadEvents(scheduleId)
                Ui.toastSuccess(requireContext(), getString(R.string.event_restored))
            } catch (error: Exception) {
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private suspend fun reloadEvents(scheduleId: String) {
        val loaded = withContext(Dispatchers.IO) { PersonalEventStore.list(scheduleId) }
        if (_binding == null) return
        events = loaded
        val current = schedule ?: return
        binding.timetable.setSchedule(
            current,
            events = events,
            jumpToCurrent = false,
            weekendMode = Prefs.weekendDisplayMode,
            leaves = leaves
        )
        binding.timetable.goToWeek(currentWeek)
        if (dayViewMode) renderDayView()
    }

    /**
     * 页头右侧的操作已并入 weekBar（周/日切换 + ⋯），AppHeader 保持纯净无多余按钮。
     */
    private fun updateHeaderActions() {
        binding.header.clearActions()
    }

    private fun showMoreActions() {
        val current = schedule ?: return
        val day = defaultHolidayDay()
        val actions = buildList {
            if (allSchedules.size > 1) add(ActionSheet.Item(getString(R.string.schedule_switch), R.drawable.ic_swap, group = 0) { showScheduleSwitcher() })
            add(ActionSheet.Item(getString(R.string.course_new_title), R.drawable.ic_menu_add, group = 0) {
                startActivity(CourseEditorActivity.intent(requireContext(), current.id, null, day, 1))
            })
            add(ActionSheet.Item(getString(R.string.event_new_title), R.drawable.ic_calendar, group = 0) {
                showEventEditor(null, currentWeek, day, 1)
            })
            if (current.isOwner) add(ActionSheet.Item(getString(R.string.holiday_title), R.drawable.ic_menu_holiday, group = 1) {
                showHolidaySheet(currentWeek, day)
            })
            add(ActionSheet.Item(getString(R.string.leave_title), R.drawable.ic_menu_leave, group = 1) {
                leaveFlow.showList(current, leaves)
            })
            add(ActionSheet.Item(getString(R.string.appearance_title), R.drawable.ic_menu_appearance, group = 1) { showAppearanceSheet() })
            if (current.isOwner && !ApiClient.isLocalMode) add(ActionSheet.Item(getString(R.string.library_action_share), R.drawable.ic_menu_share, group = 2) {
                startActivity(Intent(requireContext(), ShareActivity::class.java).putExtra(ShareActivity.EXTRA_ID, current.id))
            })
            add(ActionSheet.Item(getString(R.string.library_action_export), R.drawable.ic_menu_export, group = 2) { exportFlow.show(current.id) })
        }
        ActionSheet(requireContext(), current.name, actions).show()
    }

    /** 「⋯」里的「切换课表」：列出本机全部课表，当前那份带勾；点另一份就地切换。 */
    private fun showScheduleSwitcher() {
        val currentId = schedule?.id
        val items = allSchedules.map { item ->
            ActionSheet.Item(item.name, selected = item.id == currentId) {
                if (item.id != currentId) {
                    Prefs.activeScheduleId = item.id
                    if (ApiClient.coupleState().bound) {
                        Ui.toast(requireContext(), getString(R.string.library_couple_switch_toast))
                    }
                    load(silent = true, syncRemote = false)
                }
            }
        }
        ActionSheet(requireContext(), getString(R.string.schedule_switch), items).show()
    }

    /**
     * 课表外观面板：改一下即时生效并写入本机偏好。
     * 行高变化后按新旧行高比例换算滚动位置，用户正在看的节次不会因为变高变矮而跳走。
     */
    private fun showAppearanceSheet(focusStateStyle: Boolean = false) {
        if (_binding == null) return
        AppearanceSheet(
            requireContext(),
            Prefs.timetableAppearance,
            focusStateStyle,
            initialWeekendMode = Prefs.weekendDisplayMode,
            onWeekendModeChanged = { mode ->
                Prefs.weekendDisplayMode = mode
                _binding?.timetable?.setWeekendDisplayMode(mode)
            }
        ) { value ->
            Prefs.timetableAppearance = value
            applyAppearance(value)
        }.show()
    }

    /**
     * 升级后第一次看到停课、调课或日程时，提示一次「换了新样式」，带「改回」直接打开外观面板的对应分组。
     * 全新安装的用户从没见过旧样式，不提示（见 [Prefs.initStateStyleTip]）。
     */
    private fun maybeShowStateStyleTip() {
        if (!Prefs.stateStyleTipPending || _binding == null) return
        if (!binding.timetable.currentWeekHasStateBlocks()) return
        Prefs.markStateStyleTipShown()
        AppToast.show(
            requireContext(),
            getString(R.string.state_style_tip),
            action = AppToast.Action(getString(R.string.state_style_tip_action)) { showAppearanceSheet(focusStateStyle = true) }
        )
    }

    private fun applyAppearance(value: TimetableAppearance) {
        if (_binding == null) return
        val scroll = binding.scroll
        val beforeRow = binding.timetable.currentRowHeightPx()
        val beforeScroll = scroll.scrollY
        binding.timetable.setAppearance(value)
        if (dayViewMode) renderDayView()
        updateNextClass()
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
                persistCalendar({ getString(R.string.holiday_saved_count, drafts.size) }) {
                    ApiClient.saveHolidays(current.id, drafts, calendarRevision(current.id))
                }
            },
            onAddMakeup = { draft ->
                persistCalendar({ getString(R.string.holiday_saved) }) {
                    ApiClient.saveMakeup(current.id, draft, calendarRevision(current.id))
                }
            },
            onDeleteEntries = { holidays, makeups ->
                val edit = com.zhusijiao.app.domain.CalendarEdit(
                    removeHolidayIds = holidays.map { it.id }.toSet(),
                    removeMakeupIds = makeups.map { it.id }.toSet()
                )
                val undo = com.zhusijiao.app.domain.CalendarEdit(restoreHolidays = holidays, restoreMakeups = makeups)
                persistCalendar({ getString(R.string.holiday_deleted) }, afterSave = { updated ->
                    // 撤销只恢复本次删除的记录，并绑定删除后的版本，拒绝覆盖之后的编辑。
                    AppToast.show(holidaySheet?.context ?: requireContext(), getString(R.string.holiday_deleted), AppToast.Kind.SUCCESS,
                        action = AppToast.Action(getString(R.string.holiday_undo)) {
                            persistCalendar({ getString(R.string.holiday_restored) }) {
                                ApiClient.editCalendar(current.id, undo, updated.revision)
                            }
                        })
                }) { ApiClient.editCalendar(current.id, edit, calendarRevision(current.id)) }
            }

        )
        sheet.setOnDismissListener { if (holidaySheet === sheet) holidaySheet = null }
        holidaySheet = sheet
        sheet.show()
    }

    /** 课表自带的课只有发布者能改色（颜色随课表同步）；自己加的课颜色在本机，谁都能改。 */
    private fun canRecolor(course: Course): Boolean =
        ExtraCourses.isExtra(course) || schedule?.isOwner == true

    /** 课程详情里的「颜色」或长按课程：手动选色：手动颜色优先于自动配色，随课表同步给加入的同学。 */
    private fun showCourseColorSheet(click: TimetableView.CourseClick) {
        val current = schedule ?: return
        // 自己加的课另有一份只在本机的颜色，不受「只有发布者能改色」限制
        if (ExtraCourses.isExtra(click.course)) {
            showExtraColorSheet(current, click)
            return
        }
        if (!current.isOwner) return
        ColorPickerSheet(
            requireContext(),
            courseName = click.course.name,
            autoColor = click.backgroundColor,
            currentManual = sharedColors[click.course.name],
            onPick = { hex -> persistColors(sharedColors + (click.course.name to hex)) },
            onReset = { persistColors(sharedColors - click.course.name) },
            // 点色块只在课表上试色、不写入；点「完成」才保存，直接关掉则恢复
            onPreview = { hex ->
                val colors = if (hex == null) current.courseColors - click.course.name
                else current.courseColors + (click.course.name to hex)
                previewSchedule(current.copy(courseColors = colors))
            },
            onPreviewCancel = { previewSchedule(schedule ?: current) }
        ).show()
    }

    /** 保存手动配色；[undoable] 为 true 时轻提示带「撤销」，恢复成改之前的颜色（撤销本身不再带撤销）。 */
    /** 只刷新课表显示，不改 [schedule]、不写入存储（选色预览用）。 */
    private fun previewSchedule(preview: Schedule) {
        if (_binding == null) return
        binding.timetable.setSchedule(
            preview,
            events = events,
            jumpToCurrent = false,
            weekendMode = Prefs.weekendDisplayMode,
            leaves = leaves
        )
        binding.timetable.goToWeek(currentWeek)
        if (dayViewMode) renderDayView()
    }

    private fun persistColors(colors: Map<String, String>, undoable: Boolean = true) {
        if (_binding == null) return
        val current = schedule ?: return
        val previous = sharedColors
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = ApiClient.setCourseColors(current.id, colors, current.revision)
                applyUpdatedSchedule(updated)
                Ui.toastSuccess(
                    requireContext(),
                    getString(if (undoable) R.string.course_colors_updated else R.string.course_colors_restored),
                    if (undoable) AppToast.Action(getString(R.string.common_undo)) { persistColors(previous, undoable = false) } else null
                )
            } catch (error: Exception) {
                // 课表上可能还是预览的颜色，保存失败要恢复成实际的样子
                previewSchedule(current)
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            }
        }
    }

    private fun calendarRevision(scheduleId: String): Int {
        val latest = schedule
        check(latest?.id == scheduleId) { "课表已切换" }
        return latest.revision
    }

    private var calendarSaving = false

    private fun persistCalendar(
        successMessage: (Schedule) -> String,
        afterSave: ((Schedule) -> Unit)? = null,
        block: suspend () -> Schedule
    ) {
        if (calendarSaving) return
        calendarSaving = true
        holidaySheet?.setSaving(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = block()
                if (schedule?.id == updated.id && _binding != null) {
                    applyUpdatedSchedule(updated)
                    holidaySheet?.applySchedule(schedule ?: updated)
                }
                if (afterSave == null) Ui.toastSuccess(requireContext(), successMessage(updated)) else afterSave(updated)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                holidaySheet?.setSaving(false)
                Ui.toastError(requireContext(), error.message ?: getString(R.string.common_load_failed))
            } finally {
                calendarSaving = false
            }
        }
    }

    companion object {
        private const val STATE_LOADING = 0
        private const val STATE_CONTENT = 1
        private const val STATE_EMPTY = 2
        private const val STATE_ERROR = 3
        private const val STATE_WEEK = "selectedWeek"
        /** 翻过半页时页头标题最多跟着平移多远（半页 ±10dp，始终小于左侧 16dp 留白）。 */
        private const val WEEK_TITLE_SHIFT_DP = 20f

        /** 本进程内已弹过「课表已失效」的课表，跨 Fragment 重建保留。 */
        private val promptedGone = mutableSetOf<String>()

        /** 刚导入/加入、下次展示时要播入场动画的课表。 */
        private var introScheduleId: String? = null

        /** 导入或加入课表成功后调用：回到课表页时，本周的课依次落进格子里。 */
        fun requestIntro(scheduleId: String) {
            introScheduleId = scheduleId
        }
    }
}
