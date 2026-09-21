package com.zhusijiao.app.domain

/**
 * 「第 X 周周 Y 实际会上什么课」的唯一口径，调课与日程的冲突判定共用。
 *
 * 规则与 `TimetableView.buildRender` 的渲染保持一致：
 * - **算**：正常课程（未被调出）、调入块、补课日从来源日搬过来的课；
 * - **不算**：停课日的课、已调出的课、补课来源日的课——这三种当天都不上。
 *
 * 此前 `ScheduleValidator.conflicts` 只看 courses + adjustments，于是调到整天停课的
 * 日子会误报冲突、调到补课日又漏报了搬过来的整天课。见 docs/DECISIONS.md D18。
 */
object ScheduleOccurrences {

    /** 第 [week] 周周 [day] 实际会发生的课。 */
    fun coursesOn(schedule: Schedule, week: Int, day: Int): List<Course> {
        val result = mutableListOf<Course>()
        val movedOut = schedule.adjustments.map { it.courseId to it.sourceWeek }.toSet()
        val suspended = schedule.holidays.any { it.week == week && it.day == day }
        // 补课来源日：当天课表已整体搬到补课日，本日不上课
        val movedAway = schedule.makeups.any { it.sourceWeek == week && it.sourceDay == day }

        if (!suspended && !movedAway) {
            schedule.courses.forEach { course ->
                if (course.day == day && course.weeks.contains(week) && (course.id to week) !in movedOut) {
                    result += course
                }
            }
        }
        schedule.adjustments.filter { it.targetWeek == week && it.targetDay == day }.forEach { adjustment ->
            result += adjustment.targetCourse(schedule.courses.find { it.id == adjustment.courseId })
        }
        schedule.makeups.filter { it.targetWeek == week && it.targetDay == day }.forEach { makeup ->
            schedule.courses.forEach { course ->
                if (course.day == makeup.sourceDay && course.weeks.contains(makeup.sourceWeek) &&
                    (course.id to makeup.sourceWeek) !in movedOut
                ) {
                    result += course
                }
            }
            schedule.adjustments.filter {
                it.targetWeek == makeup.sourceWeek && it.targetDay == makeup.sourceDay
            }.forEach { adjustment ->
                result += adjustment.targetCourse(schedule.courses.find { it.id == adjustment.courseId })
            }
        }
        return result
    }

    /** 第 [week] 周周 [day] 与 [startSection]–[endSection] 有重叠的课。 */
    fun coursesOverlapping(
        schedule: Schedule,
        week: Int,
        day: Int,
        startSection: Int,
        endSection: Int
    ): List<Course> = coursesOn(schedule, week, day).filter {
        overlaps(it.startSection, it.endSection, startSection, endSection)
    }

    /** 某门课与日程重叠的周次，按课程名聚合，供二次确认文案使用。 */
    data class EventConflict(val courseName: String, val weeks: List<Int>)

    /** 日程草稿在它覆盖的每一周里会撞上哪些课。 */
    fun conflictsForEvent(schedule: Schedule, draft: PersonalEventDraft): List<EventConflict> {
        val byName = linkedMapOf<String, MutableList<Int>>()
        draft.weeks.sorted().forEach { week ->
            coursesOverlapping(schedule, week, draft.day, draft.startSection, draft.endSection)
                .map { it.name }
                .distinct()
                .forEach { name -> byName.getOrPut(name) { mutableListOf() } += week }
        }
        return byName.map { (name, weeks) -> EventConflict(name, weeks.distinct().sorted()) }
    }

    /**
     * 与草稿在同一天、同周次、同节次范围重叠的既有日程（[excludeId] 为正在编辑的那条）。
     * 同一时段最多允许一条日程：三块叠在一格里没有可读的画法，而这种情况极罕见。
     */
    fun overlappingEvent(
        events: List<PersonalEvent>,
        draft: PersonalEventDraft,
        excludeId: String? = null
    ): PersonalEvent? = events.firstOrNull { event ->
        event.id != excludeId && event.day == draft.day &&
            event.weeks.any { it in draft.weeks } &&
            overlaps(event.startSection, event.endSection, draft.startSection, draft.endSection)
    }

    /**
     * 离目标节次最近的、与当天课程都不重叠的起始节；找不到返回 null。
     * 供日程编辑器在与课重叠时给出「改到第 N 节」的一键避让。
     *
     * 按与原起始节的距离由近及远搜索，同距离优先往后挪——「往后推一点」比「提前」
     * 更符合直觉；但只往后找会漏掉唯一的空位（课占满到最后一节时后面已经排不下），
     * 所以往前也要找。
     */
    fun nearestFreeStartSection(schedule: Schedule, draft: PersonalEventDraft): Int? {
        val span = draft.endSection - draft.startSection + 1
        val lastStart = ScheduleTime.MAX_SECTION - span + 1
        if (lastStart < 1) return null
        val candidates = (1..lastStart)
            .filter { it != draft.startSection }
            .sortedWith(compareBy({ kotlin.math.abs(it - draft.startSection) }, { -it }))
        return candidates.firstOrNull { start ->
            val end = start + span - 1
            draft.weeks.none { week ->
                coursesOverlapping(schedule, week, draft.day, start, end).isNotEmpty()
            }
        }
    }

    fun overlaps(startA: Int, endA: Int, startB: Int, endB: Int): Boolean =
        startA <= endB && startB <= endA
}
