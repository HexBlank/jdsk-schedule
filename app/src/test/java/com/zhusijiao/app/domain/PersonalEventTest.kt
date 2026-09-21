package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义日程的纯函数回归：时间→占位节次换算、校验、冲突判定。
 * 这几条都不依赖 Android，坏了必须在这里被挡住。
 */
class PersonalEventTest {

    private val slots = ScheduleTime.slotsOf(null)

    private fun course(
        name: String = "高等数学",
        day: Int = 5,
        start: Int = 10,
        end: Int = 11,
        weeks: List<Int> = listOf(1, 2, 3)
    ) = Course(
        name = name,
        teacher = "张老师",
        position = "A302",
        day = day,
        startSection = start,
        endSection = end,
        weeks = weeks
    )

    private fun schedule(
        courses: List<Course> = listOf(course()),
        adjustments: List<CourseAdjustment> = emptyList(),
        holidays: List<DayHoliday> = emptyList(),
        makeups: List<DayMakeup> = emptyList()
    ) = Schedule(
        id = "s1",
        name = "测试课表",
        school = "",
        semesterStart = "2026-09-07",
        totalWeeks = 20,
        shareCode = null,
        revision = 1,
        createdAt = "",
        updatedAt = "",
        subscriberCount = 0,
        role = "owner",
        courseCount = courses.size,
        timeSlots = emptyList(),
        courses = courses,
        adjustments = adjustments,
        holidays = holidays,
        makeups = makeups
    )

    private fun draft(
        start: Int = 10,
        end: Int = 11,
        startTime: String? = null,
        endTime: String? = null,
        weeks: List<Int> = listOf(1)
    ) = PersonalEventDraft(
        title = "街舞社活动",
        day = 5,
        startSection = start,
        endSection = end,
        startTime = startTime,
        endTime = endTime,
        weeks = weeks
    )

    // ===== 时间 → 占位节次 =====

    @Test
    fun `跨晚饭空档的时间占到对应的晚课节次`() {
        // 第 9 节 16:55–17:40，第 10 节 19:15–20:00，第 11 节 20:10–20:55
        assertEquals(10..11, ScheduleTime.sectionSpanFor("18:30", "20:30", slots))
    }

    @Test
    fun `正好对齐节次作息时占位与节次一致`() {
        assertEquals(10..11, ScheduleTime.sectionSpanFor("19:15", "20:55", slots))
    }

    @Test
    fun `结束时间落在空档里只占前一节`() {
        assertEquals(10..10, ScheduleTime.sectionSpanFor("18:00", "20:00", slots))
    }

    @Test
    fun `整段落在午休空档里塌缩到空档前那一节`() {
        // 12:15（第 5 节结束）到 14:00（第 6 节开始）之间没有任何节次
        assertEquals(5..5, ScheduleTime.sectionSpanFor("12:30", "13:30", slots))
    }

    @Test
    fun `早于第一节的时间占第一节`() {
        assertEquals(1..1, ScheduleTime.sectionSpanFor("06:30", "07:20", slots))
    }

    @Test
    fun `晚于最后一节的时间占最后一节`() {
        assertEquals(12..12, ScheduleTime.sectionSpanFor("22:00", "23:00", slots))
    }

    @Test
    fun `时间格式非法时退回第一节`() {
        assertEquals(1..1, ScheduleTime.sectionSpanFor("25:00", "26:00", slots))
        assertEquals(1..1, ScheduleTime.sectionSpanFor(null, null, slots))
    }

    // ===== 校验 =====

    @Test
    fun `填了自定义时间也不改用户选的占位节次`() {
        // 节次是用户在网格上亲手点的，块必须画在他点的那一格；
        // 时间只是显示文案，不能反过来把格子挪走
        val normalized = PersonalEventValidator.normalize(
            draft(start = 10, end = 11, startTime = "18:30", endTime = "20:30"),
            totalWeeks = 20,
            slots = slots
        )
        assertEquals(10, normalized.startSection)
        assertEquals(11, normalized.endSection)
        assertEquals("18:30", normalized.startTime)
    }

