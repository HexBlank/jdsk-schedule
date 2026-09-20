package com.zhusijiao.app.domain

import java.text.SimpleDateFormat
import java.util.Locale

/** 客户端与服务端保持一致的课表约束；本机模式也不得保存服务端会拒绝的数据。 */
object ScheduleValidator {

    fun normalize(input: ParsedSchedule): ParsedSchedule {
        require(input.name.trim().isNotEmpty()) { "课表名称不能为空" }
        require(input.name.trim().length <= 40) { "课表名称不能超过 40 个字符" }
        require(validDate(input.semesterStart)) { "开学日期格式必须为有效的 YYYY-MM-DD" }
        require(input.totalWeeks in 1..30) { "总周数必须在 1 到 30 之间" }
        require(input.courses.size in 1..300) { "课程数量必须在 1 到 300 条之间" }

        val courses = input.courses.mapIndexed { index, course ->
            val prefix = "第 ${index + 1} 条课程"
            require(course.name.trim().isNotEmpty()) { "${prefix}名称不能为空" }
            require(course.name.trim().length <= 80) { "${prefix}名称过长" }
            require(course.teacher.trim().length <= 60) { "${prefix}教师名称过长" }
            require(course.position.trim().length <= 80) { "${prefix}地点过长" }
            require(course.day in 1..7) { "${prefix}星期值错误" }
            require(course.startSection in 1..12 && course.endSection in course.startSection..12) {
                "${prefix}节次错误"
            }
            val weeks = course.weeks.distinct().sorted()
            require(weeks.isNotEmpty() && weeks.all { it in 1..input.totalWeeks }) {
                "${prefix}周次超出学期范围"
            }
            course.copy(
                name = course.name.trim(),
                teacher = course.teacher.trim(),
                position = course.position.trim(),
                weeks = weeks,
                id = course.id.ifBlank {
                    Course.stableId(
                        course.name,
                        course.teacher,
                        course.position,
                        course.day,
                        course.startSection,
                        course.endSection
                    )
                }
            )
        }
        return input.copy(name = input.name.trim(), courses = courses)
    }

    fun validateAdjustment(
        schedule: Schedule,
        draft: CourseAdjustmentDraft,
        replacingId: String? = null
    ): Course {
        val course = schedule.courses.find { it.id == draft.courseId }
            ?: throw IllegalArgumentException("原课程已不存在，请先更新课表后重试")
        require(draft.sourceWeek in 1..schedule.totalWeeks && draft.sourceWeek in course.weeks) {
            "原课程在所选周次没有安排"
        }
        require(draft.sourceDay == course.day &&
            draft.sourceStartSection == course.startSection &&
            draft.sourceEndSection == course.endSection) { "原课程位置已经变化，请重新选择" }
        require(draft.targetWeek in 1..schedule.totalWeeks) { "目标周次超出学期范围" }
        require(draft.targetDay in 1..7) { "目标星期无效" }
        require(draft.targetStartSection in 1..12 && draft.targetEndSection in draft.targetStartSection..12) {
            "目标节次无效"
        }
        require(draft.targetPosition.orEmpty().trim().length <= 80) { "目标教室不能超过 80 个字符" }
        require(!(draft.sourceWeek == draft.targetWeek && draft.sourceDay == draft.targetDay &&
            draft.sourceStartSection == draft.targetStartSection &&
            draft.sourceEndSection == draft.targetEndSection && draft.targetPosition.isNullOrBlank())) {
            "目标时间与原课程相同"
        }
        require(schedule.adjustments.none {
            it.id != replacingId && it.courseId == draft.courseId && it.sourceWeek == draft.sourceWeek
        }) { "这次课程已经调过课，可在详情中修改原调课" }
        return course
    }

    /** 停课日约束：与客户端一致的周次/星期范围，避免重复，且不能与补课日重叠。 */
    fun validateHoliday(
        schedule: Schedule,
        draft: DayHolidayDraft,
        replacingId: String? = null
    ) {
        require(draft.week in 1..schedule.totalWeeks) { "停课周次超出学期范围" }
        require(draft.day in 1..7) { "停课星期无效" }
        require(schedule.holidays.none {
            it.id != replacingId && it.week == draft.week && it.day == draft.day
        }) { "这一天已经设置为停课" }
        require(schedule.makeups.none { it.targetWeek == draft.week && it.targetDay == draft.day }) {
            "这一天已安排补课，请先撤销补课"
        }
    }

    /** 补课日约束：目标日唯一、不能与来源同一天、不能落在停课日上。 */
    fun validateMakeup(
        schedule: Schedule,
        draft: DayMakeupDraft,
        replacingId: String? = null
    ) {
        require(draft.sourceWeek in 1..schedule.totalWeeks && draft.targetWeek in 1..schedule.totalWeeks) {
            "补课周次超出学期范围"
        }
        require(draft.sourceDay in 1..7 && draft.targetDay in 1..7) { "补课星期无效" }
        require(!(draft.sourceWeek == draft.targetWeek && draft.sourceDay == draft.targetDay)) {
            "补课日不能与来源是同一天"
        }
        require(schedule.makeups.none {
            it.id != replacingId && it.targetWeek == draft.targetWeek && it.targetDay == draft.targetDay
        }) { "这一天已经安排了补课" }
        require(schedule.makeups.none {
            it.id != replacingId && it.sourceWeek == draft.sourceWeek && it.sourceDay == draft.sourceDay
        }) { "来源日已有补课安排" }
        require(schedule.holidays.none { it.week == draft.targetWeek && it.day == draft.targetDay }) {
            "这一天已设为停课，请先撤销停课"
        }
    }

    /** 返回目标时段内会重叠的正常课或调入课，供界面做二次确认。 */
    fun conflicts(
        schedule: Schedule,
        draft: CourseAdjustmentDraft,
        replacingId: String? = null
    ): List<Course> {
        val movedOut = schedule.adjustments
            .filter { it.id != replacingId }
            .map { Triple(it.courseId, it.sourceWeek, it.sourceDay) }
            .toSet()
        val base = schedule.courses.filter { course ->
            draft.targetWeek in course.weeks && course.day == draft.targetDay &&
                !(course.id == draft.courseId && draft.targetWeek == draft.sourceWeek) &&
                Triple(course.id, draft.targetWeek, course.day) !in movedOut &&
                overlaps(course.startSection, course.endSection, draft.targetStartSection, draft.targetEndSection)
        }
        val adjusted = schedule.adjustments.filter { it.id != replacingId && it.targetWeek == draft.targetWeek &&
            it.targetDay == draft.targetDay && overlaps(
                it.targetStartSection,
                it.targetEndSection,
                draft.targetStartSection,
                draft.targetEndSection
            )
        }.map { adjustment ->
            adjustment.targetCourse(schedule.courses.find { it.id == adjustment.courseId })
        }
        return (base + adjusted).distinctBy { "${it.id}:${it.day}:${it.startSection}:${it.endSection}" }
    }

    private fun overlaps(startA: Int, endA: Int, startB: Int, endB: Int): Boolean =
        startA <= endB && startB <= endA

    private fun validDate(value: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)) return false
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)
        }.getOrNull() != null
    }
}
