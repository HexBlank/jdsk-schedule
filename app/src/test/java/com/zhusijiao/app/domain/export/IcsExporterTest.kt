package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.WeekendDisplayMode
import com.zhusijiao.app.domain.export.ExportFixtures.event
import com.zhusijiao.app.domain.export.ExportFixtures.leave
import com.zhusijiao.app.domain.export.ExportFixtures.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsExporterTest {

    private fun ics(
        options: ExportOptions,
        events: List<com.zhusijiao.app.domain.PersonalEvent> = emptyList(),
        leaves: List<com.zhusijiao.app.domain.Leave> = emptyList()
    ): String {
        val snapshot = ExportSnapshot.build(schedule(), emptyList(), events, leaves, WeekendDisplayMode.AUTO, options)
        return IcsExporter.build(snapshot, options, nowMillis = 0L)
    }

    /** 展开折行（RFC 5545 §3.1）：CRLF 后跟一个空格的是续行。 */
    private fun unfold(text: String) = text.replace("\r\n ", "")

    @Test
    fun oneEventPerOccurrenceWithWallClockTimes() {
        val text = ics(ExportOptions(weeks = (1..1).toList()))
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(text).count())
        val lines = unfold(text).split("\r\n")
        assertTrue(lines.contains("DTSTART;TZID=Asia/Shanghai:20260907T075000"))
        assertTrue(lines.contains("DTEND;TZID=Asia/Shanghai:20260907T093000"))
        assertTrue(lines.contains("SUMMARY:高等数学"))
        assertTrue(lines.contains("LOCATION:A101"))
        assertTrue(lines.contains("DTSTAMP:19700101T000000Z"))
        assertTrue(text.contains("BEGIN:VTIMEZONE"))
        assertFalse(text.contains("VALARM"))
    }

    @Test
    fun alarmAndLeaveHandling() {
        val leaves = listOf(leave("2026-09-07 08:00", "2026-09-07 09:00"))
        val text = unfold(ics(ExportOptions(weeks = (1..1).toList(), markLeaves = true, alarmMinutes = 15), leaves = leaves))
        assertTrue(text.contains("SUMMARY:【请假】高等数学"))
        assertTrue(text.contains("TRANSP:TRANSPARENT"))
        // 请假的课不提醒，另一节照常提醒
        assertEquals(1, Regex("BEGIN:VALARM").findAll(text).count())
        assertTrue(text.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun eventsUseTheirOwnTime() {
        val events = listOf(event("社团", day = 2, start = 9, end = 10, weeks = listOf(1), startTime = "19:00", endTime = "20:30"))
        val text = unfold(ics(ExportOptions(weeks = (1..1).toList()), events = events))
        assertTrue(text.contains("DTSTART;TZID=Asia/Shanghai:20260908T190000"))
        assertTrue(text.contains("CATEGORIES:日程"))
    }

    @Test
    fun escapeAndFold() {
        assertEquals("a\\,b\\;c\\\\d\\ne", IcsExporter.escape("a,b;c\\d\ne"))
        val long = "SUMMARY:" + "高等数学与线性代数".repeat(8) + "😀".repeat(5)
        val folded = IcsExporter.fold(long)
        folded.split("\r\n").forEach { line ->
            assertTrue("超过 75 字节：$line", line.toByteArray(Charsets.UTF_8).size <= 75)
        }
        assertEquals(long, unfold(folded))
        assertEquals("SHORT:ok", IcsExporter.fold("SHORT:ok"))
    }

    @Test
    fun cannotExportWithoutDates() {
        val snapshot = ExportSnapshot.build(
            schedule(semesterStart = ""), emptyList(), emptyList(), emptyList(), WeekendDisplayMode.AUTO, ExportOptions()
        )
        assertFalse(IcsExporter.canExport(snapshot))
    }
}