    @Test
    fun `时间与所选节次不匹配时保留用户选择由界面去建议`() {
        val normalized = PersonalEventValidator.normalize(
            draft(start = 1, end = 1, startTime = "18:30", endTime = "20:30"),
            totalWeeks = 20,
            slots = slots
        )
        assertEquals(1, normalized.startSection)
        assertEquals(1, normalized.endSection)
        // 编辑器据此给出「这个时间更接近第 10–11 节」的可点建议
        assertEquals(10..11, ScheduleTime.sectionSpanFor("18:30", "20:30", slots))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `结束时间不晚于开始时间被拒绝`() {
        PersonalEventValidator.normalize(
            draft(startTime = "20:30", endTime = "18:30"),
            totalWeeks = 20,
            slots = slots
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `只填开始时间被拒绝`() {
        PersonalEventValidator.normalize(
            draft(startTime = "18:30", endTime = null),
            totalWeeks = 20,
            slots = slots
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `周次超出学期范围被拒绝`() {
        PersonalEventValidator.normalize(draft(weeks = listOf(30)), totalWeeks = 20, slots = slots)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空名称被拒绝`() {
        PersonalEventValidator.normalize(
            draft().copy(title = "   "),
            totalWeeks = 20,
            slots = slots
        )
    }

    // ===== 块上时间行：一致就不显示 =====

    @Test
    fun `自定义时间与节次作息一致时块上不额外显示时间`() {
        val event = event(startSection = 10, endSection = 11, startTime = "19:15", endTime = "20:55")
        assertNull(event.blockTimeText(slots))
    }

    @Test
    fun `自定义时间与节次作息不同才在块上显示`() {
        val event = event(startSection = 10, endSection = 11, startTime = "18:30", endTime = "20:30")
        assertEquals("18:30–20:30", event.blockTimeText(slots))
    }

    @Test
    fun `没填自定义时间时块上不显示时间行`() {
        assertNull(event().blockTimeText(slots))
        assertEquals("19:15–20:55", event().timeText(slots))
    }

    // ===== 冲突判定 =====

    @Test
    fun `与同时段的课判定为冲突并按周次聚合`() {
        val conflicts = ScheduleOccurrences.conflictsForEvent(
            schedule(),
            draft(weeks = listOf(1, 2, 3))
        )
        assertEquals(1, conflicts.size)
        assertEquals("高等数学", conflicts[0].courseName)
        assertEquals(listOf(1, 2, 3), conflicts[0].weeks)
    }

    @Test
    fun `整日停课那一周不算冲突`() {
        val target = schedule(
            holidays = listOf(DayHoliday("h1", week = 2, day = 5, createdAt = "", updatedAt = ""))
        )
        val conflicts = ScheduleOccurrences.conflictsForEvent(target, draft(weeks = listOf(1, 2, 3)))
        assertEquals(listOf(1, 3), conflicts[0].weeks)
    }

    @Test
    fun `补课来源日那一周不算冲突`() {
        val target = schedule(
            makeups = listOf(
                DayMakeup("m1", sourceWeek = 2, sourceDay = 5, targetWeek = 2, targetDay = 6, createdAt = "", updatedAt = "")
            )
        )
        val conflicts = ScheduleOccurrences.conflictsForEvent(target, draft(weeks = listOf(1, 2, 3)))
        assertEquals(listOf(1, 3), conflicts[0].weeks)
    }

    @Test
    fun `补课目标日要算上从来源日搬过来的课`() {
        // 周六本来没课，第 2 周周六补第 2 周周五的课，周六这一格就有课了
        val target = schedule(
            makeups = listOf(
                DayMakeup("m1", sourceWeek = 2, sourceDay = 5, targetWeek = 2, targetDay = 6, createdAt = "", updatedAt = "")
            )
        )
        val saturday = draft(weeks = listOf(2)).copy(day = 6)
        val conflicts = ScheduleOccurrences.conflictsForEvent(target, saturday)
        assertEquals(1, conflicts.size)
        assertEquals("高等数学", conflicts[0].courseName)
    }

    @Test
    fun `错开节次就不算冲突`() {
        val conflicts = ScheduleOccurrences.conflictsForEvent(schedule(), draft(start = 12, end = 12))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `一键避让给出离原位置最近的空节次`() {
        // 课占第 10–11 节，两节长的日程往后挪（11、12 起始）都还会压到课上，
        // 只能往前退到第 8 节（8–9 节）
        assertEquals(8, ScheduleOccurrences.nearestFreeStartSection(schedule(), draft()))
    }

    @Test
    fun `同距离时一键避让优先往后挪`() {
        // 课只占第 10 节，第 9、11 节离得一样近，应该选往后的第 11 节
        val oneSection = schedule(courses = listOf(course(start = 10, end = 10)))
        val single = draft(start = 10, end = 10)
        assertEquals(11, ScheduleOccurrences.nearestFreeStartSection(oneSection, single))
    }

    @Test
    fun `整天排满时没有可避让的节次`() {
        val fullDay = schedule(courses = listOf(course(start = 1, end = 12, weeks = listOf(1))))
        assertNull(ScheduleOccurrences.nearestFreeStartSection(fullDay, draft()))
    }

    @Test
    fun `同时段已有日程时能被查出来`() {
        val existing = event(id = "event-1", startSection = 10, endSection = 11)
        assertNotNull(ScheduleOccurrences.overlappingEvent(listOf(existing), draft()))
        // 编辑自己那条时不该把自己算成重叠
        assertNull(ScheduleOccurrences.overlappingEvent(listOf(existing), draft(), excludeId = "event-1"))
    }

    @Test
    fun `周次不相交的日程不算重叠`() {
        val existing = event(id = "event-1", weeks = listOf(5))
        assertNull(ScheduleOccurrences.overlappingEvent(listOf(existing), draft(weeks = listOf(1))))
    }

    // ===== 调课冲突判定：修掉的两个洞 =====

    @Test
    fun `调到整天停课的日子不再误报冲突`() {
        val target = schedule(
            courses = listOf(course(day = 3, start = 1, end = 2, weeks = listOf(1, 2))),
            holidays = listOf(DayHoliday("h1", week = 2, day = 3, createdAt = "", updatedAt = ""))
        )
        val moved = CourseAdjustmentDraft(
            courseId = target.courses[0].id,
            sourceWeek = 1,
            sourceDay = 3,
            sourceStartSection = 1,
            sourceEndSection = 2,
            targetWeek = 2,
            targetDay = 3,
            targetStartSection = 1,
            targetEndSection = 2
        )
        assertTrue(ScheduleValidator.conflicts(target, moved).isEmpty())
    }

    @Test
    fun `调到补课日会报出从来源日搬来的课`() {
        val math = course(name = "高等数学", day = 1, start = 1, end = 2, weeks = listOf(1, 2))
        val english = course(name = "大学英语", day = 3, start = 1, end = 2, weeks = listOf(1, 2))
        val target = schedule(
            courses = listOf(math, english),
            makeups = listOf(
                DayMakeup("m1", sourceWeek = 2, sourceDay = 1, targetWeek = 2, targetDay = 6, createdAt = "", updatedAt = "")
            )
        )
        val moved = CourseAdjustmentDraft(
            courseId = english.id,
            sourceWeek = 1,
            sourceDay = 3,
            sourceStartSection = 1,
            sourceEndSection = 2,
            targetWeek = 2,
            targetDay = 6,
            targetStartSection = 1,
            targetEndSection = 2
        )
        val conflicts = ScheduleValidator.conflicts(target, moved)
        assertEquals(listOf("高等数学"), conflicts.map { it.name })
    }

    private fun event(
        id: String = "event-x",
        startSection: Int = 10,
        endSection: Int = 11,
        startTime: String? = null,
        endTime: String? = null,
        weeks: List<Int> = listOf(1)
    ) = PersonalEvent(
        id = id,
        title = "街舞社活动",
        position = "活动中心",
        note = "",
        day = 5,
        startSection = startSection,
        endSection = endSection,
        startTime = startTime,
        endTime = endTime,
        weeks = weeks,
        color = null,
        createdAt = "",
        updatedAt = ""
    )
}
