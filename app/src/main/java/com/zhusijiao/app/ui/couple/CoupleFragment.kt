package com.zhusijiao.app.ui.couple

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.databinding.FragmentCoupleBinding
import com.zhusijiao.app.domain.CoupleDay
import com.zhusijiao.app.domain.CoupleState
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.ui.common.CourseDetailSheet
import com.zhusijiao.app.ui.common.CoupleDayView
import com.zhusijiao.app.ui.common.EventDetailSheet
import com.zhusijiao.app.ui.common.Refreshable
import com.zhusijiao.app.ui.common.TimetableView
import com.zhusijiao.app.ui.event.EventEditorActivity
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 「我们」页：情侣课表。
 *
 * - 日视图：两个人一天的安排按真实时间左右排开（[CoupleDayView]），默认自己在左、中间按钮对换；
 * - 周视图：两个人的课叠在一张周课表上（[TimetableView.CoupleLayer]），同一时段都有课才分栏；
 * - 点名字 / 图例改名字和颜色，右上角进设置（三个显示开关、解除绑定）。
 *
 * 数据：我的一侧是当前课表（TA 看到的也是它）和本机日程；TA 的一侧是 [ApiClient.partnerSchedule] 的缓存。
 * 页面可见时每分钟刷新一次：时间线往前走，顺便拉一次情侣状态（TA 改了课表、改了名字都能及时看到）。
 */
class CoupleFragment : Fragment(), Refreshable {

    private var _binding: FragmentCoupleBinding? = null
    private val binding get() = _binding!!

    private enum class Mode { DAY, WEEK }

    private var mode = Mode.DAY

    /** 日视图正在看的日期（YYYY-MM-DD）；null 表示今天，跨过零点也跟着走。 */
    private var selectedDate: String? = null

    private var state: CoupleState = CoupleState.UNBOUND
    private var mySchedule: Schedule? = null
    private var myEvents: List<PersonalEvent> = emptyList()
    private var partnerSchedule: Schedule? = null
    private var lastSyncOk = true
    private var syncRunning = false
    private var tickJob: Job? = null
    private var dayButton: TextView? = null
    private var weekButton: TextView? = null
    private var weekShown = false
    private var currentWeek = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCoupleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.setTitle(getString(R.string.couple_title))
        buildHeaderActions()

        binding.backToday.setOnClickListener {
            selectedDate = null
            renderDay()
        }
        binding.leftPerson.setOnClickListener { editProfile(leftWho()) }
        binding.rightPerson.setOnClickListener { editProfile(rightWho()) }
        binding.swapButton.setOnClickListener {
            Prefs.coupleSwapSides = !Prefs.coupleSwapSides
            render()
        }
        binding.dayView.onItemClick = { side, item -> showItem(if (side == 0) leftWho() else rightWho(), item) }
        binding.dayView.onSwipe = { direction -> shiftDay(direction) }
        binding.noticeClose.setOnClickListener {
            ApiClient.markCoupleNoticeSeen()
            binding.noticeCard.visibility = View.GONE
        }

