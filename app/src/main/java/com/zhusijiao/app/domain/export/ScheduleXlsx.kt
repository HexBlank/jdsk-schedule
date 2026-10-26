package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.LeaveType
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.export.XlsxWriter.Border
import com.zhusijiao.app.domain.export.XlsxWriter.HAlign
import com.zhusijiao.app.domain.export.XlsxWriter.Run
import com.zhusijiao.app.domain.export.XlsxWriter.Style
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把 [ExportSnapshot] 排成 Excel：一周一张工作表。
 *
 * 版式：第 1 行标题（课表名 · 第几周 · 日期范围），第 2 行表头（星期、日期、停课/补课标注），
 * 第 1 列节次与作息；每门课按节次纵向合并成一块。冻结表头和节次列，横向打印缩到一页。
 *
 * 各类格子一眼能分开，而且每格自己写明是什么，不靠图例（见 docs/UI_GUIDELINES.md 界面原则）：
 * - 课程：底色、字色与 App 里的课程色一致；调课、补课进来的末尾标「（调课）」「（补课）」；
 * - 日程：日程色浅底加虚线框（同 App 里的日程块），首行「◆ 标题」；
 * - 请假：灰底灰字、课程名划删除线，首行「【请假】」「【公假】」；
 * - 停课日：整列浅灰（日程不受停课影响，照常画）；
 * - 时间重叠：有重叠的块聚成一组、按整组的节次合并，逐条列出并标节次，橙色粗框提醒。
 *   重叠的区域不能各自合并，那样 Excel 会报文件损坏。
 */
object ScheduleXlsx {

    private const val HEADER_ROWS = 2
    private const val LINE_POINTS = 13.5
    private const val CELL_PADDING = 8.0
    private const val MIN_SECTION_HEIGHT = 40.0

    private const val GRID = 0xFFE1E5E3.toInt()
    private const val HEADER_LINE = 0xFFD3D9D6.toInt()
    private const val MUTED = 0xFF8A9490.toInt()
    private const val SUB = 0xFF5A6360.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val CONFLICT_FILL = 0xFFFFF4E0.toInt()
    private const val CONFLICT_LINE = 0xFFD9822B.toInt()

    private val titleStyle = Style(fontSize = 13.0, bold = true, wrap = false, hAlign = HAlign.LEFT)
    private val headerStyle = Style(fill = 0xFFF3F5F4.toInt(), bold = true, border = Border.THIN, borderColor = HEADER_LINE)
    private val headerSuspendedStyle = headerStyle.copy(fill = 0xFFE6E8E7.toInt(), fontColor = MUTED)
    private val headerMakeupStyle = headerStyle.copy(fill = CONFLICT_FILL)
    private val sectionStyle = Style(
        fill = 0xFFF7F8F7.toInt(), fontColor = SUB, fontSize = 9.0, border = Border.THIN, borderColor = HEADER_LINE
    )
    private val emptyStyle = Style(border = Border.THIN, borderColor = GRID)
    private val suspendedStyle = emptyStyle.copy(fill = 0xFFF0F1F0.toInt())
    private val leaveStyle = Style(
        fill = 0xFFEEF0EF.toInt(), fontColor = MUTED, strike = true, border = Border.THIN, borderColor = 0xFFC9CFCC.toInt()
    )
    private val conflictStyle = Style(
        fill = CONFLICT_FILL, fontSize = 9.0, border = Border.MEDIUM, borderColor = CONFLICT_LINE, hAlign = HAlign.LEFT
    )

    /** 一个格子要写的内容：分段文字 + 样式，外加它占几行文字（估行高用）。 */
    private class Block(val runs: List<Run>, val style: Style) {
        val lines: Int get() = runs.joinToString("") { it.text }.count { it == '\n' } + 1
    }

