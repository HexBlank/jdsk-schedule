package com.zhusijiao.app.domain

import java.util.Calendar

/**
 * 上课提醒偏好：纯本机设置，不随课表同步（同一份课表各人各设）。
 * 只提醒「当前课表」，订阅了同学课表的人不会被别人的课打扰。
 */
data class ClassReminderSettings(
    val enabled: Boolean = false,
    /** 提前多少分钟提醒，取值见 [LEAD_OPTIONS]。 */
    val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
    /** 自己添加的日程开始前是否也提醒。 */
    val includeEvents: Boolean = false
) {
    companion object {
        val LEAD_OPTIONS = listOf(5, 10, 15, 20, 30)
        const val DEFAULT_LEAD_MINUTES = 10

        fun normalizeLead(value: Int): Int = if (value in LEAD_OPTIONS) value else DEFAULT_LEAD_MINUTES
    }
}

/** 一次要提醒的课或日程。课程已按「同一门课、同一教室、连着上」合并成一段。 */
data class ReminderOccurrence(
    val isEvent: Boolean,
    val title: String,
    val position: String,
    val teacher: String,
    val dateIso: String,
    val startTime: String,
    val endTime: String,
    val startAtMillis: Long,
    val endAtMillis: Long
) {
    /** 同一节课每次计算都得到同一个 key，用作通知标识：重复发送会覆盖而不是叠加。 */
    val key: String
        get() = listOf(if (isEvent) "event" else "course", dateIso, startTime, title, position).joinToString("|")
}

/**
 * 上课提醒的排期计算。纯 Kotlin、不依赖 android.*，JVM 单元测试可直接覆盖。
 *
 * 口径与课表显示完全一致：某天上哪些课一律走 [ScheduleOccurrences.coursesOn]，
 * 调课、停课、补课都已算进去；上下课时间走 [ScheduleTime.slotsOf]。
 *
 * 调用方每次只排「下一次」提醒：提醒发出后、课表或设置变化后都重新调用 [plan]，
 * 所以这里不需要记住整个学期的提醒，也不会留下作废的旧提醒。
 */
object ClassReminderPlanner {

    /**
     * 同一门课、同一教室，前一段下课到后一段上课不超过这么多分钟才算「连着上」、只提醒第一段。
     * 课间一般 10–20 分钟；午休、晚饭隔开的两段（90 分钟以上）中间人会离开，各提醒一次。
     */
    const val MERGE_GAP_MINUTES = 30

    /** 已提醒记录比「现在 + 1 小时」还晚，只可能是系统时间被往回调过，此时记录作废。 */
    private const val NOTIFIED_SANITY_MILLIS = 60 * 60_000L

    enum class Blocker {
        /** 课表没有开学日期，推算不出每周是哪几天。 */
        NO_SEMESTER_START,
        /** 学期已经结束。 */
        SEMESTER_OVER,
        /** 本学期接下来没有要提醒的课。 */
        NOTHING_UPCOMING
    }

    data class Plan(
        /** 现在就该发出的提醒：提醒时刻已到、还没开始、之前没发过。 */
        val due: List<ReminderOccurrence>,
        /** 下一次提醒的目标时刻（开始时间 − 提前量）；null 表示接下来没有要提醒的。 */
        val nextTriggerAtMillis: Long?,
        /** 下一次提醒时要发的内容（同一时刻开始的可能不止一项）。 */
        val next: List<ReminderOccurrence>,
        val blocker: Blocker?
    )

    /**
     * 计算现在该发哪些提醒、下一次提醒在什么时候。
     *
     * @param notifiedUpToMillis 已经发过提醒的最晚开始时刻，开始时间不晚于它的不再提醒，
     *   防止重复计算（打开 App、改设置、重启手机）把同一节课提醒两遍。
     * @param earlyToleranceMillis 允许提前多久就算「到点」。精确定时传 0；
     *   没有精确定时权限时系统只保证在一个时间窗内送达，窗口开在目标时刻之前（宁早勿晚），
     *   这里要传同样的窗口长度，否则提前送达时会被判为「还没到」，接着又排一次、反复唤醒。
     */
    fun plan(
        schedule: Schedule,
        events: List<PersonalEvent>,
        settings: ClassReminderSettings,
        nowMillis: Long,
        notifiedUpToMillis: Long,
        earlyToleranceMillis: Long = 0L
    ): Plan {
        if (!DateUtils.hasSemesterStart(schedule.semesterStart)) {
            return Plan(emptyList(), null, emptyList(), Blocker.NO_SEMESTER_START)
        }
        val totalWeeks = if (schedule.totalWeeks > 0) schedule.totalWeeks else 20
        val startWeek = maxOf(1, DateUtils.teachingWeekAt(schedule.semesterStart, nowMillis))
        if (startWeek > totalWeeks) return Plan(emptyList(), null, emptyList(), Blocker.SEMESTER_OVER)

        val leadMillis = ClassReminderSettings.normalizeLead(settings.leadMinutes) * 60_000L
        val notified = if (notifiedUpToMillis > nowMillis + NOTIFIED_SANITY_MILLIS) Long.MIN_VALUE else notifiedUpToMillis
        val floor = maxOf(nowMillis, notified)

        val due = mutableListOf<ReminderOccurrence>()
        val next = mutableListOf<ReminderOccurrence>()
        var nextStart: Long? = null
        for (week in startWeek..totalWeeks) {
            val dates = DateUtils.datesForWeek(schedule.semesterStart, week)
            for (day in 1..7) {
                val items = occurrencesOn(schedule, events, settings.includeEvents, week, day, dates[day - 1].iso)
                for (item in items) {
                    if (item.startAtMillis <= floor) continue
                    val trigger = item.startAtMillis - leadMillis
                    when {
                        nextStart == null && trigger - earlyToleranceMillis <= nowMillis -> due += item
                        nextStart == null -> {
                            nextStart = item.startAtMillis
                            next += item
                        }
                        item.startAtMillis == nextStart -> next += item
                        // 已按时间顺序遍历，再往后都比下一次提醒晚，不用再找
                        else -> return Plan(due, nextStart - leadMillis, next, null)
                    }
                }
            }
        }
        val blocker = if (due.isEmpty() && nextStart == null) Blocker.NOTHING_UPCOMING else null
        return Plan(due, nextStart?.let { it - leadMillis }, next, blocker)
    }

