package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.WeekendDisplayMode
import com.zhusijiao.app.domain.export.ExportFixtures.event
import com.zhusijiao.app.domain.export.ExportFixtures.holiday
import com.zhusijiao.app.domain.export.ExportFixtures.leave
import com.zhusijiao.app.domain.export.ExportFixtures.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ScheduleXlsxTest {

    /** 解压出每个部件的内容；顺带确认每份 XML 都是良构的。 */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val parts = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        parts.values.forEach { parse(it) }
        return parts
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun elements(doc: Document, tag: String): List<Element> {
        val list = doc.getElementsByTagNameNS("*", tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun export(
        options: ExportOptions,
        s: com.zhusijiao.app.domain.Schedule = schedule(holidays = listOf(holiday(1, 5))),
        events: List<com.zhusijiao.app.domain.PersonalEvent> = emptyList(),
        leaves: List<com.zhusijiao.app.domain.Leave> = emptyList()
    ): Map<String, String> {
        val snapshot = ExportSnapshot.build(s, emptyList(), events, leaves, WeekendDisplayMode.AUTO, options)
        val out = ByteArrayOutputStream()
        ScheduleXlsx.write(snapshot, out)
        return unzip(out.toByteArray())
    }

    @Test
    fun refAndEscape() {
        assertEquals("A1", XlsxWriter.ref(0, 0))
        assertEquals("Z3", XlsxWriter.ref(2, 25))
        assertEquals("AA1", XlsxWriter.ref(0, 26))
        assertEquals("AB10", XlsxWriter.ref(9, 27))
        assertEquals("a&amp;b&lt;c&gt;&quot;\n", XlsxWriter.escape("a&b<c>\"\u0001\n"))
    }

    @Test
    fun sheetNamesAreCleanedAndUnique() {
        val book = XlsxWriter()
        val a = book.sheet("第1周")
        val b = book.sheet("第1周")
        val c = book.sheet("a/b:c*d?[e]")
        val d = book.sheet("x".repeat(40))
        assertEquals("第1周", a.name)
        assertEquals("第1周(2)", b.name)
        assertFalse(c.name.any { it in "[]:*?/\\" })
        assertEquals(31, d.name.length)
    }

    @Test
    fun oneSheetPerWeekAndAllPartsPresent() {
        val parts = export(ExportOptions(weeks = (1..3).toList()))
        listOf(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml"
        ).forEach { assertTrue("缺少 $it", it in parts) }
        val sheets = elements(parse(parts.getValue("xl/workbook.xml")), "sheet").map { it.getAttribute("name") }
        assertEquals(listOf("第1周", "第2周", "第3周"), sheets)
        // 每个工作表都在 [Content_Types] 里登记
        assertEquals(3, Regex("worksheets/sheet\\d+\\.xml").findAll(parts.getValue("[Content_Types].xml")).count())
    }

    @Test
    fun coursesAreMergedAndStylesResolve() {
        val parts = export(ExportOptions(weeks = (1..1).toList()))
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        val merges = elements(sheet, "mergeCell").map { it.getAttribute("ref") }
        // 标题、图例，以及高等数学（周一 1–2 节 = B3:B4）、大学英语（周三 3–4 节 = D5:D6）
        assertTrue(merges.contains("A1:F1"))
        assertTrue(merges.contains("B3:B4"))
        assertTrue(merges.contains("D5:D6"))
        val text = sheet.documentElement.textContent
        assertTrue(text.contains("高等数学"))
        assertTrue(text.contains("停课"))
        val styleCount = elements(parse(parts.getValue("xl/styles.xml")), "cellXfs").single().getAttribute("count").toInt()
        elements(sheet, "c").forEach { cell -> assertTrue(cell.getAttribute("s").toInt() < styleCount) }
        assertEquals("frozen", elements(sheet, "pane").single().getAttribute("state"))
    }

    @Test
    fun overlappingBlocksBecomeOneCluster() {
        val events = listOf(event("社团", day = 1, start = 2, end = 3, weeks = listOf(1)))
        val parts = export(ExportOptions(weeks = (1..1).toList()), events = events)
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        val merges = elements(sheet, "mergeCell").map { it.getAttribute("ref") }
        // 高等数学 1–2 节与社团 2–3 节重叠：合成 B3:B5 一块，不能各自合并
        assertTrue(merges.contains("B3:B5"))
        assertFalse(merges.contains("B3:B4"))
        assertNoOverlappingMerges(merges)
        val text = sheet.documentElement.textContent
        assertTrue(text.contains("1–2节 "))
        assertTrue(text.contains("◆ 社团"))
    }

    @Test
    fun leavesAndEventsUseDistinctMarkers() {
        val events = listOf(event("社团", day = 2, start = 7, end = 8, weeks = listOf(1)))
        val leaves = listOf(leave("2026-09-07 07:00", "2026-09-07 10:00"))
        val parts = export(ExportOptions(weeks = (1..1).toList(), markLeaves = true), events = events, leaves = leaves)
        val text = parse(parts.getValue("xl/worksheets/sheet1.xml")).documentElement.textContent
        assertTrue(text.contains("【请假】高等数学"))
        assertTrue(text.contains("◆ 社团"))
        assertTrue(parts.getValue("xl/styles.xml").contains("mediumDashed"))
        assertTrue(parts.getValue("xl/styles.xml").contains("<strike/>"))
    }

    private fun assertNoOverlappingMerges(refs: List<String>) {
        fun bounds(ref: String): IntArray {
            val (a, b) = ref.split(":")
            fun cell(r: String): Pair<Int, Int> {
                val letters = r.takeWhile { it.isLetter() }
                val col = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }
                return r.drop(letters.length).toInt() to col
            }
            val (r1, c1) = cell(a)
            val (r2, c2) = cell(b)
            return intArrayOf(r1, c1, r2, c2)
        }
        val boxes = refs.map(::bounds)
        boxes.forEachIndexed { i, x ->
            boxes.drop(i + 1).forEach { y ->
                val overlap = x[0] <= y[2] && y[0] <= x[2] && x[1] <= y[3] && y[1] <= x[3]
                assertFalse("合并区域重叠：${x.toList()} ${y.toList()}", overlap)
            }
        }
    }
}