    fun write(snapshot: ExportSnapshot.Snapshot, output: OutputStream) {
        val book = XlsxWriter()
        if (snapshot.weeks.isEmpty()) {
            book.sheet("课表").text(0, 0, snapshot.scheduleName, titleStyle)
        }
        snapshot.weeks.forEach { week -> writeWeek(book, snapshot, week) }
        book.write(output)
    }

    private fun writeWeek(book: XlsxWriter, snapshot: ExportSnapshot.Snapshot, week: ExportSnapshot.Week) {
        val sheet = book.sheet("第${week.week}周")
        val lastCol = week.days.size
        val sections = snapshot.sections
        val dayWidth = if (week.days.size > 5) 15.0 else 19.0
        sheet.columnWidth(0, 12.0)
        week.days.indices.forEach { sheet.columnWidth(it + 1, dayWidth) }

        // 标题
        val range = week.days.filter { it.dateIso != null }.let { dated ->
            if (dated.isEmpty()) "" else " · ${dated.first().month}/${dated.first().dayOfMonth}–${dated.last().month}/${dated.last().dayOfMonth}"
        }
        sheet.text(0, 0, "${snapshot.scheduleName} · 第${week.week}周$range", titleStyle)
        sheet.merge(0, 0, 0, lastCol, titleStyle)
        sheet.rowHeight(0, 26.0)

        // 表头
        sheet.text(1, 0, "节次", headerStyle)
        var headerLines = 2
        week.days.forEachIndexed { index, day ->
            val lines = mutableListOf(Leaves.dayName(day.day))
            if (day.dateIso != null) lines += "${day.month}/${day.dayOfMonth}"
            val note = when {
                day.suspended -> "停课"
                day.makeupOf != null -> "补第${day.makeupOf.sourceWeek}周${Leaves.dayName(day.makeupOf.sourceDay)}的课"
                day.movedTo != null -> "课已补到第${day.movedTo.targetWeek}周${Leaves.dayName(day.movedTo.targetDay)}"
                else -> null
            }
            if (note != null) lines += note
            headerLines = max(headerLines, lines.size)
            val style = when {
                day.suspended -> headerSuspendedStyle
                day.makeupOf != null -> headerMakeupStyle
                else -> headerStyle
            }
            sheet.text(1, index + 1, lines.joinToString("\n"), style)
        }
        sheet.rowHeight(1, headerLines * LINE_POINTS + CELL_PADDING)

        // 节次列与空网格
        sections.forEachIndexed { index, slot ->
            val row = HEADER_ROWS + index
            sheet.text(row, 0, "第${slot.number}节\n${slot.startTime}–${slot.endTime}", sectionStyle)
            week.days.forEachIndexed { dayIndex, day ->
                sheet.blank(row, dayIndex + 1, if (day.suspended) suspendedStyle else emptyStyle)
            }
        }

        // 课程与日程
        val rowNeeds = DoubleArray(sections.size) { MIN_SECTION_HEIGHT }
        week.days.forEachIndexed { dayIndex, day ->
            val col = dayIndex + 1
            clusters(week.items.filter { it.day == day.day }, sections.size).forEach { cluster ->
                val first = cluster.minOf { it.startSection }.coerceIn(1, sections.size)
                val last = cluster.maxOf { it.endSection }.coerceIn(first, sections.size)
                val block = if (cluster.size == 1) single(cluster[0]) else conflict(cluster)
                val firstRow = HEADER_ROWS + first - 1
                val lastRow = HEADER_ROWS + last - 1
                sheet.runs(firstRow, col, block.runs, block.style)
                sheet.merge(firstRow, col, lastRow, col, block.style)
                // 合并块的文字摊到它占的每一行上估高度
                val span = last - first + 1
                val perRow = (block.lines * LINE_POINTS + CELL_PADDING) / span
                for (section in first..last) rowNeeds[section - 1] = max(rowNeeds[section - 1], perRow)
            }
        }
        rowNeeds.forEachIndexed { index, need -> sheet.rowHeight(HEADER_ROWS + index, ceil(need)) }

        sheet.freeze(HEADER_ROWS, 1)
        sheet.printLandscapeOnePage()
    }