    /**
     * 第 [week] 周周 [day]（日期 [dateIso]）要提醒的课和日程，按开始时间排序。
     * 课程先合并「同一门课、同一教室、连着上」的相邻段；日程不合并，各自提醒。
     */
    fun occurrencesOn(
        schedule: Schedule,
        events: List<PersonalEvent>,
        includeEvents: Boolean,
        week: Int,
        day: Int,
        dateIso: String
    ): List<ReminderOccurrence> {
        val slots = ScheduleTime.slotsOf(schedule.timeSlots)
        val courses = ScheduleOccurrences.coursesOn(schedule, week, day).mapNotNull { course ->
            occurrence(
                isEvent = false,
                title = course.name,
                position = course.position,
                teacher = course.teacher,
                dateIso = dateIso,
                startTime = slots.find { it.number == course.startSection }?.startTime,
                endTime = slots.find { it.number == course.endSection }?.endTime
            )
        }.sortedWith(compareBy({ it.startAtMillis }, { it.endAtMillis }))

        val eventItems = if (!includeEvents) emptyList() else events
            .filter { it.day == day && it.occursIn(week) }
            .mapNotNull { event ->
                occurrence(
                    isEvent = true,
                    title = event.title,
                    position = event.position,
                    teacher = "",
                    dateIso = dateIso,
                    startTime = event.startTime ?: slots.find { it.number == event.startSection }?.startTime,
                    endTime = event.endTime ?: slots.find { it.number == event.endSection }?.endTime
                )
            }

        return (mergeConsecutive(courses) + eventItems)
            .sortedWith(compareBy({ it.startAtMillis }, { it.isEvent }, { it.title }))
    }

    /** 同一门课、同一教室、间隔不超过 [MERGE_GAP_MINUTES] 的相邻段合成一段（完全重复的也会并掉）。 */
    private fun mergeConsecutive(sorted: List<ReminderOccurrence>): List<ReminderOccurrence> {
        val result = mutableListOf<ReminderOccurrence>()
        sorted.forEach { item ->
            val index = result.indexOfLast { previous ->
                previous.title.trim() == item.title.trim() &&
                    previous.position.trim() == item.position.trim() &&
                    item.startAtMillis - previous.endAtMillis <= MERGE_GAP_MINUTES * 60_000L
            }
            if (index < 0) {
                result += item
                return@forEach
            }
            val previous = result[index]
            if (item.endAtMillis > previous.endAtMillis) {
                result[index] = previous.copy(endTime = item.endTime, endAtMillis = item.endAtMillis)
            }
        }
        return result
    }

    private fun occurrence(
        isEvent: Boolean,
        title: String,
        position: String,
        teacher: String,
        dateIso: String,
        startTime: String?,
        endTime: String?
    ): ReminderOccurrence? {
        val start = ScheduleTime.atMillis(dateIso, startTime) ?: return null
        val end = ScheduleTime.atMillis(dateIso, endTime) ?: return null
        if (title.isBlank()) return null
        return ReminderOccurrence(
            isEvent = isEvent,
            title = title.trim(),
            position = position.trim(),
            teacher = teacher.trim(),
            dateIso = dateIso,
            startTime = startTime!!,
            endTime = endTime!!,
            startAtMillis = start,
            endAtMillis = maxOf(end, start)
        )
    }

    /** 距离开始还有几分钟（向上取整，不足 1 分钟按 0 算），通知标题里的「N 分钟后上课」用它。 */
    fun minutesUntil(startAtMillis: Long, nowMillis: Long): Int {
        val diff = startAtMillis - nowMillis
        if (diff <= 0) return 0
        return ((diff + 59_999L) / 60_000L).toInt()
    }

    private val dayNames = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 设置页「下一次提醒」的时刻文案：今天 07:40 / 明天 07:40 / 9月28日 周一 07:40。 */
    fun formatMoment(millis: Long, nowMillis: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val time = "%02d:%02d".format(target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val prefix = when {
            sameDay(target, today) -> "今天"
            sameDay(target, tomorrow) -> "明天"
            else -> "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日 " +
                dayNames[target.get(Calendar.DAY_OF_WEEK) - 1]
        }
        return "$prefix $time"
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
