package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.CellColumns
import com.zhusijiao.app.domain.CoupleDay
import com.zhusijiao.app.domain.CoupleFreeTime
import com.zhusijiao.app.domain.ScheduleTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 情侣日视图：一天之内两个人的安排按**真实时间**上下排开，中间一根时间轴把左右两个人分开。
 *
 * - 时间范围由学校作息决定（第一节前、最后一节后各留一点），每天比例一样，左右换天时画面不跳；
 *   午休、晚饭这些空当在图上直接看得到——情侣最关心的就是这些时间；
 * - 课块按人上色；已上变淡（跟随课表外观的「显示已上状态」）、正在上加一圈描边、
 *   停课变淡标「停课」；日程是虚线框，只出现在本人那一侧；
 * - 「都有空」：两侧铺底色（[Model.highlightFree]）、时长标签压在中轴上（[Model.showFreeLabel]），两项各自开关；
 * - 当前时间线只在看今天时画（[Model.showNowLine]）；
 * - 点课块回调 [onItemClick]，左右滑动回调 [onSwipe]（+1 下一天，-1 上一天）。
 *
 * 只负责画，数据由 [com.zhusijiao.app.domain.CoupleDay] 算好传进来。
 */
class CoupleDayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一侧的人：颜色、名字、这一天的安排；[placeholder] 非空时这一侧只显示这句话（比如 TA 还没有课表）。 */
    data class Column(
        val name: String,
        val fill: Int,
        val ink: Int,
        val ring: Int,
        val items: List<CoupleDay.Item>,
        val placeholder: String? = null
    )

    data class Model(
        val left: Column,
        val right: Column,
        /** 作息的第一节开始与最后一节结束（分钟）。 */
        val dayStart: Int,
        val dayEnd: Int,
        val gaps: List<CoupleFreeTime.Gap>,
        val highlightFree: Boolean,
        val showFreeLabel: Boolean,
        /** 看的是今天时为现在是当天第几分钟，否则为 null（不画时间线、不标上课中/已上）。 */
        val nowMinutes: Int?,
        val showNowLine: Boolean,
        val showFinished: Boolean,
        /** 两边都没有安排时画在中间的提示；null 表示不画。 */
        val emptyTitle: String? = null,
        val emptyDesc: String? = null
    )

    var onItemClick: ((side: Int, item: CoupleDay.Item) -> Unit)? = null
    var onSwipe: ((direction: Int) -> Unit)? = null

    private var model: Model? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaledDensity

    private val colBackground = ContextCompat.getColor(context, R.color.background)
    private val colSpine = ContextCompat.getColor(context, R.color.couple_spine)
    private val colHourLine = ContextCompat.getColor(context, R.color.couple_hour_line)
    private val colHourText = ContextCompat.getColor(context, R.color.couple_hour_text)
    private val colBand = ContextCompat.getColor(context, R.color.couple_free_band)
    private val colPillBorder = ContextCompat.getColor(context, R.color.couple_free_pill_border)
    private val colPillText = ContextCompat.getColor(context, R.color.couple_free_pill_text)
    private val colNow = ContextCompat.getColor(context, R.color.couple_now_line)
    private val colSurface = ContextCompat.getColor(context, R.color.surface)
    private val colInk = ContextCompat.getColor(context, R.color.ink)
    private val colMuted = ContextCompat.getColor(context, R.color.muted)
    private val colLine = ContextCompat.getColor(context, R.color.line)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val linePaint = Paint().apply { strokeWidth = 1f }
    private val hourTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = colHourText
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = colPillText
        textAlign = Paint.Align.CENTER
    }
    private val nowTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        color = 0xffffffff.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12.5f)
        color = colMuted
        textAlign = Paint.Align.CENTER
    }
    private val emptyTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
        typeface = Typeface.DEFAULT_BOLD
        color = colInk
        textAlign = Paint.Align.CENTER
    }

    private val sidePadding = dp(10f)
    private val spineWidth = dp(46f)
    private val columnGap = dp(4f)
    private val verticalPadding = dp(10f)
    private val blockRadius = dp(10f)
    private val minHourHeight = dp(36f)

    /** 画出来的块，点击命中与无障碍共用。 */
    private class Hit(val side: Int, val item: CoupleDay.Item, val rect: RectF, val description: String)

    private val hits = mutableListOf<Hit>()
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var horizontalDrag = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = dp(56f)

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            hits.indexOfLast { it.rect.contains(x, y) }.takeIf { it >= 0 } ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            hits.indices.forEach { virtualViewIds.add(it) }
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            event.contentDescription = hits.getOrNull(virtualViewId)?.description
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val hit = hits.getOrNull(virtualViewId)
            if (hit == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = hit.description
            node.className = android.widget.Button::class.java.name
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(Rect(hit.rect.left.toInt(), hit.rect.top.toInt(), hit.rect.right.toInt(), hit.rect.bottom.toInt()))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val hit = hits.getOrNull(virtualViewId) ?: return false
            onItemClick?.invoke(hit.side, hit.item)
            return true
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    fun setModel(value: Model) {
        val rangeChanged = model?.let { it.dayStart != value.dayStart || it.dayEnd != value.dayEnd } ?: true
        model = value
        if (rangeChanged) requestLayout()
        invalidate()
        accessibilityHelper.invalidateRoot()
    }

    // ===== 尺寸与坐标 =====

    /** 画布的时间范围：第一节前、最后一节后各留一点，取整到半点。 */
    private fun rangeStart(m: Model) = (floor((m.dayStart - 10) / 30.0) * 30).toInt()
    private fun rangeEnd(m: Model) = (ceil((m.dayEnd + 10) / 30.0) * 30).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val m = model
        val minHeight = if (m == null) 0 else {
            ((rangeEnd(m) - rangeStart(m)) / 60f * minHourHeight + verticalPadding * 2).toInt()
        }
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> max(minHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> minHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun scaleOf(m: Model): Float =
        (height - verticalPadding * 2) / (rangeEnd(m) - rangeStart(m)).coerceAtLeast(60).toFloat()

    private fun yOf(m: Model, minutes: Int): Float = verticalPadding + (minutes - rangeStart(m)) * scaleOf(m)

    private val columnWidth: Float get() = (width - sidePadding * 2 - spineWidth) / 2f - columnGap
    private fun columnLeft(side: Int): Float =
        if (side == 0) sidePadding else width - sidePadding - columnWidth
    private val spineCenter: Float get() = width / 2f

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        hits.clear()
        canvas.drawColor(colBackground)
        drawGrid(canvas, m)
        if (m.highlightFree) drawBands(canvas, m)
        drawColumn(canvas, m, 0, m.left)
        drawColumn(canvas, m, 1, m.right)
        if (m.showNowLine && m.nowMinutes != null) drawNowLine(canvas, m, m.nowMinutes)
        if (m.showFreeLabel) drawFreeLabels(canvas, m)
        if (m.emptyTitle != null) drawEmpty(canvas, m.emptyTitle, m.emptyDesc)
    }

    private fun drawGrid(canvas: Canvas, m: Model) {
        linePaint.color = colSpine
        canvas.drawRect(spineCenter - 0.5f, 0f, spineCenter + 0.5f, height.toFloat(), linePaint)
        linePaint.color = colHourLine
        val firstHour = ceil(rangeStart(m) / 60.0).toInt()
        val lastHour = floor(rangeEnd(m) / 60.0).toInt()
        val labelHalfHeight = dp(7f)
        for (hour in firstHour..lastHour) {
            val y = yOf(m, hour * 60)
            if (y < verticalPadding - 1 || y > height - verticalPadding + 1) continue
            for (side in 0..1) {
                val left = columnLeft(side)
                canvas.drawRect(left, y, left + columnWidth, y + 1f, linePaint)
            }
            fillPaint.color = colBackground
            canvas.drawRect(spineCenter - dp(17f), y - labelHalfHeight, spineCenter + dp(17f), y + labelHalfHeight, fillPaint)
            canvas.drawText(ScheduleTime.formatTime(hour * 60), spineCenter, y + hourTextPaint.textSize / 2.8f, hourTextPaint)
        }
    }

    private fun drawBands(canvas: Canvas, m: Model) {
        fillPaint.color = colBand
        m.gaps.forEach { gap ->
            val top = yOf(m, gap.start)
            val bottom = yOf(m, gap.end) - dp(2f)
            if (bottom <= top) return@forEach
            for (side in 0..1) {
                val left = columnLeft(side)
                rect.set(left, top, left + columnWidth, bottom)
                canvas.drawRoundRect(rect, blockRadius, blockRadius, fillPaint)
            }
        }
    }

    private fun drawColumn(canvas: Canvas, m: Model, side: Int, column: Column) {
        val left = columnLeft(side)
        column.placeholder?.let { text ->
            val centerX = left + columnWidth / 2f
            val line = TextUtils.ellipsize(text, messagePaint, columnWidth - dp(8f), TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, centerX, verticalPadding + dp(44f), messagePaint)
            return
        }
        // 停课的课垫底、占满整栏；当天真要上的块（课与课、课与日程）重叠时左右分栏，谁也不盖住谁
        column.items.filter { it.suspended }.forEach { drawItem(canvas, m, side, column, it, left, CellColumns.Slot(0, 1)) }
        val live = column.items.filterNot { it.suspended }
        val slots = CellColumns.assign(live.map {
            CellColumns.Item(1, it.startMinutes, it.endMinutes - 1, it.kind == CoupleDay.Kind.EVENT)
        })
        live.forEachIndexed { index, item -> drawItem(canvas, m, side, column, item, left, slots[index]) }
    }

    private fun drawItem(
        canvas: Canvas,
        m: Model,
        side: Int,
        column: Column,
        item: CoupleDay.Item,
        columnLeft: Float,
        slot: CellColumns.Slot
    ) {
        val top = yOf(m, item.startMinutes)
        val bottom = yOf(m, item.endMinutes) - dp(2f)
        if (bottom <= top) return
        val gap = dp(3f)
        val each = (columnWidth - gap * (slot.columnCount - 1)) / slot.columnCount
        val left = columnLeft + slot.column * (each + gap)
        rect.set(left, top, left + each, bottom)
        val now = m.nowMinutes
        val current = now != null && !item.suspended && item.startMinutes <= now && now < item.endMinutes
        val finished = now != null && m.showFinished && !item.suspended && item.endMinutes <= now
        val isEvent = item.kind == CoupleDay.Kind.EVENT
        val badge = when {
            item.suspended -> context.getString(R.string.couple_badge_suspended)
            current -> context.getString(R.string.couple_badge_now)
            finished -> context.getString(R.string.couple_badge_done)
            else -> null
        }

        val faded = item.suspended || finished
        val saved = if (faded) canvas.saveLayerAlpha(rect.left - dp(4f), rect.top - dp(4f), rect.right + dp(4f), rect.bottom + dp(4f), if (item.suspended) 110 else 128) else -1

        if (isEvent) {
            fillPaint.color = colSurface
            canvas.drawRoundRect(rect, blockRadius, blockRadius, fillPaint)
            dashPaint.color = column.ring
            canvas.drawRoundRect(rect, blockRadius, blockRadius, dashPaint)
        } else {
            fillPaint.color = column.fill
            canvas.drawRoundRect(rect, blockRadius, blockRadius, fillPaint)
        }
        if (current) {
            strokePaint.color = column.ring
            strokePaint.strokeWidth = dp(2f)
            val outset = dp(3f)
            canvas.drawRoundRect(
                RectF(rect.left - outset, rect.top - outset, rect.right + outset, rect.bottom + outset),
                blockRadius + outset, blockRadius + outset, strokePaint
            )
        }

        val textColor = if (isEvent) colInk else column.ink
        val split = slot.columnCount > 1
        val padX = dp(if (split) 5f else 8f)
        val available = rect.width() - padX * 2
        val compact = rect.height() < dp(52f)
        namePaint.color = textColor
        subPaint.color = textColor
        subPaint.alpha = 225
        val time = "${ScheduleTime.formatTime(item.startMinutes)}–${ScheduleTime.formatTime(item.endMinutes)}"
        if (compact) {
            namePaint.textSize = sp(12f)
            subPaint.textSize = sp(10f)
            val y1 = rect.top + dp(3f) - namePaint.ascent()
            drawLine(canvas, item.title, namePaint, rect.left + padX, y1, available)
            val sub = if (isEvent) "$time · ${item.position}" else item.position
            val y2 = y1 + namePaint.descent() - subPaint.ascent()
            if (sub.isNotBlank() && y2 + subPaint.descent() <= rect.bottom) {
                drawLine(canvas, sub, subPaint, rect.left + padX, y2, available)
            }
        } else {
            namePaint.textSize = sp(13f)
            subPaint.textSize = sp(11f)
            var y = rect.top + dp(6f) - namePaint.ascent()
            drawLine(canvas, item.title, namePaint, rect.left + padX, y, available)
            if (item.position.isNotBlank()) {
                y += namePaint.descent() - subPaint.ascent() + dp(1f)
                if (y + subPaint.descent() <= rect.bottom) drawLine(canvas, item.position, subPaint, rect.left + padX, y, available)
            }
            y += subPaint.descent() - subPaint.ascent() + dp(1f)
            if (y + subPaint.descent() <= rect.bottom) {
                var timeWidth = available
                // 分栏后太窄放不下角标，状态在详情和无障碍描述里给全
                if (badge != null && !split) {
                    val badgeWidth = badgePaint.measureText(badge) + dp(10f)
                    timeWidth -= badgeWidth + dp(4f)
                    drawBadge(canvas, badge, rect.right - padX - badgeWidth, y, badgeWidth, current, column, isEvent)
                }
                drawLine(canvas, time, subPaint, rect.left + padX, y, timeWidth)
            }
        }
        if (saved >= 0) canvas.restoreToCount(saved)

        val who = column.name
        val kind = if (isEvent) "日程" else "课"
        val description = context.getString(
            R.string.couple_block_desc,
            "$who 的$kind",
            item.title,
            time,
            item.position.ifBlank { context.getString(R.string.detail_room_pending) }
        ) + (badge?.let { "，$it" } ?: "")
        hits += Hit(side, item, RectF(rect), description)
    }

    private fun drawBadge(
        canvas: Canvas,
        text: String,
        left: Float,
        baseline: Float,
        width: Float,
        current: Boolean,
        column: Column,
        isEvent: Boolean
    ) {
        val top = baseline + badgePaint.ascent() - dp(1.5f)
        val bottom = baseline + badgePaint.descent() + dp(1.5f)
        fillPaint.color = when {
            current -> colSurface
            isEvent -> colLine
            else -> 0x8cffffff.toInt()
        }
        canvas.drawRoundRect(RectF(left, top, left + width, bottom), dp(6f), dp(6f), fillPaint)
        badgePaint.color = if (isEvent) colInk else column.ink
        canvas.drawText(text, left + dp(5f), baseline, badgePaint)
    }

    private fun drawLine(canvas: Canvas, text: String, paint: TextPaint, x: Float, baseline: Float, width: Float) {
        if (width <= 0f) return
        val line = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END)
        canvas.drawText(line, 0, line.length, x, baseline, paint)
    }

    private fun drawNowLine(canvas: Canvas, m: Model, now: Int) {
        if (now < rangeStart(m) || now > rangeEnd(m)) return
        val y = yOf(m, now)
        fillPaint.color = colNow
        canvas.drawRect(sidePadding, y - dp(0.75f), width - sidePadding, y + dp(0.75f), fillPaint)
        val text = ScheduleTime.formatTime(now)
        val halfWidth = nowTextPaint.measureText(text) / 2f + dp(6f)
        rect.set(spineCenter - halfWidth, y - dp(9f), spineCenter + halfWidth, y + dp(9f))
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fillPaint)
        canvas.drawText(text, spineCenter, y - (nowTextPaint.ascent() + nowTextPaint.descent()) / 2f, nowTextPaint)
    }

    private fun drawFreeLabels(canvas: Canvas, m: Model) {
        m.gaps.forEach { gap ->
            val top = yOf(m, gap.start)
            val bottom = yOf(m, gap.end)
            val mid = (top + bottom) / 2f
            val text = CoupleFreeTime.label(gap)
            val halfWidth = pillTextPaint.measureText(text) / 2f + dp(10f)
            val halfHeight = dp(11f)
            if (bottom - top < halfHeight * 2) return@forEach
            rect.set(spineCenter - halfWidth, mid - halfHeight, spineCenter + halfWidth, mid + halfHeight)
            fillPaint.color = colSurface
            canvas.drawRoundRect(rect, halfHeight, halfHeight, fillPaint)
            strokePaint.color = colPillBorder
            strokePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(rect, halfHeight, halfHeight, strokePaint)
            canvas.drawText(text, spineCenter, mid - (pillTextPaint.ascent() + pillTextPaint.descent()) / 2f, pillTextPaint)
        }
    }

    private fun drawEmpty(canvas: Canvas, title: String, desc: String?) {
        val cardWidth = (width * 0.7f).coerceAtMost(dp(280f))
        val top = height * 0.3f
        rect.set(spineCenter - cardWidth / 2f, top, spineCenter + cardWidth / 2f, top + dp(if (desc != null) 76f else 52f))
        fillPaint.color = colSurface
        canvas.drawRoundRect(rect, dp(16f), dp(16f), fillPaint)
        strokePaint.color = colLine
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), strokePaint)
        canvas.drawText(title, spineCenter, top + dp(22f) - emptyTitlePaint.ascent() / 2f, emptyTitlePaint)
        if (desc != null) canvas.drawText(desc, spineCenter, top + dp(54f), messagePaint)
    }

    // ===== 触摸 =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                horizontalDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!horizontalDrag && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                    horizontalDrag = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val dx = event.x - downX
                val dy = event.y - downY
                if (horizontalDrag) {
                    if (abs(dx) >= swipeThreshold) onSwipe?.invoke(if (dx < 0) 1 else -1)
                } else if (abs(dx) < touchSlop && abs(dy) < touchSlop) {
                    hits.lastOrNull { it.rect.contains(event.x, event.y) }?.let {
                        playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onItemClick?.invoke(it.side, it.item)
                    } ?: performClick()
                }
                horizontalDrag = false
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                horizontalDrag = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
}
