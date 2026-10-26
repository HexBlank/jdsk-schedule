package com.zhusijiao.app.domain.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极简 xlsx 写入器：只做课表导出用得到的那点 SpreadsheetML——纯文本单元格（可分段加粗）、
 * 填充色、字色、删除线、实线/虚线边框、合并单元格、列宽行高、冻结窗格、横向打印缩到一页。
 *
 * 不引入 Apache POI：它有好几 MB，会把 release 包撑破 1.5MB 的硬要求（见 docs/DECISIONS.md D26）。
 * xlsx 本身就是一个 zip 包里放几份 XML，用 [ZipOutputStream] 手写即可。
 *
 * 约定：行列号都从 0 开始；颜色是 ARGB Int。单元格用 inlineStr 直接写文字，不建 sharedStrings 表。
 */
class XlsxWriter {

    /** 边框线型。相邻格子共用一条边时 Excel 画「更重」的那种，所以要压过网格细线得用 MEDIUM 系。 */
    enum class Border { NONE, THIN, DASHED, MEDIUM, MEDIUM_DASHED }
    enum class HAlign { LEFT, CENTER }

    /** 单元格样式；相同样式只在 styles.xml 里写一次。 */
    data class Style(
        val fill: Int? = null,
        val fontColor: Int = INK,
        val fontSize: Double = 10.0,
        val bold: Boolean = false,
        val strike: Boolean = false,
        val border: Border = Border.NONE,
        val borderColor: Int = INK,
        val wrap: Boolean = true,
        val hAlign: HAlign = HAlign.CENTER
    )

    /** 一段文字；[bold]、[strike] 为 null 时跟随单元格样式。 */
    data class Run(val text: String, val bold: Boolean? = null, val strike: Boolean? = null)

    internal class Cell(val runs: List<Run>, val style: Int)

    inner class Sheet internal constructor(val name: String) {
        internal val cells = sortedMapOf<Int, java.util.SortedMap<Int, Cell>>()
        internal val merges = mutableListOf<IntArray>()
        internal val colWidths = sortedMapOf<Int, Double>()
        internal val rowHeights = mutableMapOf<Int, Double>()
        internal var freezeRows = 0
        internal var freezeCols = 0
        internal var landscape = false

        fun text(row: Int, col: Int, text: String, style: Style) = runs(row, col, listOf(Run(text)), style)

        fun runs(row: Int, col: Int, runs: List<Run>, style: Style) {
            cells.getOrPut(row) { sortedMapOf() }[col] = Cell(runs, styleIndex(style))
        }

        /** 只有样式没有文字的格子（网格线、合并区域里的其余格子）。 */
        fun blank(row: Int, col: Int, style: Style) = runs(row, col, emptyList(), style)

        /**
         * 合并 [firstRow]..[lastRow] × [firstCol]..[lastCol]，并给区域里每一格都写上 [style]：
         * Excel 画合并区域的边框时看的是边缘那些格子自己的边框，只写左上角那格会缺边。
         * 文字写在左上角，调用方先写文字再合并或反过来都行。
         */
        fun merge(firstRow: Int, firstCol: Int, lastRow: Int, lastCol: Int, style: Style) {
            for (r in firstRow..lastRow) for (c in firstCol..lastCol) {
                val existing = cells[r]?.get(c)
                if (existing == null || r != firstRow || c != firstCol) blank(r, c, style)
            }
            if (firstRow != lastRow || firstCol != lastCol) merges += intArrayOf(firstRow, firstCol, lastRow, lastCol)
        }

        /** 列宽，单位是 Excel 的「字符宽」。 */
        fun columnWidth(col: Int, width: Double) {
            colWidths[col] = width
        }

        /** 行高，单位是磅。 */
        fun rowHeight(row: Int, points: Double) {
            rowHeights[row] = points
        }

        /** 冻结前 [rows] 行、前 [cols] 列。 */
        fun freeze(rows: Int, cols: Int) {
            freezeRows = rows
            freezeCols = cols
        }

        /** 打印：横向，整张表缩到一页。 */
        fun printLandscapeOnePage() {
            landscape = true
        }
    }

    private val sheets = mutableListOf<Sheet>()
    private val styles = linkedMapOf(Style(wrap = false) to 0)

    /** 新建工作表；表名会按 Excel 的规则清理（≤31 字符、去掉 []:*?/\、不与已有表重名）。 */
    fun sheet(name: String): Sheet = Sheet(uniqueSheetName(name)).also { sheets += it }

    private fun styleIndex(style: Style): Int = styles.getOrPut(style) { styles.size }