    /** 同一天里节次有重叠的块聚成一组（传递闭包：A 撞 B、B 撞 C，三者一组）。 */
    private fun clusters(items: List<ExportSnapshot.Item>, sectionCount: Int): List<List<ExportSnapshot.Item>> {
        val sorted = items.filter { it.startSection <= sectionCount }.sortedWith(compareBy({ it.startSection }, { it.endSection }))
        val result = mutableListOf<MutableList<ExportSnapshot.Item>>()
        var end = 0
        sorted.forEach { item ->
            if (result.isEmpty() || item.startSection > end) {
                result += mutableListOf(item)
                end = item.endSection
            } else {
                result.last() += item
                end = max(end, item.endSection)
            }
        }
        return result
    }

    private fun single(item: ExportSnapshot.Item): Block {
        if (item.isEvent) {
            val lines = listOfNotNull(
                item.position.takeIf { it.isNotBlank() },
                if (item.customTime) "${item.startTime}–${item.endTime}" else null
            )
            return Block(
                listOf(Run("◆ ${item.title}", bold = true)) + lines.map { Run("\n$it") },
                // 与 BlockStyles 的日程配色一致：日程色 14% 的浅底、文字向墨色加深。
                // 虚线要用 medium 粗细：细虚线和旁边空格子的网格细线共边时会被网格线盖掉
                Style(
                    fill = blend(item.tone, WHITE, 0.86f),
                    fontColor = blend(item.tone, XlsxWriter.INK, 0.45f),
                    border = Border.MEDIUM_DASHED,
                    borderColor = item.tone
                )
            )
        }
        val details = listOfNotNull(
            item.position.takeIf { it.isNotBlank() },
            item.teacher.takeIf { it.isNotBlank() },
            tagOf(item)
        )
        val leave = item.leave
        if (leave != null) {
            return Block(
                listOf(Run(leaveLabel(leave), bold = true, strike = false), Run(item.title, bold = true)) +
                    details.map { Run("\n$it") },
                leaveStyle
            )
        }
        return Block(
            listOf(Run(item.title, bold = true)) + details.map { Run("\n$it") },
            Style(fill = item.tone, fontColor = item.ink, border = Border.THIN, borderColor = WHITE)
        )
    }

    private fun conflict(items: List<ExportSnapshot.Item>): Block {
        val runs = mutableListOf<Run>()
        items.forEachIndexed { index, item ->
            val prefix = if (index > 0) "\n" else ""
            val sections = if (item.startSection == item.endSection) "第${item.startSection}节"
            else "${item.startSection}–${item.endSection}节"
            val label = when {
                item.isEvent -> "◆ "
                item.leave != null -> leaveLabel(item.leave)
                else -> ""
            }
            val extra = listOfNotNull(item.position.takeIf { it.isNotBlank() }, tagOf(item)).joinToString(" · ")
            runs += Run("$prefix$sections ", bold = false)
            runs += Run("$label${item.title}", bold = true)
            if (extra.isNotEmpty()) runs += Run(" · $extra", bold = false)
        }
        return Block(runs, conflictStyle)
    }

    private fun tagOf(item: ExportSnapshot.Item): String? = when (item.kind) {
        ExportSnapshot.Kind.ADJUSTED -> "（调课）"
        ExportSnapshot.Kind.MAKEUP -> "（补课）"
        else -> null
    }

    private fun leaveLabel(type: LeaveType) = "【${type.label}】"

    /** 与 BlockStyles.blend 同一算法：按 [amount] 把 [color] 往 [toward] 混。 */
    private fun blend(color: Int, toward: Int, amount: Float): Int {
        fun mix(shift: Int) = ((color shr shift and 0xff) * (1f - amount) + (toward shr shift and 0xff) * amount)
            .roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
