package com.zhusijiao.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleOccurrences
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.WeekendDisplay
import com.zhusijiao.app.domain.WeekendDisplayMode
import com.zhusijiao.app.ui.common.BlockStyles
import com.zhusijiao.app.ui.common.TimetableView
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 「本周课表」小部件的画图：把一周的课画成一张位图交给 RemoteViews。
 *
 * 桌面小部件里放不了自绘 View，所以不能直接复用 [TimetableView]，这里是它的精简版：
 * 只画当天真要上的课（走 [ScheduleOccurrences.coursesOn]，调课、停课、补课已算进去）和日程，
 * 不画「已调出 / 停课」这些灰块——桌面上只有巴掌大，只回答「这周哪天哪节有课」。
 * 配色、请假和日程的画法与课表页一致（[ScheduleView] 的调色板、[BlockStyles]）。
 */
object WeekWidgetRenderer {

    /** 位图像素上限：RemoteViews 传位图有大小限制（约 1.5 倍屏幕像素），留足余量。 */
    private const val MAX_PIXELS = 1_200_000f

    private const val DEFAULT_SIZE_DP = 250
    /** widget_week.xml 里图片四周占掉的宽高（左右内边距；上下内边距 + 表头 + 间距）。 */
    private const val CHROME_WIDTH_DP = 20
    private const val CHROME_HEIGHT_DP = 42

    private class Cell(
        val title: String,
        val place: String,
        val startSection: Int,
        val endSection: Int,
        val fill: Int,
        val ink: Int,
        val stroke: Int?,
        val dashed: Boolean
    ) {
        var lane = 0
        var lanes = 1
    }

