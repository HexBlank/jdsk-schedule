package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.EamsParser
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.export.ExportFixtures.adjustMath
import com.zhusijiao.app.domain.export.ExportFixtures.event
import com.zhusijiao.app.domain.export.ExportFixtures.extra
import com.zhusijiao.app.domain.export.ExportFixtures.holiday
import com.zhusijiao.app.domain.export.ExportFixtures.leave
import com.zhusijiao.app.domain.export.ExportFixtures.makeup
import com.zhusijiao.app.domain.export.ExportFixtures.math
import com.zhusijiao.app.domain.export.ExportFixtures.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScheduleBackupTest {

    private val full = schedule(
        adjustments = listOf(adjustMath(3)),
        holidays = listOf(holiday(4, 3)),
        makeups = listOf(makeup(5, 1, 5, 6)),
        courseColors = mapOf("高等数学" to "#AA3355")
    )
    private val extras = listOf(extra("物理实验", day = 2, start = 5, end = 6, color = "#123456"))
    private val events = listOf(event("社团", day = 2, start = 9, end = 10, startTime = "19:00", endTime = "20:30"))
    private val leaves = listOf(
        leave("2026-09-07 08:00", "2026-09-07 12:00", id = "leave-in"),
        leave("2026-03-01 08:00", "2026-03-01 12:00", id = "leave-before")
    )

    private fun encode(options: ExportOptions = ExportOptions(markLeaves = true)) =
        ScheduleBackup.encode(full, extras, events, leaves, options, "9.9.9", "2026-10-26T00:00:00.000Z").toString()

    private fun base(from: Schedule, totalWeeks: Int = from.totalWeeks) = from.copy(
        id = "new", totalWeeks = totalWeeks, adjustments = emptyList(), holidays = emptyList(),
        makeups = emptyList(), courseColors = emptyMap()
    )

    @Test
    fun roundTripKeepsEverything() {
        val backup = ScheduleBackup.decode(encode())!!
        assertEquals(2, backup.version)
        assertEquals("测试课表", backup.name)
        assertEquals("2026-09-07", backup.semesterStart)
        assertEquals(16, backup.totalWeeks)
        assertEquals(full.courses.map { it.id }, backup.courses.map { it.id })
        assertEquals(1, backup.adjustments.size)
        assertEquals(1, backup.holidays.size)
        assertEquals(1, backup.makeups.size)
        assertEquals(mapOf("高等数学" to "#AA3355"), backup.courseColors)
        assertEquals("物理实验", backup.extras.single().course.name)
        assertEquals("#123456", backup.extras.single().color)
        assertEquals("19:00", backup.events.single().startTime)
        // 只带与学期有交集的请假
        assertEquals(listOf("leave-in"), backup.leaves.map { it.id })
        assertTrue(backup.hasExtraData)
    }

    @Test
    fun togglesDecideWhatIsWritten() {
        val backup = ScheduleBackup.decode(
            encode(ExportOptions(includeExtras = false, includeEvents = false, markLeaves = false))
        )!!
        assertTrue(backup.extras.isEmpty())
        assertTrue(backup.events.isEmpty())
        assertTrue(backup.leaves.isEmpty())
        assertEquals(1, backup.adjustments.size)
    }

    @Test
    fun olderParserStillReadsCoursesFromV2() {
        val parsed = EamsParser.parseImportContent(encode())
        assertEquals(3, parsed.courses.size)
        assertEquals("2026-09-07", parsed.semesterStart)
    }

    @Test
    fun nonBackupContentIsLeftToTheOldParser() {
        assertNull(ScheduleBackup.decode("<html></html>"))
        assertNull(ScheduleBackup.decode("""{"format":"zhusijiao-schedule","version":1,"courses":[]}"""))
        assertNull(ScheduleBackup.decode("""{"courses":[]}"""))
        assertNull(ScheduleBackup.decode("{broken"))
    }

    @Test
    fun brokenV2IsRejectedWithReadableMessage() {
        try {
            ScheduleBackup.decode("""{"format":"zhusijiao-schedule","version":2,"courses":[]}""")
            fail("应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("备份里没有有效课程", e.message)
        }
    }

    @Test
    fun applyArrangementsRestoresAll() {
        val backup = ScheduleBackup.decode(encode())!!
        val restored = ScheduleBackup.applyArrangements(base(full), backup)
        assertEquals(0, restored.skipped)
        assertEquals(full.adjustments, restored.items.adjustments)
        assertEquals(full.holidays, restored.items.holidays)
        assertEquals(full.makeups, restored.items.makeups)
        assertEquals(mapOf("高等数学" to "#AA3355"), restored.items.courseColors)
    }

    @Test
    fun applyArrangementsSkipsWhatNoLongerFits() {
        val conflicting = full.copy(
            adjustments = listOf(adjustMath(3), adjustMath(3).copy(id = "dup"), adjustMath(10)),
            // 第 5 周周六既是补课日又被设成停课：后来者（停课先校验，补课后校验）跳过
            holidays = listOf(holiday(4, 3), holiday(5, 6), holiday(12, 1))
        )
        val json = ScheduleBackup.encode(conflicting, emptyList(), emptyList(), emptyList(), ExportOptions(), "", "")
        val backup = ScheduleBackup.decode(json.toString())!!
        // 用户在导入页把总周数改成了 8
        val restored = ScheduleBackup.applyArrangements(base(conflicting, totalWeeks = 8), backup)
        assertEquals(listOf("adj-3"), restored.items.adjustments.map { it.id })
        assertEquals(listOf(4 to 3, 5 to 6), restored.items.holidays.map { it.week to it.day })
        assertTrue(restored.items.makeups.isEmpty())
        // 重复的调课、第 10 周的调课、第 12 周的停课、落在停课日上的补课
        assertEquals(4, restored.skipped)
    }

    @Test
    fun restoreExtrasGetsFreshIdsAndTrimsWeeks() {
        val backup = ScheduleBackup.decode(encode())!!
        var n = 0
        val restored = ScheduleBackup.restoreExtras(backup, totalWeeks = 8) { "extra-new-${n++}" }
        val course = restored.items.single().course
        assertEquals("extra-new-0", course.id)
        assertEquals((1..8).toList(), course.weeks)
        assertEquals("#123456", restored.items.single().color)
        assertEquals(0, restored.skipped)
    }

    @Test
    fun restoreEventsDropsOverlapsAndInvalid() {
        val many = listOf(
            event("社团", day = 2, start = 9, end = 10),
            event("社团副本", day = 2, start = 10, end = 11, id = "e2"),
            event("考研班", day = 3, start = 9, end = 10, weeks = listOf(20), id = "e3")
        )
        val json = ScheduleBackup.encode(full, emptyList(), many, emptyList(), ExportOptions(), "", "")
        val backup = ScheduleBackup.decode(json.toString())!!
        val restored = ScheduleBackup.restoreEvents(
            backup, totalWeeks = 16, slots = EamsParser.DEFAULT_TIME_SLOTS, maxCount = 100
        )
        assertEquals(listOf("社团"), restored.items.map { it.title })
        assertNotEquals("event-社团", restored.items.single().id)
        assertEquals(2, restored.skipped)
    }

    @Test
    fun restoreLeavesMergesById() {
        val backup = ScheduleBackup.decode(encode())!!
        val existing = listOf(leave("2026-09-07 08:00", "2026-09-07 12:00", id = "leave-in"))
        assertTrue(ScheduleBackup.restoreLeaves(backup, existing, maxCount = 200).items.isEmpty())
        val fresh = ScheduleBackup.restoreLeaves(backup, emptyList(), maxCount = 200)
        assertEquals(listOf("leave-in"), fresh.items.map { it.id })
        val full = ScheduleBackup.restoreLeaves(backup, emptyList(), maxCount = 0)
        assertTrue(full.items.isEmpty())
        assertEquals(1, full.skipped)
    }

    @Test
    fun summaryListsOnlyPresentParts() {
        val backup = ScheduleBackup.decode(
            encode(ExportOptions(includeExtras = false, includeEvents = false, markLeaves = false))
        )!!
        assertEquals(listOf("调课" to 1, "停课" to 1, "补课" to 1), ScheduleBackup.summaryParts(backup))
        assertNotNull(backup.toParsedSchedule().courses.find { it.id == math.id })
        assertFalse(backup.toParsedSchedule().courses.isEmpty())
    }
}
