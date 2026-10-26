package com.zhusijiao.app.domain.export

import com.zhusijiao.app.domain.Course
import com.zhusijiao.app.domain.CourseAdjustment
import com.zhusijiao.app.domain.DayHoliday
import com.zhusijiao.app.domain.DayMakeup
import com.zhusijiao.app.domain.ExtraCourse
import com.zhusijiao.app.domain.ExtraCourses
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.LeaveType
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule

/**
 * 导出测试共用的课表：开学日 2026-09-07 是周一，16 周，默认作息（1–2 节 07:50–09:30，3–4 节 09:50–11:30）。
 * - 高等数学：周一 1–2 节，1–16 周；
 * - 大学英语：周三 3–4 节，1–16 周；
 * - 体育：周六 1–2 节，只有第 2 周（让第 2 周出现周末列）。
 */
internal object ExportFixtures {

    fun course(name: String, day: Int, start: Int, end: Int, weeks: List<Int> = (1..16).toList()) =
        Course(name = name, teacher = "李老师", position = "A101", day = day, startSection = start, endSection = end, weeks = weeks)

    val math = course("高等数学", day = 1, start = 1, end = 2)
    val english = course("大学英语", day = 3, start = 3, end = 4)
    val sports = course("体育", day = 6, start = 1, end = 2, weeks = listOf(2))

    fun schedule(
        courses: List<Course> = listOf(math, english, sports),
        adjustments: List<CourseAdjustment> = emptyList(),
        holidays: List<DayHoliday> = emptyList(),
        makeups: List<DayMakeup> = emptyList(),
        courseColors: Map<String, String> = emptyMap(),
        semesterStart: String = "2026-09-07",
        totalWeeks: Int = 16
    ) = Schedule(
        id = "s1", name = "测试课表", school = "", semesterStart = semesterStart, totalWeeks = totalWeeks,
        shareCode = null, revision = 3, createdAt = "", updatedAt = "", subscriberCount = 0, role = "owner",
        courseCount = courses.size, timeSlots = emptyList(), courses = courses, adjustments = adjustments,
        holidays = holidays, makeups = makeups, courseColors = courseColors
    )

    /** 把第 [sourceWeek] 周的高等数学调到同周周二 3–4 节。 */
    fun adjustMath(sourceWeek: Int, targetWeek: Int = sourceWeek) = CourseAdjustment(
        id = "adj-$sourceWeek", courseId = math.id,
        sourceWeek = sourceWeek, sourceDay = 1, sourceStartSection = 1, sourceEndSection = 2,
        targetWeek = targetWeek, targetDay = 2, targetStartSection = 3, targetEndSection = 4,
        targetPosition = "B202", courseSnapshot = math, createdAt = "", updatedAt = ""
    )

    fun holiday(week: Int, day: Int) = DayHoliday("holiday-$week-$day", week, day, "", "")

    fun makeup(sourceWeek: Int, sourceDay: Int, targetWeek: Int, targetDay: Int) =
        DayMakeup("makeup-$targetWeek-$targetDay", sourceWeek, sourceDay, targetWeek, targetDay, "", "")

    fun extra(name: String, day: Int, start: Int, end: Int, color: String? = null) =
        ExtraCourse(course(name, day, start, end).copy(id = ExtraCourses.ID_PREFIX + name), color)

    fun event(
        title: String,
        day: Int,
        start: Int,
        end: Int,
        weeks: List<Int> = (1..16).toList(),
        startTime: String? = null,
        endTime: String? = null,
        id: String = "event-$title"
    ) = PersonalEvent(
        id = id, title = title, position = "活动室", note = "", day = day, startSection = start, endSection = end,
        startTime = startTime, endTime = endTime, weeks = weeks, color = null, createdAt = "", updatedAt = ""
    )

    fun leave(start: String, end: String, id: String = "leave-$start", type: LeaveType = LeaveType.PERSONAL) =
        Leave(id, type, start, end, "", "", "")
}
