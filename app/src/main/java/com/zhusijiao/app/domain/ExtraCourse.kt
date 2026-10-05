package com.zhusijiao.app.domain

import java.util.Locale

/** 一门自己加的课和它的手动颜色（#RRGGBB；null 为跟随自动配色）。 */
data class ExtraCourse(val course: Course, val color: String? = null)

/**
 * 自己加的课：教务没导出的选修、重修、实验课，或者订阅了同学的课表、自己还多上的那几门。
 *
 * 只存在本机（[com.zhusijiao.app.data.ExtraCourseStore]），**不进 [Schedule.courses] 的存储、
 * 不随课表同步**——订阅来的课表是只读的远端副本，写进去下次同步就被覆盖；发布者把自己的选修
 * 推给全班也不对。用的时候再用 [merge] 并进课表：并进去之后它就是一门普通的课，
 * 显示、分栏、停课补课、上课提醒、请假、桌面小部件全部照常，不需要各处单独适配。
 * 颜色默认自动配，也可以手动选；手动选的颜色同样只在本机。
 *
 * 与「日程」的区别：日程是课以外的安排（社团、兼职），停课不影响它、提醒要单独开；
 * 自己加的课就是课。见 docs/DECISIONS.md D23。
 */
object ExtraCourses {

    /** 自己加的课的 id 前缀，靠它与课表自带的课区分。 */
    const val ID_PREFIX = "extra-"

    /** 每份课表最多加这么多门，防止本机文件无限增长。 */
    const val MAX_PER_SCHEDULE = 50

    const val MAX_NAME = 40
    const val MAX_TEACHER = 30
    const val MAX_POSITION = 40

    private val HEX_COLOR = Regex("^#[0-9A-F]{6}$")

    fun isExtra(course: Course): Boolean = course.id.startsWith(ID_PREFIX)

    /** 手动颜色规范成大写 #RRGGBB；空白当作「跟随自动配色」返回 null；格式不对抛 [IllegalArgumentException]。 */
    fun normalizeColor(color: String?): String? {
        val value = color?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        require(HEX_COLOR.matches(value)) { "颜色值无效" }
        return value
    }

    /**
     * 校验并规范化；不合法时抛 [IllegalArgumentException]，message 即用户可读文案。
     * 上限都不超过 [ScheduleValidator]（服务端同款）的约束。
     */
    fun normalize(course: Course, totalWeeks: Int): Course {
        val name = course.name.trim()
        require(name.isNotEmpty()) { "课程名称不能为空" }
        require(name.length <= MAX_NAME) { "课程名称不能超过 $MAX_NAME 个字符" }
        val teacher = course.teacher.trim()
        require(teacher.length <= MAX_TEACHER) { "教师不能超过 $MAX_TEACHER 个字符" }
        val position = course.position.trim()
        require(position.length <= MAX_POSITION) { "教室不能超过 $MAX_POSITION 个字符" }
        require(course.day in 1..7) { "星期无效" }
        require(course.startSection in 1..ScheduleTime.MAX_SECTION) { "请先选择节次" }
        require(course.endSection in course.startSection..ScheduleTime.MAX_SECTION) { "节次无效" }
        val weeks = course.weeks.filter { it > 0 }.distinct().sorted()
        require(weeks.isNotEmpty()) { "请至少选择一个周次" }
        require(weeks.all { it in 1..totalWeeks }) { "周次超出学期范围" }
        return course.copy(name = name, teacher = teacher, position = position, weeks = weeks)
    }

    /**
     * 把自己加的课并进课表。课表重新导入后总周数可能变少：超出学期的周次裁掉，一周都不剩的不并。
     * 不改 courseCount 等摘要字段——那些说的是课表本身。
     *
     * 手动颜色一并并进 [Schedule.courseColors]（全 App 的配色都按课程名查这张表，并进去各处自动生效）。
     * 这张表按课程名索引，所以自己加的课和课表里的课重名时（重修课）两者同色，以自己选的为准——
     * 自动配色本来也是同名同色。并进去的颜色只用于显示，保存课表配色时要用课表原本的那份，
     * 不能把私人的颜色写回去同步给全班。
     */
    fun merge(schedule: Schedule, extras: List<ExtraCourse>): Schedule {
        if (extras.isEmpty()) return schedule
        val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        val usable = extras.mapNotNull { extra ->
            val weeks = extra.course.weeks.filter { it in 1..totalWeeks }
            if (weeks.isEmpty() || !isExtra(extra.course)) null else extra.copy(course = extra.course.copy(weeks = weeks))
        }
        if (usable.isEmpty()) return schedule
        val colors = usable.mapNotNull { extra -> extra.color?.let { extra.course.name to it } }
        return schedule.copy(
            courses = schedule.courses + usable.map { it.course },
            courseColors = if (colors.isEmpty()) schedule.courseColors else schedule.courseColors + colors
        )
    }

    /**
     * 这门课和课表里哪些课时间重叠（按课程名聚合周次），保存前提示用。
     * [schedule] 应当已经并入其他自己加的课、并且不含正在编辑的这一门。
     */
    fun conflicts(schedule: Schedule, draft: Course): List<ScheduleOccurrences.EventConflict> {
        val byName = linkedMapOf<String, MutableList<Int>>()
        draft.weeks.sorted().forEach { week ->
            ScheduleOccurrences.coursesOverlapping(schedule, week, draft.day, draft.startSection, draft.endSection)
                .map { it.name }
                .distinct()
                .forEach { name -> byName.getOrPut(name) { mutableListOf() } += week }
        }
        return byName.map { (name, weeks) -> ScheduleOccurrences.EventConflict(name, weeks.distinct().sorted()) }
    }
}
