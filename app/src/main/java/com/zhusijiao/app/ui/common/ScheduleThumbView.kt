package com.zhusijiao.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.zhusijiao.app.R
import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleView

/**
 * 课表库卡片左侧的课表缩略图：一周的课按星期、节次画成小色块，颜色与课表里一致，
 * 几张卡片放在一起时靠「形状」就能认出是哪张课表。不区分周次，同一时段的课只画一次。
 */
class ScheduleThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Cell(val day: Int, val start: Int, val end: Int, val color: Int)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.background) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.line) }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var cells: List<Cell> = emptyList()
    private var days = 5
    private var sections = 10

    fun setSchedule(schedule: Schedule) {
        val courses = schedule.courses.filter { it.day in 1..7 && it.startSection >= 1 && it.endSection >= it.startSection }
        val palette = ScheduleView.buildCoursePaletteMap(courses, schedule.courseColors)
        cells = courses
            .distinctBy { Triple(it.day, it.startSection, it.endSection) }
            .map { Cell(it.day, it.startSection, it.endSection, colorOf(it, palette)) }
        days = if (cells.any { it.day >= 6 }) 7 else 5
        sections = maxOf(10, cells.maxOfOrNull { it.end } ?: 0)
        invalidate()
    }

    private fun colorOf(course: Course, palette: Map<String, ScheduleView.Palette>): Int =
        (palette[course.name] ?: ScheduleView.COURSE_PALETTES[0]).background

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = dp(8f)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val pad = dp(5f)
        val gap = dp(1.5f)
        val colW = (w - pad * 2) / days
        val rowH = (h - pad * 2) / sections
        // 淡淡的列分隔，空课表也能看出是张课表
        for (d in 1 until days) {
            val x = pad + colW * d
            canvas.drawRect(x - dp(0.25f), pad, x + dp(0.25f), h - pad, gridPaint)
        }
        val cellRadius = dp(1.5f)
        cells.forEach { c ->
            if (c.day > days) return@forEach
            val left = pad + colW * (c.day - 1) + gap / 2f
            val top = pad + rowH * (c.start - 1) + gap / 2f
            rect.set(left, top, left + colW - gap, pad + rowH * c.end - gap / 2f)
            cellPaint.color = c.color
            canvas.drawRoundRect(rect, cellRadius, cellRadius, cellPaint)
        }
    }
}