        binding.legendFirst.setOnClickListener { editProfile(leftWho()) }
        binding.legendSecond.setOnClickListener { editProfile(rightWho()) }
        binding.onlyPartnerRow.setOnClickListener { binding.onlyPartnerToggle.toggle() }
        binding.onlyPartnerToggle.onCheckedChange = { checked ->
            Prefs.coupleOnlyPartner = checked
            renderWeek(jumpToCurrent = false)
        }
        binding.prevWeek.setOnClickListener { binding.weekTimetable.previousWeek() }
        binding.nextWeek.setOnClickListener { binding.weekTimetable.nextWeek() }
        binding.weekTimetable.onWeekChanged = { week, isFirst, isLast -> updateWeekBar(week, isFirst, isLast) }
        binding.weekTimetable.onCourseClick = { click -> showWeekCourse(click) }
        binding.weekTimetable.onEventClick = { click -> showEvent(click.event, click.week, click.dayName) }
        binding.weekTimetable.setAppearance(Prefs.timetableAppearance)
        binding.weekContent.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            binding.weekTimetable.setViewportHeight(bottom - top)
        }

        binding.bindButton.setOnClickListener {
            startActivity(Intent(requireContext(), CoupleBindActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTicking()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        stopTicking()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopTicking()
    }

    override fun refresh() {
        if (_binding != null) load(sync = true)
    }

    // ===== 数据 =====

    private fun load(sync: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            readLocal()
            if (_binding == null) return@launch
            render()
            if (sync && !syncRunning && !ApiClient.isLocalMode) {
                syncRunning = true
                lastSyncOk = ApiClient.syncSchedules()
                syncRunning = false
                if (_binding == null) return@launch
                readLocal()
                render()
            }
            startTicking()
        }
    }

    private suspend fun readLocal() {
        state = ApiClient.coupleState()
        val activeId = Prefs.activeScheduleId
        mySchedule = ApiClient.listSchedules().find { it.id == activeId }
        myEvents = mySchedule?.let { schedule ->
            withContext(Dispatchers.IO) { PersonalEventStore.list(schedule.id) }
        } ?: emptyList()
        partnerSchedule = ApiClient.partnerSchedule()
    }

    /** 页面可见时每分钟一次：时间线和「上课中」跟着走，顺便拉一次情侣状态。 */
    private fun startTicking() {
        if (tickJob?.isActive == true || isHidden) return
        tickJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (_binding == null || isHidden) break
                if (!syncRunning) {
                    syncRunning = true
                    lastSyncOk = ApiClient.syncCoupleNow()
                    syncRunning = false
                }
                if (_binding == null) break
                readLocal()
                render()
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    // ===== 渲染 =====

    private fun meWho() = "me"
    private fun leftWho() = if (Prefs.coupleSwapSides) "partner" else "me"
    private fun rightWho() = if (Prefs.coupleSwapSides) "me" else "partner"
    private fun nameOf(who: String) = if (who == "me") state.myName else state.partnerName
    private fun colorsOf(who: String): CoupleColors =
        CoupleColors.of((if (who == "me") state.me else state.partner)?.color ?: "#7FAEE3")

    private fun render() {
        if (_binding == null) return
        if (!state.bound) {
            binding.dayMode.visibility = View.GONE
            binding.weekMode.visibility = View.GONE
            binding.unboundState.visibility = View.VISIBLE
            (activity as? MainActivity)?.syncCoupleTab()
            return
        }
        binding.unboundState.visibility = View.GONE
        binding.dayMode.visibility = if (mode == Mode.DAY) View.VISIBLE else View.GONE
        binding.weekMode.visibility = if (mode == Mode.WEEK) View.VISIBLE else View.GONE
        renderModeButtons()
        if (mode == Mode.DAY) renderDay() else renderWeek(jumpToCurrent = !weekShown)
    }

    private fun todayIso(): String = DateUtils.formatDate(Calendar.getInstance())

    private fun renderDay() {
        if (_binding == null || !state.bound) return
        val today = todayIso()
        val date = selectedDate ?: today
        val isToday = date == today
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val nowMinutes = if (isToday) now else null

        renderDateStrip(date, today)
        val base = mySchedule ?: partnerSchedule
        val month = date.substring(5, 7).toInt()
        val week = base?.let { CoupleDay.weekOf(it, date) }?.takeIf { it in 1..(base.totalWeeks) }
        binding.dateCaption.text = if (week != null) getString(R.string.couple_date_caption, month, week)
        else getString(R.string.couple_date_caption_no_week, month)
        binding.backToday.visibility = if (isToday) View.GONE else View.VISIBLE

        val myPlan = mySchedule?.let { CoupleDay.plan(it, myEvents, date) }
        val partnerPlan = partnerSchedule?.let { CoupleDay.plan(it, emptyList(), date) }
        val (dayStart, dayEnd) = CoupleDay.dayRange(base)

        val columns = mapOf(
            "me" to colorsOf("me").column(
                state.myName,
                myPlan?.items.orEmpty(),
                placeholder = if (mySchedule == null) getString(R.string.couple_no_my_schedule) else null
            ),
            "partner" to colorsOf("partner").column(
                state.partnerName,
                partnerPlan?.items.orEmpty(),
                placeholder = if (partnerSchedule == null) getString(R.string.couple_no_partner_schedule) else null
            )
        )
        val statuses = mapOf(
            "me" to if (mySchedule == null) getString(R.string.couple_no_my_schedule)
            else CoupleDay.statusText(myPlan, isToday, now),
            "partner" to if (partnerSchedule == null) getString(R.string.couple_no_partner_schedule)
            else CoupleDay.statusText(partnerPlan, isToday, now)
        )
        renderPerson(leftWho(), binding.leftName, binding.leftSwatch, binding.leftStatus, binding.leftPerson, statuses)
        renderPerson(rightWho(), binding.rightName, binding.rightSwatch, binding.rightStatus, binding.rightPerson, statuses)

        val bothKnown = mySchedule != null && partnerSchedule != null
        val gaps = if (bothKnown) CoupleDay.freeGaps(myPlan, partnerPlan, dayStart, dayEnd) else emptyList()
        val bothEmpty = bothKnown && myPlan?.items.isNullOrEmpty() && partnerPlan?.items.isNullOrEmpty()
        binding.dayView.setModel(
            CoupleDayView.Model(
                left = columns.getValue(leftWho()),
                right = columns.getValue(rightWho()),
                dayStart = dayStart,
                dayEnd = dayEnd,
                gaps = if (bothEmpty) emptyList() else gaps,
                highlightFree = Prefs.coupleHighlightFree,
                showFreeLabel = Prefs.coupleFreeLabel,
                nowMinutes = nowMinutes,
                showNowLine = Prefs.coupleShowNowLine,
                showFinished = Prefs.timetableAppearance.showFinished,
                emptyTitle = if (bothEmpty) getString(R.string.couple_both_empty_title) else null,
                emptyDesc = if (bothEmpty) getString(R.string.couple_both_empty_desc) else null
            )
        )

        val note = when {
            !lastSyncOk && ApiClient.coupleSyncedAt() > 0 ->
                getString(R.string.couple_stale_note, CoupleSettingsSheet.relativeTime(ApiClient.coupleSyncedAt()))
            state.sameSchedule -> getString(R.string.couple_same_schedule)
            else -> null
        }
        binding.dayNote.visibility = if (note != null) View.VISIBLE else View.GONE
        binding.dayNote.text = note.orEmpty()
        renderNotice()
    }

    private fun renderPerson(
        who: String,
        nameView: TextView,
        swatch: View,
        statusView: TextView,
        container: View,
        statuses: Map<String, String>
    ) {
        val name = nameOf(who)
        nameView.text = name
        swatch.showSwatch(colorsOf(who).fill)
        statusView.text = statuses[who].orEmpty()
        container.contentDescription = getString(R.string.couple_edit_desc, name) + "。" + statuses[who].orEmpty()
    }

    /** 本周七天：选中日深色底，今天字是主题绿。 */
    private fun renderDateStrip(selected: String, today: String) {
        val strip = binding.dateStrip
        strip.removeAllViews()
        val cal = Calendar.getInstance().apply {
            val parts = selected.split("-").map { it.toInt() }
            clear()
            set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
            // 回到这一周的周一
            add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        val accent = ContextCompat.getColor(requireContext(), R.color.tt_today_text)
        val normal = ContextCompat.getColor(requireContext(), R.color.action_ink)
        val sub = ContextCompat.getColor(requireContext(), R.color.tt_day_text)
        repeat(7) { index ->
            val iso = DateUtils.formatDate(cal)
            val isSelected = iso == selected
            val isToday = iso == today
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = Rpx.dp(2f)
                    marginEnd = Rpx.dp(2f)
                }
                if (isSelected) setBackgroundResource(R.drawable.bg_couple_date_selected)
                else setBackgroundResource(R.drawable.bg_menu_row)
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    R.string.couple_date_desc,
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH),
                    names[index]
                )
                setOnClickListener {
                    selectedDate = if (iso == todayIso()) null else iso
                    renderDay()
                }
            }
            cell.addView(TextView(requireContext()).apply {
                text = if (isToday) getString(R.string.couple_today) else names[index]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (isSelected) 0xb8ffffff.toInt() else if (isToday) accent else sub)
                gravity = Gravity.CENTER
            })
            cell.addView(TextView(requireContext()).apply {
                text = cal.get(Calendar.DAY_OF_MONTH).toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) 0xffffffff.toInt() else if (isToday) accent else normal)
                gravity = Gravity.CENTER
            })
            strip.addView(cell)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun shiftDay(direction: Int) {
        val cal = Calendar.getInstance().apply {
            val parts = (selectedDate ?: todayIso()).split("-").map { it.toInt() }
            clear()
            set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
            add(Calendar.DAY_OF_YEAR, direction)
        }
        val iso = DateUtils.formatDate(cal)
        selectedDate = if (iso == todayIso()) null else iso
        renderDay()
    }

    /** 「TA 把你的名字改成了…」：只在对方改了我的名字/颜色时提示一次。 */
    private fun renderNotice() {
        val text = ApiClient.coupleNoticeText()
        binding.noticeCard.visibility = if (text != null) View.VISIBLE else View.GONE
        if (text != null) {
            binding.noticeText.text = text
            binding.noticeHeart.setColorFilter(colorsOf(meWho()).ring)
        }
    }

    private fun renderWeek(jumpToCurrent: Boolean) {
        if (_binding == null || !state.bound) return
        weekShown = true
        val onlyPartner = Prefs.coupleOnlyPartner
        binding.onlyPartnerToggle.setChecked(onlyPartner, animate = false)
        renderLegend(leftWho(), binding.legendFirst, binding.legendFirstSwatch, binding.legendFirstName)
        renderLegend(rightWho(), binding.legendSecond, binding.legendSecondSwatch, binding.legendSecondName)
        // 只看 TA 时隐藏我的图例
        val meLegend = if (leftWho() == "me") binding.legendFirst else binding.legendSecond
        meLegend.visibility = if (onlyPartner) View.GONE else View.VISIBLE

        val base = mySchedule ?: partnerSchedule
        val me = colorsOf("me")
        val partner = colorsOf("partner")
        val timetable = binding.weekTimetable
        timetable.setCouple(
            TimetableView.CoupleLayer(
                partner = partnerSchedule,
                baseIsMine = mySchedule != null,
                myName = state.myName,
                partnerName = state.partnerName,
                myFill = me.fill,
                myInk = me.ink,
                partnerFill = partner.fill,
                partnerInk = partner.ink,
                swapped = Prefs.coupleSwapSides,
                showMine = !onlyPartner,
                highlightFree = Prefs.coupleHighlightFree,
                freeColor = ContextCompat.getColor(requireContext(), R.color.couple_free_band)
            )
        )
        timetable.setSchedule(
            base,
            events = if (mySchedule != null) myEvents else emptyList(),
            jumpToCurrent = jumpToCurrent,
            weekendMode = Prefs.weekendDisplayMode
        )
        if (!jumpToCurrent && base != null) timetable.goToWeek(currentWeek.coerceIn(1, base.totalWeeks))
    }

    private fun renderLegend(who: String, container: View, swatch: View, nameView: TextView) {
        val name = nameOf(who)
        swatch.showSwatch(colorsOf(who).fill)
        nameView.text = name
        container.contentDescription = getString(R.string.couple_edit_desc, name)
    }

    private fun updateWeekBar(week: Int, isFirst: Boolean, isLast: Boolean) {
        if (_binding == null) return
        currentWeek = week
        binding.weekTitle.text = getString(R.string.couple_week_title, week)
        val base = mySchedule ?: partnerSchedule
        val dates = base?.let { DateUtils.datesForWeek(it.semesterStart, week) }.orEmpty()
        binding.weekCaption.text = if (dates.isNotEmpty()) {
            "${dates.first().month}.${dates.first().day}–${dates.last().month}.${dates.last().day}"
        } else ""
        binding.prevIcon.setColorFilter(
            ContextCompat.getColor(requireContext(), if (isFirst) R.color.tt_chevron_off else R.color.tt_chevron)
        )
        binding.nextIcon.setColorFilter(
            ContextCompat.getColor(requireContext(), if (isLast) R.color.tt_chevron_off else R.color.tt_chevron)
        )
    }

    // ===== 页头：日 / 周 切换 + 设置 =====

    private fun buildHeaderActions() {
        val header = binding.header
        header.clearActions()
        val segment = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_couple_segment)
            val pad = Rpx.dp(3f)
            setPadding(pad, pad, pad, pad)
        }
        fun segmentButton(labelRes: Int, descRes: Int, target: Mode): TextView = TextView(requireContext()).apply {
            text = getString(labelRes)
            contentDescription = getString(descRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(Rpx.dp(42f), Rpx.dp(30f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (mode == target) return@setOnClickListener
                mode = target
                render()
            }
        }
        dayButton = segmentButton(R.string.couple_view_day, R.string.couple_view_day_desc, Mode.DAY)
        weekButton = segmentButton(R.string.couple_view_week, R.string.couple_view_week_desc, Mode.WEEK)
        segment.addView(dayButton)
        segment.addView(weekButton)
        header.addAction(segment)

        header.addAction(AppCompatImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_more)
            val borderless = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderless, true)
            setBackgroundResource(borderless.resourceId)
            val pad = Rpx.dp(12f)
            setPadding(pad, pad, pad, pad)
            minimumWidth = Rpx.dp(44f)
            minimumHeight = Rpx.dp(44f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.couple_settings_action)
            setOnClickListener { showSettings() }
        })
        renderModeButtons()
    }

    private fun renderModeButtons() {
        val context = context ?: return
        val selectedColor = ContextCompat.getColor(context, R.color.title_ink)
        val normalColor = ContextCompat.getColor(context, R.color.field_label)
        listOf(dayButton to Mode.DAY, weekButton to Mode.WEEK).forEach { (button, target) ->
            val selected = mode == target
            button?.apply {
                if (selected) setBackgroundResource(R.drawable.bg_couple_segment_selected) else background = null
                setTextColor(if (selected) selectedColor else normalColor)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isSelected = selected
            }
        }
    }

    // ===== 交互 =====

    private fun editProfile(who: String) {
        if (!state.bound) return
        CoupleProfileSheet(requireContext(), who, state) { nickname, color ->
            viewLifecycleOwner.lifecycleScope.launch {
                ApiClient.updateCoupleMember(who, nickname, color)
                if (_binding == null) return@launch
                readLocal()
                render()
                Ui.toast(requireContext(), getString(R.string.couple_profile_saved))
            }
        }.show()
    }

    private fun showSettings() {
        if (!state.bound) return
        CoupleSettingsSheet(
            requireContext(),
            state,
            currentScheduleName = mySchedule?.name,
            syncedAt = ApiClient.coupleSyncedAt(),
            onDisplayChanged = { render() },
            onEditProfile = { editProfile("partner") },
            onUnbind = { confirmUnbind() }
        ).show()
    }

    private fun confirmUnbind() {
        Ui.confirm(
            requireContext(),
            getString(R.string.couple_unbind_title),
            getString(R.string.couple_unbind_content),
            confirmText = getString(R.string.couple_unbind),
            confirmColor = ContextCompat.getColor(requireContext(), R.color.danger_confirm)
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    ApiClient.unbindCouple()
                    Ui.toast(requireContext(), getString(R.string.couple_unbound_toast))
                    (activity as? MainActivity)?.syncCoupleTab()
                } catch (error: Exception) {
                    Ui.toast(requireContext(), error.message ?: getString(R.string.common_load_failed))
                }
            }
        }
    }

    /** 日视图点块：课看详情（只读），我的日程可以编辑、删除。 */
    private fun showItem(who: String, item: CoupleDay.Item) {
        val date = selectedDate ?: todayIso()
        val schedule = (if (who == "me") mySchedule else partnerSchedule) ?: return
        val plan = CoupleDay.plan(schedule, emptyList(), date) ?: return
        val dayName = "周" + listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(plan.day - 1) { "" }
        if (item.kind == CoupleDay.Kind.EVENT) {
            item.event?.let { showEvent(it, plan.week, dayName) }
            return
        }
        val course = item.course ?: return
        val click = TimetableView.CourseClick(
            course = course,
            week = plan.week,
            adjustment = null,
            occurrence = TimetableView.Occurrence.NORMAL,
            orphaned = false,
            backgroundColor = colorsOf(who).fill,
            dayName = dayName,
            timeText = "${ScheduleTime.formatTime(item.startMinutes)}–${ScheduleTime.formatTime(item.endMinutes)}",
            weekSummary = ScheduleView.formatWeekSummary(course.weeks),
            holiday = if (item.suspended) schedule.holidays.find { it.week == plan.week && it.day == plan.day } else null,
            partner = who != "me"
        )
        showCourseDetail(click)
    }

    private fun showWeekCourse(click: TimetableView.CourseClick) = showCourseDetail(click)

    private fun showCourseDetail(click: TimetableView.CourseClick) {
        CourseDetailSheet(
            requireContext(),
            click,
            canEdit = false,
            readOnlyNote = if (click.partner) getString(R.string.couple_detail_partner_readonly, state.partnerName)
            else getString(R.string.couple_detail_mine_hint)
        ).show()
    }

    private fun showEvent(event: PersonalEvent, week: Int, dayName: String) {
        val schedule = mySchedule ?: return
        EventDetailSheet(
            requireContext(),
            data = TimetableView.EventClick(event, week, dayName, colorsOf("me").fill),
            slots = ScheduleTime.slotsOf(schedule.timeSlots),
            onEdit = {
                startActivity(
                    EventEditorActivity.intent(requireContext(), schedule.id, event.id, week, event.day, event.startSection)
                )
            },
            onDelete = {
                Ui.confirm(
                    requireContext(),
                    getString(R.string.event_delete_title),
                    getString(R.string.event_delete_content),
                    confirmText = getString(R.string.event_delete),
                    confirmColor = 1
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { PersonalEventStore.delete(schedule.id, event.id) }
                        load(sync = false)
                        Ui.toast(requireContext(), getString(R.string.event_deleted))
                    }
                }
            }
        ).show()
    }

    companion object {
        private const val TICK_MS = 60_000L
    }
}
