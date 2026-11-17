package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max

/**
 * 调休面板的整学期迷你日历：按自然月分块、真实日期直出，
 * 每格标注停课（灰底「休」）/补课目标日（橙底「补」）/补课来源日（右上橙点）；有课日期深色、无课日期浅色，
 * 点格子即选中当天，替代「第 X 周 + 周 X」这种要用户心算的抽象输入。
 *
 * 列按课表自身的星期定义排（day 1 = 周一 = 第 1 列），与全 App 的周次/星期模型保持一致。
 * 几何在 [layoutCells] 里按宽度一次算好存进 [cellRects]，绘制与命中测试都不再重复测量。
 */
class HolidayCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一个可点选的自然日；index 从 0 起（学期第 1 周周一为 0）。 */
    data class DayItem(
        val index: Int,
        val week: Int,
        val day: Int,
        val month: Int,
        val dayOfMonth: Int,
        val courseCount: Int,
        val isHoliday: Boolean,
        val isMakeupTarget: Boolean,
        val isMakeupSource: Boolean,
        val isToday: Boolean
    )

    /** 一个自然月块；cells 前部的 null 是该月首日之前的占位格。 */
    private class MonthBlock(val year: Int, val month: Int, val cells: List<DayItem?>) {
        val rows = ceil(cells.size / 7f).toInt()
    }

    var onDayClick: ((DayItem) -> Unit)? = null

    private var items: List<DayItem> = emptyList()
    private var blocks: List<MonthBlock> = emptyList()
    private var primaryIndex = -1
    private var secondaryIndex = -1
    private var rangeSelection = false
    private var selectableDays: Set<Int>? = null

    /** index -> 日格矩形，供绘制、命中测试与滚动定位共用。 */
    private val cellRects = HashMap<Int, RectF>()

    /** 与 [blocks] 等长的月块顶部 y。 */
    private var blockTops = FloatArray(0)
    private var contentHeight = 0f
    private var laidOutWidth = 0

    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val density = resources.displayMetrics.density

    // 字号随系统字体放大，但设上限并同步放大格高，避免大字号下文字溢出格子。
    private val fontScale = resources.configuration.fontScale.coerceIn(1f, 1.3f)
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * density * fontScale

    private val cellHeight = dp(48f) * fontScale
    private val monthTitleHeight = dp(28f) * fontScale
    private val rowGap = dp(2f)
    private val blockGap = dp(10f)
    private val cellRadius = dp(9f)

    private val ink = ContextCompat.getColor(context, R.color.text_primary)
    private val faintText = ContextCompat.getColor(context, R.color.text_disabled)
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val makeupText = ContextCompat.getColor(context, R.color.tt_mark_makeup_text)
    private val makeupBg = ContextCompat.getColor(context, R.color.tt_mark_makeup_bg)
    private val offText = ContextCompat.getColor(context, R.color.tt_mark_off_text)
    private val offBg = ContextCompat.getColor(context, R.color.tt_mark_off_bg)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    private fun isDaySelected(index: Int) = index == primaryIndex || (rangeSelection && secondaryIndex >= 0 &&
        index in minOf(primaryIndex, secondaryIndex)..maxOf(primaryIndex, secondaryIndex))

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            cellRects.entries.firstOrNull { it.value.contains(x, y) }?.key ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val visible = Rect()
            if (getLocalVisibleRect(visible)) cellRects.forEach { (id, rect) ->
                if (RectF.intersects(rect, RectF(visible))) ids.add(id)
            }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val item = items.getOrNull(id)
            val rect = cellRects[id]
            node.contentDescription = if (item == null) context.getString(R.string.holiday_no_selection) else {
                val status = when {
                    item.isHoliday -> "停课"
                    item.isMakeupTarget -> "补课"
                    item.isMakeupSource -> "已补课"
                    else -> context.getString(R.string.holiday_course_count_value, item.courseCount)
                }
                "${item.month}月${item.dayOfMonth}日 周${WEEK_NAMES[item.day - 1]}，$status"
            }
            node.setBoundsInParent(rect?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } ?: Rect(0, 0, 1, 1))
            node.className = android.widget.Button::class.java.name
            node.isSelected = isDaySelected(id)
            node.isEnabled = isSelectable(id)
            node.isClickable = isSelectable(id)
            if (isSelectable(id)) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val item = items.getOrNull(id) ?: return false
            if (!isSelectable(id)) return false
            onDayClick?.invoke(item)
            return true
        }
    }

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        contentDescription = context.getString(R.string.holiday_calendar_description)
    }

    /** 周次/星期 → 从 0 起的学期日下标。 */
    fun dayIndex(week: Int, day: Int): Int = (week - 1) * 7 + (day - 1)

    fun itemAt(index: Int): DayItem? = items.getOrNull(index)

    /** 重新读取课表的停课/补课与课程分布；选中态另行通过 [setSelection] 设置。 */
    fun configure(schedule: Schedule) {
        val totalWeeks = schedule.totalWeeks
        val firstIso = if (DateUtils.hasSemesterStart(schedule.semesterStart))
            DateUtils.datesForWeek(schedule.semesterStart, 1).firstOrNull()?.iso else null
        if (firstIso == null || totalWeeks <= 0) {
            items = emptyList()
            blocks = emptyList()
            blockTops = FloatArray(0)
            cellRects.clear()
            contentHeight = 0f
            laidOutWidth = 0
            requestLayout()
            invalidate()
            return
        }
        val holidays = schedule.holidays.mapTo(HashSet()) { dayIndex(it.week, it.day) }
        val makeupTargets = schedule.makeups.mapTo(HashSet()) { dayIndex(it.targetWeek, it.targetDay) }
        val makeupSources = schedule.makeups.mapTo(HashSet()) { dayIndex(it.sourceWeek, it.sourceDay) }
        rebuild(
            firstIso,
            totalWeeks,
            holidays,
            makeupTargets,
            makeupSources,
            (0 until totalWeeks * 7).associateWith { index ->
                ScheduleOccurrences.coursesOn(schedule, index / 7 + 1, index % 7 + 1).size
            }
        )
        laidOutWidth = 0
        cellRects.clear()
        requestLayout()
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    fun setSelectableDays(days: Set<Int>?) {
        selectableDays = days
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    private fun isSelectable(index: Int) = selectableDays?.contains(index) != false

    /** 单日选择或起止日期范围；-1 表示未选。仅重绘，不重排。 */
    fun setSelection(primaryIndex: Int, secondaryIndex: Int, rangeSelection: Boolean = false) {
        if (this.primaryIndex == primaryIndex && this.secondaryIndex == secondaryIndex && this.rangeSelection == rangeSelection) return
        this.rangeSelection = rangeSelection
        this.primaryIndex = primaryIndex
        this.secondaryIndex = secondaryIndex
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    /** 让 index 所在日露出时，外层滚动容器应滚到的 y；上方留两行做上下文。 */
    fun scrollTopFor(index: Int): Int {
        val rect = cellRects[index] ?: return 0
        return max(0f, rect.top - cellHeight * 2f).toInt()
    }

    private fun rebuild(
        firstIso: String,
        totalWeeks: Int,
        holidays: Set<Int>,
        makeupTargets: Set<Int>,
        makeupSources: Set<Int>,
        courseCounts: Map<Int, Int>
    ) {
        val parts = firstIso.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) {
            items = emptyList()
            blocks = emptyList()
            blockTops = FloatArray(0)
            return
        }
        // 单个游标逐日推进，避免每天克隆一次 Calendar。
        val cursor = Calendar.getInstance().apply {
            clear()
            set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
        }
        val todayIso = DateUtils.formatDate(Calendar.getInstance())
        val total = totalWeeks * 7
        val built = ArrayList<DayItem>(total)
        val grouped = LinkedHashMap<Int, MutableList<DayItem?>>()
        for (index in 0 until total) {
            val year = cursor.get(Calendar.YEAR)
            val month = cursor.get(Calendar.MONTH) + 1
            val item = DayItem(
                index = index,
                week = index / 7 + 1,
                day = index % 7 + 1,
                month = month,
                dayOfMonth = cursor.get(Calendar.DAY_OF_MONTH),
                courseCount = courseCounts[index] ?: 0,
                isHoliday = holidays.contains(index),
                isMakeupTarget = makeupTargets.contains(index),
                isMakeupSource = makeupSources.contains(index),
                isToday = DateUtils.formatDate(cursor) == todayIso
            )
            built += item
            // 新月份起头时先补该月首日之前的空格，使其落在正确的星期列上。
            grouped.getOrPut(year * 100 + month) { MutableList(index % 7) { null } } += item
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        items = built
        blocks = grouped.map { (key, cells) -> MonthBlock(key / 100, key % 100, cells) }
        blockTops = FloatArray(blocks.size)
    }

    /** 按当前宽度把每个日格的矩形算好；宽度未变时直接复用。 */
    private fun layoutCells(widthPx: Int) {
        if (blocks.isEmpty() || widthPx <= 0) return
        if (widthPx == laidOutWidth && cellRects.isNotEmpty()) return
        laidOutWidth = widthPx
        cellRects.clear()
        val cellWidth = widthPx / 7f
        var top = 0f
        blocks.forEachIndexed { blockIndex, block ->
            blockTops[blockIndex] = top
            val gridTop = top + monthTitleHeight
            block.cells.forEachIndexed { pos, item ->
                if (item == null) return@forEachIndexed
                val row = pos / 7
                val col = pos % 7
                val cellTop = gridTop + row * (cellHeight + rowGap)
                cellRects[item.index] = RectF(
                    cellWidth * col + dp(1.5f),
                    cellTop,
                    cellWidth * (col + 1) - dp(1.5f),
                    cellTop + cellHeight
                )
            }
            top = gridTop + block.rows * (cellHeight + rowGap) - rowGap + blockGap
        }
        contentHeight = max(0f, top - blockGap)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        layoutCells(width)
        setMeasuredDimension(width, contentHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (blocks.isEmpty()) return
        layoutCells(width)
        blocks.forEachIndexed { blockIndex, block ->
            val top = blockTops[blockIndex]
            textPaint.apply {
                textSize = sp(12.5f)
                typeface = Typeface.DEFAULT_BOLD
                color = ink
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(
                context.getString(R.string.holiday_calendar_month, block.year, block.month),
                dp(2f), top + monthTitleHeight - dp(9f), textPaint
            )
            textPaint.textAlign = Paint.Align.CENTER
            block.cells.forEach { item ->
                val rect = item?.let { cellRects[it.index] } ?: return@forEach
                drawCell(canvas, rect, item)
            }
        }
    }

    private fun drawCell(canvas: Canvas, rect: RectF, item: DayItem) {
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val selected = isDaySelected(item.index)
        val disabled = !isSelectable(item.index)
        val secondary = item.index == secondaryIndex && !selected
        // 底色优先级：主选中 > 停课 > 补课目标；普通日留白，让有安排的日子一眼跳出来。
        when {
            selected -> {
                fillPaint.color = accent
                canvas.drawRoundRect(rect, cellRadius, cellRadius, fillPaint)
            }
            item.isHoliday -> {
                fillPaint.color = offBg
                canvas.drawRoundRect(rect, cellRadius, cellRadius, fillPaint)
            }
            item.isMakeupTarget -> {
                fillPaint.color = makeupBg
                canvas.drawRoundRect(rect, cellRadius, cellRadius, fillPaint)
            }
        }
        // 描边：补课目标待选优先于「今天」提示
        val strokeColor = when {
            secondary -> makeupText
            item.isToday && !selected -> accent
            else -> null
        }
        if (strokeColor != null) {
            strokePaint.color = strokeColor
            val inset = strokePaint.strokeWidth / 2f
            canvas.drawRoundRect(
                RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
                cellRadius, cellRadius, strokePaint
            )
        }
        textPaint.apply {
            textSize = sp(13f)
            typeface = if (selected || item.isToday || item.isMakeupTarget) Typeface.DEFAULT_BOLD
            else Typeface.DEFAULT
            color = when {
                disabled -> faintText
                selected -> Color.WHITE
                item.isHoliday -> offText
                item.isMakeupTarget -> makeupText
                item.courseCount > 0 -> ink
                else -> faintText
            }
        }
        canvas.drawText(item.dayOfMonth.toString(), centerX, centerY + if (item.isHoliday || item.isMakeupTarget) sp(0f) else sp(4.5f), textPaint)
        // 仅保留休、补状态，普通日期不堆叠课程数。
        val badgeBaseline = rect.bottom - dp(4.5f)
        when {
            item.isHoliday ->
                drawBadge(canvas, centerX, badgeBaseline, if (selected) Color.WHITE else offText, "休")
            item.isMakeupTarget ->
                drawBadge(canvas, centerX, badgeBaseline, if (selected) Color.WHITE else makeupText, "补")
        }
        // 补课来源日：右上角橙点，提示「这天的课已经挪走了」
        if (item.isMakeupSource && !item.isMakeupTarget) {
            fillPaint.color = if (selected) Color.WHITE else makeupText
            canvas.drawCircle(rect.right - dp(6f), rect.top + dp(6f), dp(2f), fillPaint)
        }
    }

    private fun drawBadge(canvas: Canvas, centerX: Float, baseline: Float, color: Int, label: String) {
        textPaint.apply {
            textSize = sp(8.5f)
            typeface = Typeface.DEFAULT_BOLD
            this.color = color
        }
        canvas.drawText(label, centerX, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // 位移超过 slop 视为滚动手势，交给外层滚动容器，不当作点选。
                if (dx * dx + dy * dy <= touchSlop.toFloat() * touchSlop) {
                    val hit = cellRects.entries.firstOrNull { it.value.contains(event.x, event.y) }
                    val item = hit?.let { items.getOrNull(it.key) }
                    if (item != null && isSelectable(item.index)) {
                        performClick()
                        onDayClick?.invoke(item)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        val WEEK_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")
    }
}