    private fun uniqueSheetName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().trim('\'').ifBlank { "Sheet" }.take(31)
        var name = cleaned
        var n = 2
        while (sheets.any { it.name.equals(name, ignoreCase = true) }) {
            val suffix = "($n)"
            name = cleaned.take(31 - suffix.length) + suffix
            n++
        }
        return name
    }

    fun write(output: OutputStream) {
        val sheetList = sheets.ifEmpty { listOf(sheet("Sheet1")) }
        val zip = ZipOutputStream(output)
        fun entry(path: String, content: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", contentTypes(sheetList.size))
        entry("_rels/.rels", ROOT_RELS)
        entry("xl/workbook.xml", workbook(sheetList))
        entry("xl/_rels/workbook.xml.rels", workbookRels(sheetList.size))
        sheetList.forEachIndexed { index, sheet -> entry("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet, index == 0)) }
        // 样式最后写：工作表里用到的样式都已登记
        entry("xl/styles.xml", stylesXml())
        zip.finish()
        zip.flush()
    }

    // ===== 各部件 XML =====

    private fun contentTypes(count: Int) = buildString {
        append(XML_HEAD)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in 1..count) {
            append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun workbook(list: List<Sheet>) = buildString {
        append(XML_HEAD)
        append("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>")
        list.forEachIndexed { index, sheet ->
            append("<sheet name=\"${escape(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append(XML_HEAD)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..count) {
            append("<Relationship Id=\"rId$i\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        append("<Relationship Id=\"rId${count + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    /** 元素顺序是 schema 规定死的：sheetPr → sheetViews → sheetFormatPr → cols → sheetData → mergeCells → 打印设置。 */
    private fun worksheet(sheet: Sheet, selected: Boolean) = buildString {
        append(XML_HEAD)
        append("<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">")
        if (sheet.landscape) append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
        append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (selected) append(" tabSelected=\"1\"")
        if (sheet.freezeRows > 0 || sheet.freezeCols > 0) {
            val topLeft = ref(sheet.freezeRows, sheet.freezeCols)
            val pane = when {
                sheet.freezeRows > 0 && sheet.freezeCols > 0 -> "bottomRight"
                sheet.freezeRows > 0 -> "bottomLeft"
                else -> "topRight"
            }
            append("><pane")
            if (sheet.freezeCols > 0) append(" xSplit=\"${sheet.freezeCols}\"")
            if (sheet.freezeRows > 0) append(" ySplit=\"${sheet.freezeRows}\"")
            append(" topLeftCell=\"$topLeft\" activePane=\"$pane\" state=\"frozen\"/>")
            append("<selection pane=\"$pane\" activeCell=\"$topLeft\" sqref=\"$topLeft\"/></sheetView>")
        } else {
            append("/>")
        }
        append("</sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        if (sheet.colWidths.isNotEmpty()) {
            append("<cols>")
            sheet.colWidths.forEach { (col, width) ->
                append("<col min=\"${col + 1}\" max=\"${col + 1}\" width=\"${num(width)}\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        val rows = (sheet.cells.keys + sheet.rowHeights.keys).toSortedSet()
        rows.forEach { row ->
            append("<row r=\"${row + 1}\"")
            sheet.rowHeights[row]?.let { append(" ht=\"${num(it)}\" customHeight=\"1\"") }
            append(">")
            sheet.cells[row]?.forEach { (col, cell) -> appendCell(row, col, cell) }
            append("</row>")
        }
        append("</sheetData>")
        if (sheet.merges.isNotEmpty()) {
            append("<mergeCells count=\"${sheet.merges.size}\">")
            sheet.merges.forEach { (r1, c1, r2, c2) -> append("<mergeCell ref=\"${ref(r1, c1)}:${ref(r2, c2)}\"/>") }
            append("</mergeCells>")
        }
        append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
        if (sheet.landscape) append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"1\"/>")
        append("</worksheet>")
    }

    private fun StringBuilder.appendCell(row: Int, col: Int, cell: Cell) {
        append("<c r=\"${ref(row, col)}\" s=\"${cell.style}\"")
        val runs = cell.runs.filter { it.text.isNotEmpty() }
        if (runs.isEmpty()) {
            append("/>")
            return
        }
        append(" t=\"inlineStr\"><is>")
        if (runs.size == 1 && runs[0].bold == null && runs[0].strike == null) {
            append("<t xml:space=\"preserve\">${escape(runs[0].text)}</t>")
        } else {
            // 分段文字的每一段都要带全字体属性：缺省的那几项会回退到工作簿默认字体，而不是单元格的字体
            val style = styles.entries.first { it.value == cell.style }.key
            runs.forEach { run ->
                append("<r><rPr>")
                if (run.bold ?: style.bold) append("<b/>")
                if (run.strike ?: style.strike) append("<strike/>")
                append("<sz val=\"${num(style.fontSize)}\"/><color rgb=\"${hex(style.fontColor)}\"/><rFont val=\"$FONT\"/><family val=\"2\"/>")
                append("</rPr><t xml:space=\"preserve\">${escape(run.text)}</t></r>")
            }
        }
        append("</is></c>")
    }

    private fun stylesXml(): String {
        val list = styles.keys.toList()
        data class FontKey(val color: Int, val size: Double, val bold: Boolean, val strike: Boolean)
        data class BorderKey(val border: Border, val color: Int)
        val fonts = list.map { FontKey(it.fontColor, it.fontSize, it.bold, it.strike) }.distinct()
        // 前两个填充是 Excel 保留的（none、gray125），自定义填充从 2 开始
        val fills = list.mapNotNull { it.fill }.distinct()
        val borders = (listOf(BorderKey(Border.NONE, INK)) + list.map {
            BorderKey(it.border, if (it.border == Border.NONE) INK else it.borderColor)
        }).distinct()
        return buildString {
            append(XML_HEAD)
            append("<styleSheet xmlns=\"$NS_MAIN\">")
            append("<fonts count=\"${fonts.size}\">")
            fonts.forEach { font ->
                append("<font>")
                if (font.bold) append("<b/>")
                if (font.strike) append("<strike/>")
                append("<sz val=\"${num(font.size)}\"/><color rgb=\"${hex(font.color)}\"/><name val=\"$FONT\"/><family val=\"2\"/>")
                append("</font>")
            }
            append("</fonts>")
            append("<fills count=\"${fills.size + 2}\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
            fills.forEach { append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${hex(it)}\"/><bgColor indexed=\"64\"/></patternFill></fill>") }
            append("</fills>")
            append("<borders count=\"${borders.size}\">")
            borders.forEach { key ->
                val side = when (key.border) {
                    Border.NONE -> null
                    Border.THIN -> "thin"
                    Border.DASHED -> "dashed"
                    Border.MEDIUM -> "medium"
                    Border.MEDIUM_DASHED -> "mediumDashed"
                }
                append("<border>")
                for (edge in listOf("left", "right", "top", "bottom")) {
                    if (side == null) append("<$edge/>")
                    else append("<$edge style=\"$side\"><color rgb=\"${hex(key.color)}\"/></$edge>")
                }
                append("<diagonal/></border>")
            }
            append("</borders>")
            append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
            append("<cellXfs count=\"${list.size}\">")
            list.forEach { style ->
                val fontId = fonts.indexOf(FontKey(style.fontColor, style.fontSize, style.bold, style.strike))
                val fillId = style.fill?.let { fills.indexOf(it) + 2 } ?: 0
                val borderId = borders.indexOf(
                    BorderKey(style.border, if (style.border == Border.NONE) INK else style.borderColor)
                )
                val horizontal = if (style.hAlign == HAlign.LEFT) "left" else "center"
                append("<xf numFmtId=\"0\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\" ")
                append("applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">")
                append("<alignment horizontal=\"$horizontal\" vertical=\"center\"")
                if (style.wrap) append(" wrapText=\"1\"")
                append("/></xf>")
            }
            append("</cellXfs>")
            append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
            append("</styleSheet>")
        }
    }

    companion object {
        const val INK = 0xFF1D2421.toInt()
        private const val FONT = "微软雅黑"
        private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val ROOT_RELS = XML_HEAD +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

        /** (0, 0) → "A1"，(2, 27) → "AB3"。 */
        fun ref(row: Int, col: Int): String {
            val letters = StringBuilder()
            var n = col + 1
            while (n > 0) {
                val rem = (n - 1) % 26
                letters.insert(0, ('A' + rem))
                n = (n - 1) / 26
            }
            return "$letters${row + 1}"
        }

        /** XML 转义，并去掉 XML 1.0 不允许出现的控制字符（留下制表、换行、回车）。 */
        fun escape(text: String): String = buildString(text.length) {
            text.forEach { ch ->
                when {
                    ch == '&' -> append("&amp;")
                    ch == '<' -> append("&lt;")
                    ch == '>' -> append("&gt;")
                    ch == '"' -> append("&quot;")
                    ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                    ch < ' ' || ch == '￾' || ch == '￿' -> Unit
                    else -> append(ch)
                }
            }
        }

        private fun hex(argb: Int): String = "FF%06X".format(java.util.Locale.US, argb and 0xFFFFFF)

        private fun num(value: Double): String =
            if (value == Math.floor(value)) value.toLong().toString() else "%.2f".format(java.util.Locale.US, value)
    }
}