    /** 画第 [week] 周；小部件尺寸 [widthDp] × [heightDp] 为 0 时按默认大小。内存不够时返回 null。 */
    fun render(
        context: Context,
        schedule: Schedule,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        week: Int,
        widthDp: Int,
        heightDp: Int,
        nowMillis: Long
    ): Bitmap? {
        val density = context.resources.displayMetrics.density
        var scale = density
        var width = (((if (widthDp > 0) widthDp else DEFAULT_SIZE_DP) - CHROME_WIDTH_DP).coerceAtLeast(80)) * density
        var height = (((if (heightDp > 0) heightDp else DEFAULT_SIZE_DP) - CHROME_HEIGHT_DP).coerceAtLeast(80)) * density
        if (width * height > MAX_PIXELS) {
            val shrink = sqrt(MAX_PIXELS / (width * height))
            width *= shrink
            height *= shrink
            scale *= shrink
        }
        val bitmap = try {
            Bitmap.createBitmap(width.roundToInt(), height.roundToInt(), Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        draw(Canvas(bitmap), context, schedule, events, leaves, week, width, height, scale, nowMillis)
        return bitmap
    }

    private fun draw(
        canvas: Canvas,
        context: Context,
        schedule: Schedule,
        events: List<PersonalEvent>,
        leaves: List<Leave>,
        week: Int,
        width: Float,
        height: Float,
        dp: Float,
        nowMillis: Long
    ) {
        val sections = TimetableView.visibleSections(schedule, events)
        val dayCount = WeekendDisplay.dayCount(schedule, week, WeekendDisplayMode.AUTO, events)
        val dates = DateUtils.datesForWeek(schedule.semesterStart, week)
        val todayIso = DateUtils.formatDate(Calendar.getInstance().apply { timeInMillis = nowMillis })
        val dense = dayCount > WeekendDisplay.WEEKDAY_COUNT

        val timeCol = 14f * dp
        val header = 18f * dp
        val colWidth = (width - timeCol) / dayCount
        val rowHeight = (height - header) / max(1, sections.size)
        val gap = 1f * dp
        val radius = 4f * dp

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 9f * dp
        }
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (dense) 8.5f else 9.5f) * dp
            typeface = Typeface.DEFAULT_BOLD
        }
        val place = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (if (dense) 7.5f else 8.5f) * dp }

        val accent = ContextCompat.getColor(context, R.color.accent)
        val muted = ContextCompat.getColor(context, R.color.text_tertiary)

        // 今天整列的浅底
        val todayColumn = (1..dayCount).firstOrNull { dates.getOrNull(it - 1)?.iso == todayIso }
        if (todayColumn != null) {
            fill.color = ContextCompat.getColor(context, R.color.tt_today_col)
            val left = timeCol + (todayColumn - 1) * colWidth
            canvas.drawRoundRect(RectF(left, 0f, left + colWidth, height), radius, radius, fill)
        }
        // 横向分隔线
        line.color = ContextCompat.getColor(context, R.color.tt_grid_h)
        line.strokeWidth = max(1f, 0.5f * dp)
        for (row in 0..sections.size) {
            val y = header + row * rowHeight
            canvas.drawLine(timeCol, y, width, y, line)
        }
        // 表头星期、左侧节次
        for (day in 1..dayCount) {
            small.color = if (day == todayColumn) accent else muted
            small.typeface = if (day == todayColumn) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            drawCentered(canvas, Leaves.dayName(day), timeCol + (day - 0.5f) * colWidth, header / 2f, small)
        }
        small.typeface = Typeface.DEFAULT
        small.color = muted
        sections.forEachIndexed { index, slot ->
            drawCentered(canvas, slot.number.toString(), timeCol / 2f, header + (index + 0.5f) * rowHeight, small)
        }

        val palette = ScheduleView.buildCoursePaletteMap(
            schedule.courses + schedule.adjustments.map { it.courseSnapshot },
            schedule.courseColors
        )
        val lastSection = sections.lastOrNull()?.number ?: 0
        val dash = DashPathEffect(floatArrayOf(4f * dp, 3f * dp), 0f)
        for (day in 1..dayCount) {
            val dateIso = dates.getOrNull(day - 1)?.iso
            val cells = mutableListOf<Cell>()
            ScheduleOccurrences.coursesOn(schedule, week, day).forEach { course ->
                val colors = palette[course.name] ?: ScheduleView.COURSE_PALETTES[0]
                val onLeave = Leaves.leaveFor(leaves, schedule, dateIso, course) != null
                cells += if (onLeave) {
                    // 请假：和课表页一样画成空心虚线
                    Cell(
                        course.name, course.position, course.startSection, course.endSection,
                        fill = Color.WHITE,
                        ink = BlockStyles.ghostText(colors.background),
                        stroke = BlockStyles.ghostStroke(colors.background),
                        dashed = true
                    )
                } else {
                    Cell(
                        course.name, course.position, course.startSection, course.endSection,
                        fill = colors.background, ink = colors.foreground, stroke = null, dashed = false
                    )
                }
            }
            events.filter { it.day == day && it.occursIn(week) }.forEach { event ->
                val tone = ScheduleView.manualPalette(event.color)?.background ?: BlockStyles.DEFAULT_EVENT_COLOR
                cells += Cell(
                    event.title, event.position, event.startSection, event.endSection,
                    fill = BlockStyles.eventFill(tone),
                    ink = BlockStyles.eventText(tone),
                    stroke = BlockStyles.eventStroke(tone),
                    dashed = false
                )
            }
            assignLanes(cells)
            cells.forEach { cell ->
                val start = cell.startSection.coerceIn(1, max(1, lastSection))
                val end = cell.endSection.coerceIn(start, max(start, lastSection))
                val laneWidth = (colWidth - gap * 2f) / cell.lanes
                val left = timeCol + (day - 1) * colWidth + gap + cell.lane * laneWidth
                val rect = RectF(
                    left + (if (cell.lane > 0) gap / 2f else 0f),
                    header + (start - 1) * rowHeight + gap,
                    left + laneWidth - (if (cell.lane < cell.lanes - 1) gap / 2f else 0f),
                    header + end * rowHeight - gap
                )
                fill.color = cell.fill
                canvas.drawRoundRect(rect, radius, radius, fill)
                cell.stroke?.let { color ->
                    line.color = color
                    line.strokeWidth = max(1f, 1f * dp)
                    line.pathEffect = if (cell.dashed) dash else null
                    val inset = line.strokeWidth / 2f
                    canvas.drawRoundRect(
                        RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
                        radius, radius, line
                    )
                    line.pathEffect = null
                }
                drawCellText(canvas, cell, rect, name, place, dp)
            }
        }
    }

    /** 同一天里时间重叠的块左右分栏：一组互相重叠的块平分列宽。 */
    private fun assignLanes(cells: MutableList<Cell>) {
        cells.sortWith(compareBy({ it.startSection }, { it.endSection }))
        var cluster = mutableListOf<Cell>()
        var clusterEnd = -1
        fun close() {
            val lanes = (cluster.maxOfOrNull { it.lane } ?: 0) + 1
            cluster.forEach { it.lanes = lanes }
        }
        cells.forEach { cell ->
            if (cell.startSection > clusterEnd) {
                close()
                cluster = mutableListOf()
            }
            val used = cluster.filter { it.endSection >= cell.startSection }.map { it.lane }.toSet()
            cell.lane = generateSequence(0) { it + 1 }.first { it !in used }
            cluster += cell
            clusterEnd = max(clusterEnd, cell.endSection)
        }
        close()
    }

    /** 块里先写课程名（尽量多行），放得下再写教室；文字超出块时裁掉。 */
    private fun drawCellText(canvas: Canvas, cell: Cell, rect: RectF, name: TextPaint, place: TextPaint, dp: Float) {
        val pad = 2.5f * dp
        val textWidth = (rect.width() - pad * 2f).roundToInt()
        val textHeight = rect.height() - pad * 2f
        if (textWidth <= 0 || textHeight < name.textSize) return
        name.color = cell.ink
        place.color = Color.argb(215, Color.red(cell.ink), Color.green(cell.ink), Color.blue(cell.ink))
        val nameLineHeight = name.fontSpacing
        val nameLines = max(1, min(4, (textHeight / nameLineHeight).toInt()))
        val nameLayout = layout(cell.title, name, textWidth, nameLines)
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left + pad, rect.top + pad)
        nameLayout.draw(canvas)
        val remaining = textHeight - nameLayout.height - 1f * dp
        if (cell.place.isNotBlank() && remaining >= place.fontSpacing) {
            val placeLayout = layout(cell.place, place, textWidth, max(1, min(3, (remaining / place.fontSpacing).toInt())))
            canvas.translate(0f, nameLayout.height + 1f * dp)
            placeLayout.draw(canvas)
        }
        canvas.restore()
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
        val fm = paint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }
}
