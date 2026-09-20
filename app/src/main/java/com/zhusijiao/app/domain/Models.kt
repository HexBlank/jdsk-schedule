package com.zhusijiao.app.domain

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** 一节的时间（number 从 1 开始）。 */
data class TimeSlot(val number: Int, val startTime: String, val endTime: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("number", number)
        .put("startTime", startTime)
        .put("endTime", endTime)

    companion object {
        fun fromJson(o: JSONObject) = TimeSlot(
            number = o.optInt("number"),
            startTime = o.optString("startTime"),
            endTime = o.optString("endTime")
        )
    }
}

/** 一个课程时段：星期 day(1-7)、节次 startSection-endSection、周次 weeks。 */
data class Course(
    val name: String,
    val teacher: String,
    val position: String,
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int>,
    val id: String = stableId(name, teacher, position, day, startSection, endSection)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("teacher", teacher)
        .put("position", position)
        .put("day", day)
        .put("startSection", startSection)
        .put("endSection", endSection)
        .put("weeks", JSONArray(weeks))

    companion object {
        fun fromJson(o: JSONObject): Course {
            val name = o.optString("name")
            val teacher = o.optString("teacher")
            val position = o.optString("position").ifEmpty { o.optString("room") }
            val day = o.optInt("day")
            val startSection = o.optInt("startSection")
            val endSection = o.optInt("endSection", startSection)
            return Course(
                id = o.optString("id").takeIf { it.isNotBlank() }
                    ?: stableId(name, teacher, position, day, startSection, endSection),
                name = name,
                teacher = teacher,
                position = position,
                day = day,
                startSection = startSection,
                endSection = endSection,
                weeks = o.optJSONArray("weeks").toIntList()
            )
        }

        /** 旧课表没有 id 时生成跨进程、跨端稳定的课程标识；周次变化不会改变关联。 */
        fun stableId(
            name: String,
            teacher: String,
            position: String,
            day: Int,
            startSection: Int,
            endSection: Int
        ): String {
            val canonical = listOf(name, teacher, position)
                .map { it.trim().lowercase(Locale.ROOT) }
                .plus(listOf(day.toString(), startSection.toString(), endSection.toString()))
                .joinToString("\u001f")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return "course-" + digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/** 某一次课程的例外移动。基础课程不变；整表教务覆盖时所有调整会被清空。 */
data class CourseAdjustment(
    val id: String,
    val courseId: String,
    val sourceWeek: Int,
    val sourceDay: Int,
    val sourceStartSection: Int,
    val sourceEndSection: Int,
    val targetWeek: Int,
    val targetDay: Int,
    val targetStartSection: Int,
    val targetEndSection: Int,
    val targetPosition: String?,
    val courseSnapshot: Course,
    val createdAt: String,
    val updatedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("courseId", courseId)
        .put("sourceWeek", sourceWeek)
        .put("sourceDay", sourceDay)
        .put("sourceStartSection", sourceStartSection)
        .put("sourceEndSection", sourceEndSection)
        .put("targetWeek", targetWeek)
        .put("targetDay", targetDay)
        .put("targetStartSection", targetStartSection)
        .put("targetEndSection", targetEndSection)
        .put("targetPosition", targetPosition)
        .put("courseSnapshot", courseSnapshot.toJson())
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    fun targetCourse(base: Course? = null): Course = (base ?: courseSnapshot).copy(
        day = targetDay,
        startSection = targetStartSection,
        endSection = targetEndSection,
        weeks = listOf(targetWeek),
        position = targetPosition?.takeIf { it.isNotBlank() } ?: (base ?: courseSnapshot).position
    )

    companion object {
        fun fromJson(o: JSONObject): CourseAdjustment {
            val snapshot = Course.fromJson(o.optJSONObject("courseSnapshot") ?: JSONObject())
            return CourseAdjustment(
                id = o.optString("id"),
                courseId = o.optString("courseId").ifBlank { snapshot.id },
                sourceWeek = o.optInt("sourceWeek"),
                sourceDay = o.optInt("sourceDay", snapshot.day),
                sourceStartSection = o.optInt("sourceStartSection", snapshot.startSection),
                sourceEndSection = o.optInt("sourceEndSection", snapshot.endSection),
                targetWeek = o.optInt("targetWeek"),
                targetDay = o.optInt("targetDay"),
                targetStartSection = o.optInt("targetStartSection"),
                targetEndSection = o.optInt("targetEndSection"),
                targetPosition = if (o.isNull("targetPosition")) null else o.optString("targetPosition"),
                courseSnapshot = snapshot,
                createdAt = o.optString("createdAt"),
                updatedAt = o.optString("updatedAt")
            )
        }
    }
}

/** 创建或修改调课时提交的可编辑字段。 */
data class CourseAdjustmentDraft(
    val courseId: String,
    val sourceWeek: Int,
    val sourceDay: Int,
    val sourceStartSection: Int,
    val sourceEndSection: Int,
    val targetWeek: Int,
    val targetDay: Int,
    val targetStartSection: Int,
    val targetEndSection: Int,
    val targetPosition: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("courseId", courseId)
        .put("sourceWeek", sourceWeek)
        .put("sourceDay", sourceDay)
        .put("sourceStartSection", sourceStartSection)
        .put("sourceEndSection", sourceEndSection)
        .put("targetWeek", targetWeek)
        .put("targetDay", targetDay)
        .put("targetStartSection", targetStartSection)
        .put("targetEndSection", targetEndSection)
        .put("targetPosition", targetPosition)

    companion object {
        fun from(adjustment: CourseAdjustment) = CourseAdjustmentDraft(
            courseId = adjustment.courseId,
            sourceWeek = adjustment.sourceWeek,
            sourceDay = adjustment.sourceDay,
            sourceStartSection = adjustment.sourceStartSection,
            sourceEndSection = adjustment.sourceEndSection,
            targetWeek = adjustment.targetWeek,
            targetDay = adjustment.targetDay,
            targetStartSection = adjustment.targetStartSection,
            targetEndSection = adjustment.targetEndSection,
            targetPosition = adjustment.targetPosition
        )
    }
}

/**
 * 课表。既承载列表摘要（courseCount / subscriberCount / role），也承载完整详情（timeSlots / courses）。
 * role: "owner"（发布者）/ "subscriber"（订阅者）。shareCode 仅发布者可见。
 */
data class Schedule(
    val id: String,
    val name: String,
    val school: String,
    val semesterStart: String,
    val totalWeeks: Int,
    val shareCode: String?,
    val revision: Int,
    val createdAt: String,
    val updatedAt: String,
    val subscriberCount: Int,
    val role: String,
    val courseCount: Int,
    val timeSlots: List<TimeSlot>,
    val courses: List<Course>,
    val adjustments: List<CourseAdjustment> = emptyList(),
    val holidays: List<DayHoliday> = emptyList(),
    val makeups: List<DayMakeup> = emptyList(),
    /** 手动指定的课程颜色（课程名 → #RRGGBB），优先于自动配色；随课表同步给加入的同学。 */
    val courseColors: Map<String, String> = emptyMap()
) {
    val isOwner: Boolean get() = role == "owner"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("school", school)
        .put("semesterStart", semesterStart)
        .put("totalWeeks", totalWeeks)
        .put("shareCode", shareCode)
        .put("revision", revision)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("subscriberCount", subscriberCount)
        .put("role", role)
        .put("courseCount", courseCount)
        .put("timeSlots", JSONArray(timeSlots.map { it.toJson() }))
        .put("courses", JSONArray(courses.map { it.toJson() }))
        .put("adjustments", JSONArray(adjustments.map { it.toJson() }))
        .put("holidays", JSONArray(holidays.map { it.toJson() }))
        .put("makeups", JSONArray(makeups.map { it.toJson() }))
        .put("courseColors", JSONObject(courseColors))

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val timeSlots = o.optJSONArray("timeSlots").toTimeSlotList()
            val courses = o.optJSONArray("courses").toCourseList()
            val adjustments = o.optJSONArray("adjustments").toAdjustmentList()
            return Schedule(
                id = o.optString("id"),
                name = o.optString("name"),
                school = o.optString("school"),
                semesterStart = o.optString("semesterStart"),
                totalWeeks = o.optInt("totalWeeks", 20),
                shareCode = o.optString("shareCode").takeIf { it.isNotEmpty() },
                revision = o.optInt("revision", 1),
                createdAt = o.optString("createdAt"),
                updatedAt = o.optString("updatedAt"),
                subscriberCount = o.optInt("subscriberCount", 0),
                role = o.optString("role", "subscriber"),
                courseCount = o.optInt("courseCount", courses.size),
                timeSlots = timeSlots,
                courses = courses,
                adjustments = adjustments,
                holidays = o.optJSONArray("holidays").toDayHolidayList(),
                makeups = o.optJSONArray("makeups").toDayMakeupList(),
                courseColors = o.optJSONObject("courseColors").toStringMap()
            )
        }
    }
}

/** 停课日：该周该天放假停课，当天课程以半透明「停课」样式显示。 */
data class DayHoliday(
    val id: String,
    val week: Int,
    val day: Int,
    val createdAt: String,
    val updatedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("week", week)
        .put("day", day)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = DayHoliday(
            id = o.optString("id"),
            week = o.optInt("week"),
            day = o.optInt("day"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt")
        )
    }
}

/** 创建停课日时提交的字段。 */
data class DayHolidayDraft(val week: Int, val day: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("week", week)
        .put("day", day)
}

/** 补课日：在 targetWeek 周 targetDay 补上 sourceWeek 周 sourceDay 的课。 */
data class DayMakeup(
    val id: String,
    val sourceWeek: Int,
    val sourceDay: Int,
    val targetWeek: Int,
    val targetDay: Int,
    val createdAt: String,
    val updatedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sourceWeek", sourceWeek)
        .put("sourceDay", sourceDay)
        .put("targetWeek", targetWeek)
        .put("targetDay", targetDay)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = DayMakeup(
            id = o.optString("id"),
            sourceWeek = o.optInt("sourceWeek"),
            sourceDay = o.optInt("sourceDay"),
            targetWeek = o.optInt("targetWeek"),
            targetDay = o.optInt("targetDay"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt")
        )
    }
}

/** 创建补课日时提交的字段。 */
data class DayMakeupDraft(
    val sourceWeek: Int,
    val sourceDay: Int,
    val targetWeek: Int,
    val targetDay: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sourceWeek", sourceWeek)
        .put("sourceDay", sourceDay)
        .put("targetWeek", targetWeek)
        .put("targetDay", targetDay)
}

/** 加入前摘要（分享码预览）。 */
data class SharePreview(
    val id: String,
    val name: String,
    val school: String,
    val semesterStart: String,
    val totalWeeks: Int,
    val courseCount: Int,
    val revision: Int,
    val updatedAt: String,
    val subscriberCount: Int,
    val joined: Boolean,
    val owned: Boolean
) {
    companion object {
        fun fromJson(o: JSONObject) = SharePreview(
            id = o.optString("id"),
            name = o.optString("name"),
            school = o.optString("school"),
            semesterStart = o.optString("semesterStart"),
            totalWeeks = o.optInt("totalWeeks"),
            courseCount = o.optInt("courseCount"),
            revision = o.optInt("revision"),
            updatedAt = o.optString("updatedAt"),
            subscriberCount = o.optInt("subscriberCount"),
            joined = o.optBoolean("joined"),
            owned = o.optBoolean("owned")
        )
    }
}

/** 解析器输出 / 写入 API 的规范化课表包。 */
data class ParsedSchedule(
    val name: String,
    val semesterStart: String,
    val totalWeeks: Int,
    val timeSlots: List<TimeSlot>,
    val courses: List<Course>,
    val format: String = "zhusijiao-schedule",
    val version: Int = 1,
    val school: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", format)
        .put("version", version)
        .put("school", school)
        .put("name", name)
        .put("semesterStart", semesterStart)
        .put("totalWeeks", totalWeeks)
        .put("timeSlots", JSONArray(timeSlots.map { it.toJson() }))
        .put("courses", JSONArray(courses.map { it.toJson() }))
}

// ===== org.json 便捷扩展 =====

fun JSONArray?.toIntList(): List<Int> {
    val a = this ?: return emptyList()
    return (0 until a.length()).map { a.optInt(it) }
}

fun JSONArray?.toCourseList(): List<Course> {
    val a = this ?: return emptyList()
    return (0 until a.length()).map { Course.fromJson(a.optJSONObject(it) ?: JSONObject()) }
}

fun JSONArray?.toTimeSlotList(): List<TimeSlot> {
    val a = this ?: return emptyList()
    return (0 until a.length()).map { TimeSlot.fromJson(a.optJSONObject(it) ?: JSONObject()) }
}

fun JSONArray?.toAdjustmentList(): List<CourseAdjustment> {
    val a = this ?: return emptyList()
    return (0 until a.length()).mapNotNull { index ->
        a.optJSONObject(index)?.let(CourseAdjustment::fromJson)
    }.filter { it.id.isNotBlank() && it.courseId.isNotBlank() }
}

fun JSONArray?.toDayHolidayList(): List<DayHoliday> {
    val a = this ?: return emptyList()
    return (0 until a.length()).mapNotNull { index ->
        a.optJSONObject(index)?.let(DayHoliday::fromJson)
    }.filter { it.id.isNotBlank() && it.week > 0 && it.day in 1..7 }
}

fun JSONArray?.toDayMakeupList(): List<DayMakeup> {
    val a = this ?: return emptyList()
    return (0 until a.length()).mapNotNull { index ->
        a.optJSONObject(index)?.let(DayMakeup::fromJson)
    }.filter {
        it.id.isNotBlank() && it.sourceWeek > 0 && it.sourceDay in 1..7 &&
            it.targetWeek > 0 && it.targetDay in 1..7
    }
}

fun JSONObject?.toStringMap(): Map<String, String> {
    val obj = this ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    for (key in obj.keys()) {
        val value = obj.optString(key)
        if (key.isNotBlank() && value.isNotBlank()) result[key] = value
    }
    return result
}
