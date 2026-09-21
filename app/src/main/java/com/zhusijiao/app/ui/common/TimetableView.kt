package com.zhusijiao.app.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.CourseAdjustment
import com.zhusijiao.app.domain.DayHoliday
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleTime
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.TimeSlot
import com.zhusijiao.app.domain.TimetableAppearance
import com.zhusijiao.app.domain.WeekendDisplay
import com.zhusijiao.app.domain.WeekendDisplayMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 周课表视图：
 * 顶部星期/日期（今天深色圆角标记）、左侧节次与上下课时间、课程块（圆角、配色、单双周角标、课程名/教师/地点）。
 * 整周一屏、列宽以 750 设计稿宽度为基准按视图宽度等比换算。
 *
 * 行高、课程块留白与字号由 [TimetableAppearance] 决定（见 [setAppearance]），列宽不可调：
 * 整周一屏是硬约束，列宽恒为「视图宽度减时间列后平分给各天」。
 *
 * 周次切换采用「连续横向分页」：相邻周作为独立页面并排绘制，跟随手指整体平移，
 * 松手后平滑吸附到最近一页（类似拾光课程表的连贯翻页），而非淡入淡出闪现。
 */
class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class CourseClick(
        val course: Course,
        val week: Int,
        val adjustment: CourseAdjustment?,
        val occurrence: Occurrence,
        val orphaned: Boolean,
        val backgroundColor: Int,
        val dayName: String,
        val timeText: String,
        val weekSummary: String,
        val holiday: DayHoliday? = null,
        val makeup: DayMakeup? = null,
        val madeUpNote: String? = null
    )

    /** 点击表头某一天（用于查看/设置停课与补课）。 */
    data class DayClick(
        val week: Int,
        val day: Int,
        val date: DateUtils.DayInfo,
        val holiday: DayHoliday?,
        val makeup: DayMakeup?
    )

    /** 点击自定义日程块（本机私有安排，订阅课表同样可编辑）。 */
    data class EventClick(
        val event: PersonalEvent,
        val week: Int,
        val dayName: String,
        val backgroundColor: Int
    )

    enum class Occurrence { NORMAL, MOVED_IN, MOVED_OUT }

    var onCourseClick: ((CourseClick) -> Unit)? = null

    /** 点击日程块：查看/编辑/删除。 */
    var onEventClick: ((EventClick) -> Unit)? = null

    /** 点击或长按空格子：在该时段新建日程，参数为 (周次, 星期, 节次)。 */
    var onEmptySlotClick: ((week: Int, day: Int, section: Int) -> Unit)? = null

    /** 长按课程块：手动选择课程颜色（仅发布者）。 */
    var onCourseLongClick: ((CourseClick) -> Unit)? = null
    var onDayClick: ((DayClick) -> Unit)? = null
    var onWeekChanged: ((week: Int, isFirst: Boolean, isLast: Boolean) -> Unit)? = null

    private var schedule: Schedule? = null
    private var events: List<PersonalEvent> = emptyList()
    private var paletteMap: Map<String, ScheduleView.Palette> = emptyMap()
    private var week = 1
    private var currentWeekNumber = 1
    private var weekendDisplayMode = WeekendDisplayMode.AUTO

    private var dayCount = WEEKDAY_COUNT
    private var dense = false
    private var sectionsList: List<TimeSlot> = emptyList()

    // 本机外观偏好（格子高度/留白/文字大小、铺满一屏），不随课表同步
    private var appearance = TimetableAppearance.DEFAULT
    /** 课表在页面里的可视高度（px），由外层滚动容器告知，只有「铺满一屏」用得到。 */
    private var viewportHeightPx = 0

    // 分页状态：pageOffset 为当前页的水平平移量；secondaryWeek 为并排显示的相邻页
    private var pageOffset = 0f
    private var secondaryWeek: Int? = null
    private var offsetAnimator: ValueAnimator? = null

    // 每周渲染缓存（日期、课程块及其文本排版）
    private val weekCache = object : LinkedHashMap<Int, WeekRender>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WeekRender>?) = size > 6
    }

    // 像素几何
    private var unit = 1f
    private var timeColPx = 0f
    private var dayColPx = 0f
    private var rowHeightPx = 0f
    private var headerHeightPx = 0f
    private var bodyHeightPx = 0f

    private class WeekRender(val week: Int) {
        var dates: List<DateUtils.DayInfo> = emptyList()
        var monthLabel = ""
        var marks: List<Int> = emptyList()
        val blocks = mutableListOf<Block>()
    }

    private class Block(
        val course: Course,
        val background: Int,
        val foreground: Int,
        val badge: String,
        val compact: Boolean,
        val col: Int,
        val startSection: Int,
        val span: Int,
        val adjustment: CourseAdjustment? = null,
        val occurrence: Occurrence = Occurrence.NORMAL,
        val displayNote: String? = null,
        val orphaned: Boolean = false,
        val holiday: DayHoliday? = null,
        val makeup: DayMakeup? = null,
        val madeUpNote: String? = null,
        val event: PersonalEvent? = null
    ) {
        var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
        var nameLayout: StaticLayout? = null
        var teacherLayout: StaticLayout? = null
        var placeLayout: StaticLayout? = null
        var badgeWidth = 0f
        /** 同格分栏时本块所在的栏（0 左 / 1 右）与该格的总栏数（1 表示独占整格）。 */
        var column = 0
        var columnCount = 1

        /** 「鬼影」块：已调出、整日停课、补课来源日——当天都不上，不参与冲突分栏。 */
        val ghosted: Boolean
            get() = occurrence == Occurrence.MOVED_OUT || holiday != null || madeUpNote != null
    }

    // ===== 画笔 =====
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dayNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val timeNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val timeClockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val teacherPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val placePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 分栏块（日程与课同格时左右等分）专用画笔：字号降一档。
    // 必须与常规块分开——StaticLayout 持有 TextPaint 引用，排版与绘制得用同一支笔，
    // 若在循环里反复改同一支笔的字号，先排好的块会被按最后一次的字号画出来。
    private val splitNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val splitTeacherPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val splitPlacePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val c = { id: Int -> ContextCompat.getColor(context, id) }
    private val colBgWhite = c(R.color.surface)
    private val colCornerBg = c(R.color.tt_corner_bg)
    private val colCornerBorder = c(R.color.tt_corner_border)
    private val colHeadBorder = c(R.color.tt_head_border)
    private val colDayText = c(R.color.tt_day_text)
    private val colTodayText = c(R.color.tt_today_text)
    private val colTodayBg = c(R.color.tt_today_bg)
    private val colSectionBg = c(R.color.tt_section_bg)
    private val colTimeBorder = c(R.color.tt_border)
    private val colTimeNumber = c(R.color.tt_time_number)
    private val colTimeClock = c(R.color.tt_time_clock)
    private val colGridV = c(R.color.tt_grid_v)
    private val colGridH = c(R.color.tt_grid_h)
    private val colEmpty = c(R.color.sub_a0)
    private val colBadgeBg = c(R.color.tt_badge_bg)
    // 默认日程色：冷灰蓝，刻意与 20 色课程色板拉开，一眼能分出「这不是课」
    private val colEventBg = Color.rgb(74, 90, 114)
    private val colEventText = Color.WHITE
    private val colSuspendedBg = Color.rgb(238, 240, 243)
    private val colSuspendedText = Color.rgb(105, 112, 124)
    private val colMarkMakeupText = c(R.color.tt_mark_makeup_text)
    private val colMarkMakeupBg = c(R.color.tt_mark_makeup_bg)
    private val colMarkOffText = c(R.color.tt_mark_off_text)
    private val colMarkOffBg = c(R.color.tt_mark_off_bg)

    // 手势
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    // 接近拾光 HorizontalPager 的轻扫手感：短距离甩动也翻页，慢拖约 12% 即提交。
    private val flingThresholdPx = 300f * resources.displayMetrics.density

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        /** 表头某一天对应的虚拟视图 id：1000 + day - 1（课程块沿用 0..n-1）。 */
        private fun headerDayAt(x: Float): Int? {
            if (dayColPx <= 0f || x <= timeColPx || x >= width) return null
            val day = ((x - timeColPx) / dayColPx).toInt() + 1
            return day.takeIf { it in 1..dayCount }
        }

        private fun headerDescription(day: Int): String {
            val date = renderFor(week).dates.getOrNull(day - 1) ?: return "第 $day 列"
            val s = schedule ?: return "${date.month}月${date.day}日 周${date.name}"
            val makeup = s.makeups.find { it.targetWeek == week && it.targetDay == day }
            val madeUp = s.makeups.find { it.sourceWeek == week && it.sourceDay == day }
            val holiday = s.holidays.find { it.week == week && it.day == day }
            val status = when {
                makeup != null -> "，补第${makeup.sourceWeek}周周${dayName(makeup.sourceDay)}的课"
                madeUp != null -> "，当天课程已补到${madeUpDateText(s, madeUp)}（周${dayName(madeUp.targetDay)}）"
                holiday != null -> "，停课"
                else -> ""
            }
            return "${date.month}月${date.day}日 周${date.name}$status"
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (y <= headerHeightPx) {
                headerDayAt(x)?.let { return DAY_VIEW_BASE + it - 1 }
            }
            val blocks = renderFor(week).blocks
            return blocks.indexOfLast { x in it.left..it.right && y in it.top..it.bottom }
                .takeIf { it >= 0 } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            renderFor(week).blocks.indices.forEach(virtualViewIds::add)
            for (day in 1..dayCount) virtualViewIds.add(DAY_VIEW_BASE + day - 1)
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            event.contentDescription = when {
                virtualViewId >= DAY_VIEW_BASE -> headerDescription(virtualViewId - DAY_VIEW_BASE + 1)
                else -> renderFor(week).blocks.getOrNull(virtualViewId)?.let { blockDescription(it) }
            }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            if (virtualViewId >= DAY_VIEW_BASE) {
                val day = virtualViewId - DAY_VIEW_BASE + 1
                val left = timeColPx + (day - 1) * dayColPx
                node.contentDescription = headerDescription(day)
                node.className = android.widget.Button::class.java.name
                node.isClickable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                node.setBoundsInParent(Rect(left.toInt(), 0, (left + dayColPx).toInt(), headerHeightPx.toInt()))
                return
            }
            val block = renderFor(week).blocks.getOrNull(virtualViewId) ?: return
            node.contentDescription = blockDescription(block)
            node.className = android.widget.Button::class.java.name
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(Rect(block.left.toInt(), block.top.toInt(), block.right.toInt(), block.bottom.toInt()))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (virtualViewId >= DAY_VIEW_BASE) {
                openDay(virtualViewId - DAY_VIEW_BASE + 1)
                return true
            }
            val block = renderFor(week).blocks.getOrNull(virtualViewId) ?: return false
            openBlock(renderFor(week), block)
            return true
        }
    }

    init {
        setBackgroundColor(colBgWhite)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    // ===== 对外接口 =====

    fun setSchedule(
        schedule: Schedule?,
        events: List<PersonalEvent> = emptyList(),
        jumpToCurrent: Boolean = true,
        weekendMode: WeekendDisplayMode = WeekendDisplayMode.AUTO
    ) {
        this.schedule = schedule
        this.events = events
        weekendDisplayMode = weekendMode
        pageOffset = 0f
        secondaryWeek = null
        offsetAnimator?.cancel()
        weekCache.clear()
        accessibilityHelper.invalidateRoot()
        if (schedule == null) {
            invalidate(); return
        }
        // 调课快照中的课程名也纳入配色，孤儿课（原课程已删除）不会回退到他人颜色
        paletteMap = ScheduleView.buildCoursePaletteMap(
            schedule.courses + schedule.adjustments.map { it.courseSnapshot },
            schedule.courseColors
        )
        sectionsList = visibleSections(schedule, events)
        currentWeekNumber = DateUtils.currentWeek(schedule.semesterStart, schedule.totalWeeks)
        if (jumpToCurrent) week = currentWeekNumber
        week = week.coerceIn(1, max(1, schedule.totalWeeks))
        dayCount = WeekendDisplay.dayCount(schedule, week, weekendDisplayMode, events)
        dense = dayCount > WEEKDAY_COUNT
        renderFor(week)
        requestLayout()
        invalidate()
        notifyWeek()
    }

    /** 切换周末显示模式，不改变当前周次。 */
    fun setWeekendDisplayMode(mode: WeekendDisplayMode) {
        weekendDisplayMode = mode
        if (applyDayCountForWeek(week)) renderFor(week)
        invalidate()
    }

    /** 应用本机课表外观偏好：立刻重新测量、重排已缓存的各周并重绘，不改变当前周次。 */
    fun setAppearance(value: TimetableAppearance) {
        if (value == appearance) return
        appearance = value
        refreshMetrics()
    }

    /** 当前行高（px），供外层在改档位后按比例换算滚动位置，保持用户正在看的节次不跳。 */
    fun currentRowHeightPx(): Float = rowHeightPx

    /**
     * 由外层滚动容器告知课表的可视高度（px），供「铺满一屏」平分行高。
     * 容器高度变化（如系统字体缩放、分屏）时再调一次即可。
     */
    fun setViewportHeight(px: Int) {
        if (px == viewportHeightPx) return
        viewportHeightPx = px
        if (appearance.fitScreen) refreshMetrics()
    }

    /** 几何参数变化后的统一收尾：重算尺寸、重排缓存、刷新无障碍节点。 */
    private fun refreshMetrics() {
        if (width > 0) {
            applyMetrics(width)
            weekCache.values.forEach { layoutBlocks(it) }
        }
        accessibilityHelper.invalidateRoot()
        requestLayout()
        invalidate()
    }

    fun goToWeek(target: Int) {
        val s = schedule ?: return
        val clamped = target.coerceIn(1, max(1, s.totalWeeks))
        if (clamped == week) return
        jumpTo(clamped)
    }

    fun previousWeek() {
        if (week > 1) jumpTo(week - 1) else edgeBounce(-1)
    }

    fun nextWeek() {
        val s = schedule ?: return
        if (week < s.totalWeeks) jumpTo(week + 1) else edgeBounce(1)
    }

    // ===== 分页动画 =====

    /** 非拖拽式跳转（箭头 / 周次面板）：旧页作为相邻页一起滑出，新页滑入，保持连贯。 */
    private fun jumpTo(target: Int) {
        val old = week
        offsetAnimator?.cancel()
        applyDayCountForWeek(target)
        secondaryWeek = old
        week = target
        val w = width.toFloat()
        pageOffset = if (target > old) w else -w
        invalidate()
        notifyWeek()
        animateOffsetTo(0f, 240) {
            secondaryWeek = null
            invalidate()
        }
    }

    private fun edgeBounce(direction: Int) {
        val shift = (if (direction > 0) -1 else 1) * width * 0.06f
        offsetAnimator?.cancel()
        animateOffsetTo(shift, 110) { animateOffsetTo(0f, 170) }
    }

    private fun settleAfterDrag(velocityX: Float) {
        val w = width.toFloat()
        val distanceCommit = min(w * 0.12f, 48f * resources.displayMetrics.density)
        val sec = secondaryWeek
        val flingNext = velocityX < -flingThresholdPx
        val flingPrev = velocityX > flingThresholdPx
        val commitNext = sec != null && sec > week && (pageOffset <= -distanceCommit || flingNext)
        val commitPrev = sec != null && sec < week && (pageOffset >= distanceCommit || flingPrev)
        if (sec != null && (commitNext || commitPrev)) {
            val targetWeek = sec
            val target = if (commitNext) -w else w
            applyDayCountForWeek(targetWeek)
            animateOffsetTo(target, 170) {
                week = targetWeek
                secondaryWeek = null
                pageOffset = 0f
                notifyWeek()
                invalidate()
            }
        } else {
            animateOffsetTo(0f, 150) {
                secondaryWeek = null
                invalidate()
            }
        }
    }

    private fun animateOffsetTo(target: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        offsetAnimator?.cancel()
        offsetAnimator = ValueAnimator.ofFloat(pageOffset, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { pageOffset = it.animatedValue as Float; invalidate() }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { onEnd() }
                })
            }
            start()
        }
    }

    private fun notifyWeek() {
        val s = schedule ?: return
        onWeekChanged?.invoke(week, week <= 1, week >= s.totalWeeks)
        accessibilityHelper.invalidateRoot()
    }

    /** 自动模式下切周可能在 5/7 列之间变化；列数变化时统一清空与列宽有关的缓存。 */
    private fun applyDayCountForWeek(targetWeek: Int): Boolean {
        val s = schedule ?: return false
        val target = WeekendDisplay.dayCount(s, targetWeek, weekendDisplayMode, events)
        if (target == dayCount) return false
        dayCount = target
        dense = dayCount > WEEKDAY_COUNT
        weekCache.clear()
        accessibilityHelper.invalidateRoot()
        requestLayout()
        return true
    }

    // ===== 每周渲染缓存 =====

    private fun renderFor(w: Int): WeekRender = weekCache.getOrPut(w) { buildRender(w) }

    private fun buildRender(w: Int): WeekRender {
        val s = schedule ?: return WeekRender(w)
        val r = WeekRender(w)
        val all = DateUtils.datesForWeek(s.semesterStart, w)
        r.dates = all.take(dayCount)
        r.monthLabel = if (r.dates.isNotEmpty()) "${r.dates[0].month}月" else ""
        val movedOut = s.adjustments.associateBy { it.courseId to it.sourceWeek }
        val holidayByDay = s.holidays.filter { it.week == w }.associateBy { it.day }
        // 双向关联：来源日（课被移走的那天）也要标出「已补」并指向补课日
        val madeUpByDay = s.makeups
            .filter { it.sourceWeek == w }
            .sortedWith(compareBy({ it.targetWeek }, { it.targetDay }))
            .associateBy { it.sourceDay }
        r.marks = (1..dayCount).map { day ->
            when {
                s.makeups.any { it.targetWeek == w && it.targetDay == day } -> MARK_MAKEUP
                madeUpByDay.containsKey(day) -> MARK_MADE_UP
                holidayByDay.containsKey(day) -> MARK_OFF
                else -> 0
            }
        }
        s.courses.forEach { course ->
            if (!course.weeks.contains(w) || course.day > dayCount) return@forEach
            val palette = paletteMap[course.name] ?: ScheduleView.COURSE_PALETTES[0]
            val span = course.endSection - course.startSection + 1
            val adjustment = movedOut[course.id to w]
            val holiday = holidayByDay[course.day]
            val madeUp = madeUpByDay[course.day]
            // 已调出的单课 / 补课来源日 / 整日停课都用「鬼影」样式：不再是正常的课上。
            val ghosted = adjustment != null || holiday != null || madeUp != null
            val madeUpDate = madeUp?.let { madeUpDateText(s, it) }
            r.blocks += Block(
                course = course,
                background = if (ghosted) colSuspendedBg else palette.background,
                foreground = if (ghosted) colSuspendedText else palette.foreground,
                badge = when {
                    adjustment != null -> "已调出"
                    madeUp != null -> "已补"
                    holiday != null -> "停课"
                    else -> ScheduleView.alternatingWeekBadge(course.weeks)
                },
                compact = span == 1,
                col = course.day,
                startSection = course.startSection,
                span = span,
                adjustment = adjustment,
                occurrence = if (adjustment == null) Occurrence.NORMAL else Occurrence.MOVED_OUT,
                displayNote = adjustment?.let {
                    "→ 第 ${it.targetWeek} 周周${dayName(it.targetDay)} ${it.targetStartSection}–${it.targetEndSection} 节"
                } ?: madeUp?.let { "→ ${madeUpDate}（周${dayName(it.targetDay)}）补课" },
                holiday = holiday.takeIf { madeUp == null },
                madeUpNote = madeUp?.let {
                    "该日课表已整体调整到 $madeUpDate（周${dayName(it.targetDay)}）补课，当天不上课。"
                }
            )
        }
        s.adjustments.filter { it.targetWeek == w && it.targetDay <= dayCount }.forEach { adjustment ->
            val base = s.courses.find { it.id == adjustment.courseId }
            val course = adjustment.targetCourse(base)
            val palette = paletteMap[course.name] ?: ScheduleView.COURSE_PALETTES[0]
            r.blocks += Block(
                course = course,
                background = palette.background,
                foreground = palette.foreground,
                badge = "调课",
                compact = course.startSection == course.endSection,
                col = course.day,
                startSection = course.startSection,
                span = course.endSection - course.startSection + 1,
                adjustment = adjustment,
                occurrence = Occurrence.MOVED_IN,
                orphaned = base == null
            )
        }
        // 补课日：在目标日完整补上来源日当天的课（已被手动调离来源日的单课除外）。
        s.makeups.filter { it.targetWeek == w && it.targetDay <= dayCount }.forEach { makeup ->
            s.courses.forEach { course ->
                if (!course.weeks.contains(makeup.sourceWeek) || course.day != makeup.sourceDay ||
                    course.day > dayCount || movedOut.containsKey(course.id to makeup.sourceWeek)
                ) return@forEach
                val palette = paletteMap[course.name] ?: ScheduleView.COURSE_PALETTES[0]
                r.blocks += Block(
                    course = course,
                    background = palette.background,
                    foreground = palette.foreground,
                    badge = "补课",
                    compact = course.startSection == course.endSection,
                    col = makeup.targetDay,
                    startSection = course.startSection,
                    span = course.endSection - course.startSection + 1,
                    makeup = makeup
                )
            }
            s.adjustments.filter {
                it.targetWeek == makeup.sourceWeek && it.targetDay == makeup.sourceDay
            }.forEach { adjustment ->
                val base = s.courses.find { it.id == adjustment.courseId }
                val course = adjustment.targetCourse(base)
                if (course.day > dayCount) return@forEach
                val palette = paletteMap[course.name] ?: ScheduleView.COURSE_PALETTES[0]
                r.blocks += Block(
                    course = course,
                    background = palette.background,
                    foreground = palette.foreground,
                    badge = "补课",
                    compact = course.startSection == course.endSection,
                    col = makeup.targetDay,
                    startSection = course.startSection,
                    span = course.endSection - course.startSection + 1,
                    makeup = makeup,
                    orphaned = base == null
                )
            }
        }
        // 自定义日程：本机私有安排，与课程重叠时由 layoutBlocks 左右分栏（左课右程）。
        // 停课/补课不影响日程——停的是课，社团活动照常，所以这里不做任何鬼影处理。
        events.forEach { event ->
            if (!event.occursIn(w) || event.day > dayCount) return@forEach
            val manual = ScheduleView.manualPalette(event.color)
            r.blocks += Block(
                course = Course(
                    id = event.id,
                    name = event.title,
                    // 复用课程块的「教师」槽位放自定义时间行，「地点」槽位仍放地点
                    teacher = event.blockTimeText(sectionsList).orEmpty(),
                    position = event.position,
                    day = event.day,
                    startSection = event.startSection,
                    endSection = event.endSection,
                    weeks = event.weeks
                ),
                background = manual?.background ?: colEventBg,
                foreground = manual?.foreground ?: colEventText,
                badge = context.getString(R.string.event_badge),
                compact = event.span == 1,
                col = event.day,
                startSection = event.startSection,
                span = event.span,
                event = event
            )
        }
        layoutBlocks(r)
        return r
    }

    // ===== 测量与几何 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        applyMetrics(if (width > 0) width else resources.displayMetrics.widthPixels)
        setMeasuredDimension(width, (headerHeightPx + bodyHeightPx).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            applyMetrics(w)
            weekCache.values.forEach { layoutBlocks(it) }
        }
    }

    /** 按视图宽度算出等比单位、表头高度、行高与表体高度。 */
    private fun applyMetrics(widthPx: Int) {
        unit = widthPx / 750f
        headerHeightPx = rpx(HEADER_HEIGHT_RPX)
        val rows = if (sectionsList.isNotEmpty()) sectionsList.size else MIN_SECTIONS
        rowHeightPx = rpx(rowHeightRpx(rows))
        bodyHeightPx = rows * rowHeightPx
    }

    /**
     * 当前行高（设计稿单位）：开启「铺满一屏」时把可视高度减去表头后平分给各节次，
     * 拿不到可视高度（容器还没布局）时退回高度档位，不会出现 0 高度的空白课表。
     */
    private fun rowHeightRpx(rows: Int): Float {
        if (!appearance.fitScreen || viewportHeightPx <= 0 || unit <= 0f) return appearance.rowHeightRpx
        return TimetableAppearance.fitRowHeightRpx(viewportHeightPx / unit - HEADER_HEIGHT_RPX, rows)
    }

    private fun rpx(v: Float) = v * unit

    /** 课程块四周留白：越大块越瘦，格线露出越多。 */
    private fun blockMarginPx() = rpx(appearance.blockMarginRpx)

    /** 课程块内左右、顶部内边距；七列时收窄一档，分栏块再减半把宽度让给文字。 */
    private fun blockPadH(split: Boolean = false): Float {
        val base = if (dense) rpx(7f) else rpx(10f)
        return if (split) base * 0.5f else base
    }

    private fun blockPadTop(split: Boolean = false): Float {
        val base = if (dense) rpx(9f) else rpx(11f)
        return if (split) base * 0.8f else base
    }

    /** 单双周角标行高；字号随文字档位缩放，排版与绘制共用同一份计算。 */
    private fun badgeHeightPx() = badgePaint.textSize * 1.3f + rpx(4f)

    /**
     * 分栏：日程与课程在同一格重叠时左右等分（左课右程），中间留 [SPLIT_GAP_RPX] 的缝。
     *
     * 只有「至少一条是日程」的重叠才分栏——纯课程之间的重叠是既有行为（后画的盖前面），
     * 不该被这个功能改变。鬼影块（停课/已调出/补课来源日）当天不上课，不算冲突。
     * 同一时段最多允许一条日程（见 PersonalEventStore），所以右栏恒为一块。
     */
    private fun assignColumns(blocks: List<Block>) {
        blocks.forEach { it.column = 0; it.columnCount = 1 }
        blocks.forEach { event ->
            if (event.event == null) return@forEach
            val eventEnd = event.startSection + event.span - 1
            val clashes = blocks.filter { other ->
                other.event == null && !other.ghosted && other.col == event.col &&
                    other.startSection <= eventEnd &&
                    event.startSection <= other.startSection + other.span - 1
            }
            if (clashes.isEmpty()) return@forEach
            event.column = 1
            event.columnCount = 2
            clashes.forEach { it.column = 0; it.columnCount = 2 }
        }
    }

    private fun layoutBlocks(render: WeekRender) {
        if (width <= 0) return
        timeColPx = rpx(timeColumnRpx(dayCount))
        dayColPx = (width - timeColPx) / dayCount
        // 文字档位（小/标准/大）统一缩放四个字号；七列的降档系数保留，两者相乘
        val scale = appearance.textScale
        namePaint.textSize = rpx((if (dense) 21f else 24f) * scale)
        teacherPaint.textSize = rpx((if (dense) 18f else 20f) * scale)
        // 教室名字号略小于教师名，同拾光课程表对齐：同一行能容纳更多字，减少换行
        placePaint.textSize = rpx((if (dense) 15f else 17f) * scale)
        badgePaint.textSize = rpx(BADGE_TEXT_RPX * scale)
        // 分栏块只有半格宽，字号统一降到七列档；七列本就窄，再乘一档收缩系数
        val splitScale = scale * if (dense) SPLIT_DENSE_SCALE else 1f
        splitNamePaint.textSize = rpx(21f * splitScale)
        splitTeacherPaint.textSize = rpx(18f * splitScale)
        splitPlacePaint.textSize = rpx(15f * splitScale)

        assignColumns(render.blocks)
        val margin = blockMarginPx()
        val gap = rpx(SPLIT_GAP_RPX)
        render.blocks.forEach { b ->
            val split = b.columnCount > 1
            val colLeft = timeColPx + (b.col - 1) * dayColPx
            if (split) {
                val each = max(1f, (dayColPx - margin * 2f - gap) / 2f)
                b.left = colLeft + margin + b.column * (each + gap)
                b.right = b.left + each
            } else {
                b.left = colLeft + margin
                b.right = colLeft + dayColPx - margin
            }
            b.top = headerHeightPx + (b.startSection - 1) * rowHeightPx + margin
            b.bottom = headerHeightPx + (b.startSection - 1 + b.span) * rowHeightPx - margin
            val padH = blockPadH(split)
            val padTop = blockPadTop(split)
            val contentWidth = max(1, (b.right - b.left - padH * 2).roundToInt())
            // 分栏块窄到放不下角标（七列时内容宽只剩约 35rpx），一律省掉只留标题；
            // 教师、地点与「调课/补课」状态都在详情抽屉里给全。
            b.badgeWidth = if (!split && b.badge.isNotEmpty() && !b.compact) {
                badgePaint.measureText(b.badge) + rpx(16f)
            } else {
                0f
            }
            // 块内可用高度 = 课程块高度 - 上下留白 - 徽标行（与 drawBlocks 的徽标占位一致）。
            // 文本行数按剩余高度动态分配：高度足够时完整显示教室名，空间不足才省略，
            // 修复连续多节课时教室名被省略号吞掉、短课时文字被块底边截断的问题。
            val badgeUsed = if (b.badgeWidth > 0f) badgeHeightPx() + rpx(6f) else 0f
            var availH = (b.bottom - b.top) - padTop * 2f - badgeUsed
            val nameLayout = fitLayout(
                b.course.name,
                if (split) splitNamePaint else namePaint,
                contentWidth,
                if (b.compact) 2 else 4,
                1.2f,
                availH
            )
            b.nameLayout = nameLayout
            availH -= nameLayout.height
            val secondary = b.displayNote ?: b.course.teacher
            if (secondary.isNotBlank()) {
                availH -= if (b.compact) rpx(4f) else rpx(8f)
                val teacherLayout = fitLayout(
                    secondary,
                    if (split) splitTeacherPaint else teacherPaint,
                    contentWidth,
                    if (b.compact) 1 else 2,
                    1.25f,
                    availH
                )
                b.teacherLayout = teacherLayout
                availH -= teacherLayout.height
            } else {
                b.teacherLayout = null
            }
            if (b.displayNote == null && !b.compact && b.course.position.isNotBlank()) {
                availH -= rpx(4f)
                b.placeLayout = fitLayout(
                    b.course.position,
                    if (split) splitPlacePaint else placePaint,
                    contentWidth,
                    PLACE_MAX_LINES,
                    1.25f,
                    availH
                )
            } else {
                b.placeLayout = null
            }
        }
    }

    /**
     * 先按 [maxLines] 排版；若总高度超过 [availPx] 则逐行收缩，
     * 保证文字完整落在课程块内，只有空间实在不足时才会被省略号截断。
     */
    private fun fitLayout(text: String, paint: TextPaint, widthPx: Int, maxLines: Int, lineMul: Float, availPx: Float): StaticLayout {
        var lines = maxLines
        var layout = buildLayout(text, paint, widthPx, lines, lineMul)
        while (lines > 1 && layout.height > availPx) {
            lines -= 1
            layout = buildLayout(text, paint, widthPx, lines, lineMul)
        }
        return layout
    }

    private fun buildLayout(text: String, paint: TextPaint, widthPx: Int, maxLines: Int, lineMul: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, lineMul)
            .setIncludePad(false)
            .build()

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        canvas.drawColor(colBgWhite)
        if (schedule == null) return
        val w = width.toFloat()
        val sec = secondaryWeek
        if (sec != null) {
            val secLeft = pageOffset + if (sec > week) w else -w
            drawPage(canvas, renderFor(sec), secLeft)
        }
        drawPage(canvas, renderFor(week), pageOffset)
    }

    /** 在 [left, left+width] 区域绘制某一周（裁剪 + 平移），实现相邻周并排连贯滑动。 */
    private fun drawPage(canvas: Canvas, render: WeekRender, left: Float) {
        val w = width.toFloat()
        canvas.save()
        canvas.clipRect(left, 0f, left + w, height.toFloat())
        canvas.translate(left, 0f)
        drawHeader(canvas, render)
        drawBody(canvas)
        drawBlocks(canvas, render)
        if (render.blocks.isEmpty()) {
            emptyPaint.textSize = rpx(25f)
            emptyPaint.color = colEmpty
            canvas.drawText(context.getString(R.string.index_week_empty), w / 2f, headerHeightPx + rpx(200f), emptyPaint)
        }
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, render: WeekRender) {
        val lineW = max(1f, rpx(1f))
        fillPaint.color = colCornerBg
        canvas.drawRect(0f, 0f, timeColPx, headerHeightPx, fillPaint)
        linePaint.strokeWidth = lineW
        linePaint.color = colCornerBorder
        canvas.drawLine(timeColPx, 0f, timeColPx, headerHeightPx, linePaint)
        cornerPaint.textSize = rpx(20f)
        cornerPaint.color = colDayText
        drawCenteredText(canvas, render.monthLabel, timeColPx / 2f, headerHeightPx / 2f, cornerPaint)

        dayNamePaint.textSize = rpx(21f)
        datePaint.textSize = rpx(27f)
        val nameH = rpx(28f)
        val dateH = rpx(42f)
        // 「已补」圆点的占位行（补课来源日）
        val dotH = rpx(10f)
        val stackH = nameH + rpx(2f) + dateH + dotH
        val startY = (headerHeightPx - stackH) / 2f
        render.dates.forEachIndexed { i, d ->
            val cx = timeColPx + i * dayColPx + dayColPx / 2f
            dayNamePaint.color = if (d.today) colTodayText else colDayText
            drawCenteredText(canvas, d.name, cx, startY + nameH / 2f, dayNamePaint)
            val dateCy = startY + nameH + rpx(2f) + dateH / 2f
            val text = d.day.toString()
            if (d.today) {
                val pillW = max(rpx(46f), datePaint.measureText(text) + rpx(16f))
                fillPaint.color = colTodayBg
                val rect = RectF(cx - pillW / 2f, dateCy - dateH / 2f, cx + pillW / 2f, dateCy + dateH / 2f)
                canvas.drawRoundRect(rect, rpx(14f), rpx(14f), fillPaint)
                datePaint.color = Color.WHITE
            } else {
                datePaint.color = colDayText
            }
            drawCenteredText(canvas, text, cx, dateCy, datePaint)
            // 表头日历标记：「补」= 补课日、「休」= 停课日（星期名右缘小胶囊，与日期完全错开）；
            // 补课来源日不用文字胶囊，改为日期正下方一枚橙色小圆点（同日历事件点的通用语言），
            // 轻量、不遮挡任何文字，点击该列表头或课程卡可查看「已补到某日」详情。
            when (render.marks.getOrNull(i)) {
                MARK_MAKEUP -> drawDayMark(canvas, i, startY + nameH / 2f, "补", colMarkMakeupBg, colMarkMakeupText)
                MARK_MADE_UP -> drawMadeUpDot(canvas, i, startY + nameH + rpx(2f) + dateH + dotH / 2f)
                MARK_OFF -> drawDayMark(canvas, i, startY + nameH / 2f, "休", colMarkOffBg, colMarkOffText)
            }
        }
        linePaint.color = colHeadBorder
        canvas.drawLine(0f, headerHeightPx, width.toFloat(), headerHeightPx, linePaint)
    }

    /** 表头日历角标（补/休），放在星期名行右缘并限制在当前列内。 */
    private fun drawDayMark(
        canvas: Canvas,
        column: Int,
        markCy: Float,
        label: String,
        bg: Int,
        text: Int
    ) {
        cornerPaint.textSize = rpx(17f)
        val markW = cornerPaint.measureText(label) + rpx(10f)
        val markH = rpx(24f)
        val colRight = timeColPx + (column + 1) * dayColPx
        val pillRight = colRight - rpx(5f)
        val pillLeft = pillRight - markW
        val rect = RectF(pillLeft, markCy - markH / 2f, pillRight, markCy + markH / 2f)
        fillPaint.color = bg
        canvas.drawRoundRect(rect, rpx(8f), rpx(8f), fillPaint)
        cornerPaint.color = text
        val fm = cornerPaint.fontMetrics
        canvas.drawText(label, (pillLeft + pillRight) / 2f, markCy - (fm.ascent + fm.descent) / 2f, cornerPaint)
    }

    /** 补课来源日：日期正下方一枚补课橙小圆点，与补课日的「补」胶囊同色呼应，双向可见。 */
    private fun drawMadeUpDot(canvas: Canvas, column: Int, cy: Float) {
        val cx = timeColPx + column * dayColPx + dayColPx / 2f
        fillPaint.color = colMarkMakeupText
        canvas.drawCircle(cx, cy, rpx(3.5f), fillPaint)
    }

    private fun drawBody(canvas: Canvas) {
        val lineW = max(1f, rpx(1f))
        val rows = sectionsList.size
        val bottom = headerHeightPx + rows * rowHeightPx
        fillPaint.color = colSectionBg
        canvas.drawRect(0f, headerHeightPx, timeColPx, bottom, fillPaint)

        timeNumberPaint.textSize = rpx(26f)
        timeClockPaint.textSize = rpx(18f)
        linePaint.strokeWidth = lineW
        sectionsList.forEachIndexed { r, slot ->
            val top = headerHeightPx + r * rowHeightPx
            val numH = rpx(30f); val clockH = rpx(22f)
            val stackH = numH + rpx(5f) + clockH * 2
            val startY = top + (rowHeightPx - stackH) / 2f
            timeNumberPaint.color = colTimeNumber
            drawCenteredText(canvas, slot.number.toString(), timeColPx / 2f, startY + numH / 2f, timeNumberPaint)
            timeClockPaint.color = colTimeClock
            drawCenteredText(canvas, slot.startTime, timeColPx / 2f, startY + numH + rpx(5f) + clockH / 2f, timeClockPaint)
            drawCenteredText(canvas, slot.endTime, timeColPx / 2f, startY + numH + rpx(5f) + clockH + clockH / 2f, timeClockPaint)
            linePaint.color = colGridH
            canvas.drawLine(timeColPx, top + rowHeightPx, width.toFloat(), top + rowHeightPx, linePaint)
            linePaint.color = colTimeBorder
            canvas.drawLine(timeColPx, top, timeColPx, top + rowHeightPx, linePaint)
            canvas.drawLine(0f, top + rowHeightPx, timeColPx, top + rowHeightPx, linePaint)
        }
        linePaint.color = colGridV
        for (i in 1 until dayCount) {
            val x = timeColPx + i * dayColPx
            canvas.drawLine(x, headerHeightPx, x, bottom, linePaint)
        }
    }

    private fun drawBlocks(canvas: Canvas, render: WeekRender) {
        val radius = rpx(12f)
        render.blocks.forEach { b ->
            val split = b.columnCount > 1
            val padTop = blockPadTop(split)
            val padH = blockPadH(split)
            val rect = RectF(b.left, b.top, b.right, b.bottom)
            fillPaint.color = b.background
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            if (b.occurrence == Occurrence.MOVED_OUT || b.holiday != null || b.madeUpNote != null) {
                linePaint.color = b.foreground
                linePaint.strokeWidth = max(1f, rpx(2f))
                canvas.drawRoundRect(rect, radius, radius, linePaint)
            }

            canvas.save()
            canvas.clipRect(rect)
            val contentLeft = b.left + padH
            var y = b.top + padTop
            if (b.badge.isNotEmpty() && !b.compact && b.badgeWidth > 0f) {
                val badgeH = badgeHeightPx()
                fillPaint.color = colBadgeBg
                val br = RectF(contentLeft, y, contentLeft + b.badgeWidth, y + badgeH)
                canvas.drawRoundRect(br, rpx(8f), rpx(8f), fillPaint)
                badgePaint.color = b.foreground
                val fm = badgePaint.fontMetrics
                canvas.drawText(b.badge, contentLeft + rpx(8f), y + badgeH / 2f - (fm.ascent + fm.descent) / 2f, badgePaint)
                y += badgeH + rpx(6f)
            }
            b.nameLayout?.let {
                (if (split) splitNamePaint else namePaint).color = b.foreground
                canvas.save(); canvas.translate(contentLeft, y); it.draw(canvas); canvas.restore()
                y += it.height
            }
            b.teacherLayout?.let {
                y += if (b.compact) rpx(4f) else rpx(8f)
                (if (split) splitTeacherPaint else teacherPaint).color = withAlpha(b.foreground, 0.94f)
                canvas.save(); canvas.translate(contentLeft, y); it.draw(canvas); canvas.restore()
                y += it.height
            }
            b.placeLayout?.let {
                y += rpx(4f)
                (if (split) splitPlacePaint else placePaint).color = withAlpha(b.foreground, 0.85f)
                canvas.save(); canvas.translate(contentLeft, y); it.draw(canvas); canvas.restore()
            }
            canvas.restore()
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, centerY: Float, paint: Paint) {
        val fm = paint.fontMetrics
        canvas.drawText(text, cx, centerY - (fm.ascent + fm.descent) / 2f, paint)
    }

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (dragging || schedule == null) return@Runnable
        val render = renderFor(week)
        val hit = render.blocks.lastOrNull {
            (downX - pageOffset) in it.left..it.right && downY in it.top..it.bottom
        }
        // 空格子长按：与点按一样新建日程；日程块长按不做事（选色只针对课程，编辑在详情里）
        if (hit == null) {
            if (onEmptySlotClick == null) return@Runnable
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openEmptySlot(downX - pageOffset, downY)
            return@Runnable
        }
        if (hit.event != null || onCourseLongClick == null) return@Runnable
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        openBlock(render, hit, onCourseLongClick)
    }

    private fun cancelLongPressCheck() {
        longPressHandler.removeCallbacks(longPressRunnable)
    }

    // ===== 手势：横向拖拽翻页 / 点击课程 =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x; downY = event.y
                dragging = false
                offsetAnimator?.cancel()
                longPressFired = false
                longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) cancelLongPressCheck()
                // 横向意图稍占优即认领手势，避免被竖向滚动提前抢走导致“滑一半被打断”
                if (!dragging && abs(dx) > touchSlop && abs(dx) >= abs(dy) * 0.8f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) dragTo(dx)
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                velocityTracker?.computeCurrentVelocity(1000)
                val velX = velocityTracker?.xVelocity ?: 0f
                val dx = event.x - downX
                val dy = event.y - downY
                cancelLongPressCheck()
                if (dragging) settleAfterDrag(velX)
                else if (!longPressFired && abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                    performClick()
                    handleClick(event.x - pageOffset, event.y)
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                cancelLongPressCheck()
                animateOffsetTo(0f, 150) { secondaryWeek = null; invalidate() }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        longPressHandler.removeCallbacks(longPressRunnable)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 拖拽时整体平移：当前页跟随手指，相邻页从对应侧进入；到头则橡皮筋回拉。 */
    private fun dragTo(dx: Float) {
        val s = schedule ?: return
        val w = width.toFloat()
        val neighbor = when {
            dx < 0 -> week + 1
            dx > 0 -> week - 1
            else -> week
        }
        val inRange = neighbor in 1..s.totalWeeks && neighbor != week
        var off = dx.coerceIn(-w, w)
        if (!inRange) off *= 0.35f
        pageOffset = off
        secondaryWeek = if (inRange) neighbor else null
        invalidate()
    }

    private fun handleClick(x: Float, y: Float) {
        // 点击表头某一天：打开该日停课/补课面板（无课的周末也可设置调休）。
        if (y <= headerHeightPx && dayColPx > 0f && x > timeColPx) {
            val day = ((x - timeColPx) / dayColPx).toInt() + 1
            if (day in 1..dayCount) {
                openDay(day)
                return
            }
        }
        val render = renderFor(week)
        val hit = render.blocks.lastOrNull { x in it.left..it.right && y in it.top..it.bottom }
        if (hit != null) {
            openBlock(render, hit)
            return
        }
        openEmptySlot(x, y)
    }

    /**
     * 点击空格子：在这个时段新建日程。
     * 发布者和订阅者都可以——日程是本机私有数据，与课表的编辑权限无关。
     */
    private fun openEmptySlot(x: Float, y: Float) {
        val sink = onEmptySlotClick ?: return
        if (dayColPx <= 0f || rowHeightPx <= 0f || x <= timeColPx || y <= headerHeightPx) return
        val day = ((x - timeColPx) / dayColPx).toInt() + 1
        val section = ((y - headerHeightPx) / rowHeightPx).toInt() + 1
        if (day !in 1..dayCount || section !in 1..sectionsList.size) return
        sink(week, day, section)
    }

    private fun openDay(day: Int) {
        val s = schedule ?: return
        val date = renderFor(week).dates.getOrNull(day - 1) ?: return
        onDayClick?.invoke(
            DayClick(
                week = week,
                day = day,
                date = date,
                holiday = s.holidays.find { it.week == week && it.day == day },
                makeup = s.makeups.find { it.targetWeek == week && it.targetDay == day }
            )
        )
    }

    private fun openBlock(render: WeekRender, hit: Block, sink: ((CourseClick) -> Unit)? = null) {
        hit.event?.let { event ->
            // 长按（sink 非空）只对课程块有效，日程的编辑/删除在详情抽屉里
            if (sink != null) return
            val date = render.dates.getOrNull(hit.col - 1)
            onEventClick?.invoke(
                EventClick(
                    event = event,
                    week = week,
                    dayName = if (date != null) "周${date.name}" else context.getString(R.string.detail_day_fallback),
                    backgroundColor = hit.background
                )
            )
            return
        }
        val start = sectionsList.find { it.number == hit.course.startSection }
        val end = sectionsList.find { it.number == hit.course.endSection }
        // 日期列以块所在列为准（补课块显示在目标日，而 course.day 是来源日）。
        val date = render.dates.getOrNull(hit.col - 1)
        val timeText = if (start != null && end != null) "${start.startTime}–${end.endTime}"
        else context.getString(R.string.detail_time_pending)
        val click = CourseClick(
                course = hit.course,
                week = week,
                adjustment = hit.adjustment,
                occurrence = hit.occurrence,
                orphaned = hit.orphaned,
                backgroundColor = hit.background,
                dayName = if (date != null) "周${date.name}" else context.getString(R.string.detail_day_fallback),
                timeText = timeText,
                weekSummary = when {
                    hit.makeup != null -> "补第 ${hit.makeup.sourceWeek} 周周${dayName(hit.makeup.sourceDay)} 的课"
                    hit.madeUpNote != null -> "第 $week 周 · 已补"
                    hit.holiday != null -> "第 $week 周 · 停课"
                    else -> when (hit.occurrence) {
                        Occurrence.NORMAL -> ScheduleView.formatWeekSummary(hit.course.weeks)
                        Occurrence.MOVED_IN -> "第 $week 周 · 调入"
                        Occurrence.MOVED_OUT -> "第 $week 周 · 已调出"
                    }
                },
                holiday = hit.holiday,
                makeup = hit.makeup,
                madeUpNote = hit.madeUpNote
            )
        (sink ?: onCourseClick)?.invoke(click)
    }

    private fun blockDescription(block: Block): String {
        block.event?.let { event ->
            val time = event.timeText(sectionsList)?.let { "，$it" }.orEmpty()
            val place = event.position.takeIf { it.isNotBlank() }?.let { "，地点$it" }.orEmpty()
            return "日程${event.title}，周${dayName(block.col)}，" +
                "第${block.startSection}到${block.startSection + block.span - 1}节$time$place"
        }
        val status = when {
            block.makeup != null -> "，补第${block.makeup.sourceWeek}周周${dayName(block.makeup.sourceDay)}的课"
            block.madeUpNote != null -> "，已补课"
            block.holiday != null -> "，停课，当天不上课"
            else -> when (block.occurrence) {
                Occurrence.NORMAL -> ""
                Occurrence.MOVED_IN -> "，调入课程"
                Occurrence.MOVED_OUT -> "，已调出"
            }
        }
        val teacher = block.course.teacher.takeIf { it.isNotBlank() }?.let { "，教师$it" }.orEmpty()
        val place = block.course.position.takeIf { it.isNotBlank() }?.let { "，地点$it" }.orEmpty()
        return "${block.course.name}$status，周${listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(block.col - 1) { "" }}，第${block.startSection}到${block.startSection + block.span - 1}节$teacher$place"
    }

    private fun dayName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日")
        .getOrElse(day - 1) { "?" }

    /** 补课目标日的展示文案，如「9月20日」。 */
    private fun madeUpDateText(s: Schedule, makeup: DayMakeup): String {
        val date = DateUtils.datesForWeek(s.semesterStart, makeup.targetWeek).getOrNull(makeup.targetDay - 1)
        return date?.let { "${it.month}月${it.day}日" } ?: "第 ${makeup.targetWeek} 周"
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    companion object {
        /** 教室名允许的最大行数；实际行数仍受课程块剩余高度约束（见 fitLayout）。 */
        const val PLACE_MAX_LINES = 4
        /** 表头（星期 + 日期）高度，不随外观档位变化。 */
        const val HEADER_HEIGHT_RPX = 92f
        /** 单双周角标基准字号；实际字号再乘外观的文字缩放系数。 */
        const val BADGE_TEXT_RPX = 17f
        /** 日程与课程同格分栏时两栏之间的缝宽（设计稿单位），不要 0 距离贴在一起。 */
        const val SPLIT_GAP_RPX = 4f
        /** 七列模式下分栏块的额外字号收缩系数；五列分栏只降到七列档，不再乘。 */
        const val SPLIT_DENSE_SCALE = 0.85f
        const val MIN_SECTIONS = 8
        const val WEEKDAY_COUNT = WeekendDisplay.WEEKDAY_COUNT
        const val FULL_WEEK_COUNT = WeekendDisplay.FULL_WEEK_COUNT
        /** 表头日期虚拟视图 id 的起始偏移（无障碍）。 */
        const val DAY_VIEW_BASE = 1000
        /** WeekRender.marks：0 正常，1 停课，2 补课，3 已补（补课来源日）。 */
        const val MARK_OFF = 1
        const val MARK_MAKEUP = 2
        const val MARK_MADE_UP = 3
        private val TIME_COLUMN = mapOf(5 to 70f, 7 to 78f)

        fun timeColumnRpx(dayCount: Int): Float = TIME_COLUMN[dayCount] ?: TIME_COLUMN[FULL_WEEK_COUNT]!!

        /**
         * 实际用到的最大节次决定显示行数，最少 8、最多 12。
         *
         * 日程的结束节必须算进来：学校课表只排到下午时行数只有 8，
         * 晚上根本没有格子可以放「周五晚上的社团活动」，功能会直接不可用。
         */
        fun visibleSections(
            schedule: Schedule,
            events: List<PersonalEvent> = emptyList()
        ): List<TimeSlot> {
            val slots = ScheduleTime.slotsOf(schedule.timeSlots)
            val lastUsed = maxOf(
                schedule.courses.maxOfOrNull { it.endSection } ?: 0,
                schedule.adjustments.maxOfOrNull { it.targetEndSection } ?: 0,
                events.maxOfOrNull { it.endSection } ?: 0
            )
            val count = minOf(slots.size, maxOf(MIN_SECTIONS, lastUsed))
            return slots.take(count)
        }

    }
}
