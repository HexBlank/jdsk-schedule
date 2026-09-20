package com.zhusijiao.app.domain

import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.max
import kotlin.math.min

/**
 * 教务系统（EAMS）课表解析器。
 * 算法思路参考开源项目「拾光课程表」（Apache-2.0），归属与致谢见仓库根目录 README。支持两种输入：
 *  1) 含 `new TaskActivity(...)` 的课表页源码（HTML/JS）；
 *  2) 导出助手生成的 zhusijiao-schedule JSON。
 */
object EamsParser {

    class ParseException(message: String) : Exception(message)

    private data class TaskInfo(val name: String, val teacher: String, val room: String, val validWeeks: String)

    /** 可变工作项，便于两趟合并。 */
    private class Work(
        var name: String, var teacher: String, var position: String,
        var day: Int, var startSection: Int, var endSection: Int, var weeks: MutableList<Int>
    ) {
        fun toCourse() = Course(name, teacher, position, day, startSection, endSection, weeks.toList())
    }

    val DEFAULT_TIME_SLOTS: List<TimeSlot> = listOf(
        TimeSlot(1, "07:50", "08:35"),
        TimeSlot(2, "08:45", "09:30"),
        TimeSlot(3, "09:50", "10:35"),
        TimeSlot(4, "10:45", "11:30"),
        TimeSlot(5, "11:31", "12:15"),
        TimeSlot(6, "14:00", "14:45"),
        TimeSlot(7, "14:55", "15:40"),
        TimeSlot(8, "16:00", "16:45"),
        TimeSlot(9, "16:55", "17:40"),
        TimeSlot(10, "19:15", "20:00"),
        TimeSlot(11, "20:10", "20:55"),
        TimeSlot(12, "21:05", "21:50")
    )

    private val reUnitCount = Regex("var\\s+unitCount\\s*=\\s*(\\d+)")
    private val reMarshal = Regex("marshalTable\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\)")
    private val reIndex = Regex("index\\s*=\\s*(\\d+)\\s*\\*\\s*unitCount\\s*\\+\\s*(\\d+)\\s*;")
    private val reActTeachers = Regex("var\\s+actTeachers\\s*=\\s*\\[([^\\]]*)\\]")
    private val reTeacherName = Regex("name\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
    private val reAssistant = Regex("var\\s+assistantName\\s*=\\s*\"((?:\\\\.|[^\"])*)\"")
    private val reBinary = Regex("^[01]+$")
    private val reCodeSuffix = Regex("\\s*[（(][0-9A-Za-z.]{3,}[）)]\\s*$")

    // ===== 字符串工具 =====

    /** JS String.slice 语义（支持越界与负索引钳制）。 */
    private fun String.jsSlice(start: Int, end: Int = length): String {
        val len = length
        var s = if (start < 0) max(len + start, 0) else min(start, len)
        var e = if (end < 0) max(len + end, 0) else min(end, len)
        if (e < s) e = s
        return substring(s, e)
    }

    private fun decodeEntities(value: String?): String = (value ?: "")
        .replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /** 按顶层逗号切分实参，忽略引号与括号内的逗号。 */
    fun splitArgs(argsString: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (ch in argsString) {
            if (quote != null) {
                current.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == quote -> quote = null
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch
                current.append(ch)
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth += 1; current.append(ch)
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth -= 1; current.append(ch)
            } else if (ch == ',' && depth == 0) {
                args += current.toString().trim(); current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        if (current.toString().trim().isNotEmpty()) args += current.toString().trim()
        return args
    }

    private fun stringLiteral(value: String): String {
        if (value.length < 2) return value
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return try {
                val v = JSONTokener(value).nextValue()
                if (v is String) v else value.substring(1, value.length - 1)
            } catch (_: Exception) {
                value.substring(1, value.length - 1)
            }
        }
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length - 1).replace("\\'", "'").replace("\\\\", "\\")
        }
        return value
    }

    private fun resolveArg(argument: String?, teacherNames: List<String>, assistantName: String): String {
        if (argument == null || argument.equals("null", ignoreCase = true)) return ""
        if (argument.contains("actTeacherName")) return teacherNames.joinToString(",")
        if (argument.contains("actTeacherId")) return ""
        if (argument.contains("assistantName")) return assistantName
        return decodeEntities(stringLiteral(argument))
    }

    private fun resolveTaskActivityArgs(argsString: String, teacherNames: List<String>, assistantName: String): TaskInfo? {
        val args = splitArgs(argsString)
        if (args.size < 7) return null
        val rawName = resolveArg(args[3], teacherNames, assistantName)
        val courseName = rawName.replace(reCodeSuffix, "")
        val teacher = listOf(resolveArg(args[1], teacherNames, assistantName), assistantName)
            .filter { it.isNotBlank() }
            .joinToString(",")
        return TaskInfo(
            name = courseName,
            teacher = teacher,
            room = resolveArg(args[5], teacherNames, assistantName),
            validWeeks = resolveArg(args[6], teacherNames, assistantName)
        )
    }

    // ===== 主解析 =====

    fun parseTimetable(html: String?): ParsedSchedule {
        if (html.isNullOrBlank()) throw ParseException("课表源码为空")
        val unitCountMatch = reUnitCount.find(html)
        val marshalMatch = reMarshal.find(html)
        if (unitCountMatch == null || marshalMatch == null) {
            throw ParseException("没有找到 EAMS TaskActivity 课表数据，请确认文件来自登录后的学生课表页")
        }
        val unitCount = unitCountMatch.groupValues[1].toIntOrNull() ?: 0
        val from = marshalMatch.groupValues[1].toIntOrNull() ?: 0
        val startWeek = marshalMatch.groupValues[2].toIntOrNull() ?: 0
        val endWeek = marshalMatch.groupValues[3].toIntOrNull() ?: 0
        if (unitCount == 0 || startWeek < 1 || endWeek < startWeek) throw ParseException("课表周次数据异常")

        val segments = html.split("new TaskActivity(")
        val rawCourses = mutableListOf<Course>()
        for (index in 1 until segments.size) {
            val segment = segments[index]
            val callEnd = segment.indexOf(");")
            if (callEnd < 0) continue
            val rest = segment.substring(callEnd + 2)
            val indexMatches = reIndex.findAll(rest).toList()
            if (indexMatches.isEmpty()) continue

            val previous = segments[index - 1]
            val teacherDeclarations = reActTeachers.findAll(previous).toList()
            val lastTeacher = teacherDeclarations.lastOrNull()
            val teacherNames = lastTeacher
                ?.let { reTeacherName.findAll(it.groupValues[1]).map { m -> stringLiteral("\"" + m.groupValues[1] + "\"") }.toList() }
                ?: emptyList()
            val assistantMatches = reAssistant.findAll(previous).toList()
            val assistantName = if (assistantMatches.isNotEmpty()) {
                stringLiteral("\"" + assistantMatches.last().groupValues[1] + "\"")
            } else ""

            val info = resolveTaskActivityArgs(segment.substring(0, callEnd), teacherNames, assistantName)
            if (info == null || !reBinary.matches(info.validWeeks)) continue

            val validStart = from + startWeek - 2
            var validWeeks = info.validWeeks
            if (validWeeks.jsSlice(0, validStart).contains('1') && !validWeeks.jsSlice(validStart).contains('1')) {
                validWeeks = validWeeks.jsSlice(1) + "0"
            }

            val weeks = mutableListOf<Int>()
            for (week in startWeek..endWeek) {
                val validIndex = from + week - 2
                if (validIndex in 0 until validWeeks.length && validWeeks[validIndex] == '1') weeks += week
            }
            if (weeks.isEmpty()) continue

            indexMatches.forEach { match ->
                val day = (match.groupValues[1].toIntOrNull() ?: 0) + 1
                val section = (match.groupValues[2].toIntOrNull() ?: 0) + 1
                if (day in 1..7 && section in 1..12) {
                    rawCourses += Course(info.name, info.teacher, info.room, day, section, section, weeks.toList())
                }
            }
        }
        val courses = mergeAndDistinctCourses(rawCourses)
        if (courses.isEmpty()) throw ParseException("已识别课表结构，但没有解析到有效课程")
        return ParsedSchedule(
            name = "我的课表",
            semesterStart = "",
            totalWeeks = endWeek,
            timeSlots = DEFAULT_TIME_SLOTS,
            courses = courses
        )
    }

    private fun sameWeeks(a: List<Int>, b: List<Int>) = a.joinToString(",") == b.joinToString(",")

    /** 先合并相邻节次，再对同课程同节次的周次去重合并。 */
    fun mergeAndDistinctCourses(courses: List<Course>): List<Course> {
        if (courses.isEmpty()) return emptyList()
        val list = courses.map {
            Work(it.name, it.teacher, it.position, it.day, it.startSection, it.endSection,
                it.weeks.distinct().sorted().toMutableList())
        }.sortedWith(
            compareBy({ it.name }, { it.teacher }, { it.position }, { it.day },
                { it.weeks.joinToString(",") }, { it.startSection })
        )

        // 第一趟：合并连续节次
        val consecutive = mutableListOf<Work>()
        var current = Work(list[0].name, list[0].teacher, list[0].position, list[0].day,
            list[0].startSection, list[0].endSection, list[0].weeks.toMutableList())
        for (index in 1 until list.size) {
            val next = list[index]
            val sameCourse = current.name == next.name && current.teacher == next.teacher &&
                current.position == next.position && current.day == next.day && sameWeeks(current.weeks, next.weeks)
            if (sameCourse && current.endSection + 1 == next.startSection) {
                current.endSection = next.endSection
            } else if (!(sameCourse && current.startSection == next.startSection && current.endSection == next.endSection)) {
                consecutive += current
                current = Work(next.name, next.teacher, next.position, next.day,
                    next.startSection, next.endSection, next.weeks.toMutableList())
            }
        }
        consecutive += current

        consecutive.sortWith(
            compareBy({ it.name }, { it.teacher }, { it.position }, { it.day },
                { it.startSection }, { it.endSection })
        )

        // 第二趟：合并同节次的周次
        val merged = mutableListOf<Work>()
        current = Work(consecutive[0].name, consecutive[0].teacher, consecutive[0].position, consecutive[0].day,
            consecutive[0].startSection, consecutive[0].endSection, consecutive[0].weeks.toMutableList())
        for (index in 1 until consecutive.size) {
            val next = consecutive[index]
            val sameSlot = current.name == next.name && current.teacher == next.teacher &&
                current.position == next.position && current.day == next.day &&
                current.startSection == next.startSection && current.endSection == next.endSection
            if (sameSlot) {
                current.weeks = (current.weeks + next.weeks).distinct().sorted().toMutableList()
            } else {
                merged += current
                current = Work(next.name, next.teacher, next.position, next.day,
                    next.startSection, next.endSection, next.weeks.toMutableList())
            }
        }
        merged += current
        return merged.map { it.toCourse() }
    }

    fun normalizeJsonPackage(value: JSONObject?): ParsedSchedule {
        val source = value?.optJSONObject("schedule") ?: value
            ?: throw ParseException("JSON 中没有 courses 数组")
        val coursesArray = source.optJSONArray("courses")
            ?: throw ParseException("JSON 中没有 courses 数组")
        val courses = (0 until coursesArray.length()).mapNotNull { i ->
            coursesArray.optJSONObject(i)
        }.map { o ->
            Course(
                name = o.optString("name").trim(),
                teacher = o.optString("teacher").trim(),
                position = o.optString("position").ifEmpty { o.optString("room") }.trim(),
                day = o.optInt("day"),
                startSection = o.optInt("startSection"),
                endSection = o.optInt("endSection", o.optInt("startSection")),
                weeks = o.optJSONArray("weeks").toIntList()
            )
        }.filter {
            it.name.isNotEmpty() && it.day in 1..7 && it.startSection >= 1 && it.endSection <= 12 && it.weeks.isNotEmpty()
        }
        if (courses.isEmpty()) throw ParseException("JSON 中没有有效课程")
        val maxWeek = courses.flatMap { it.weeks }.maxOrNull() ?: 0
        val slotsArray = source.optJSONArray("timeSlots")
        val timeSlots = if (slotsArray != null && slotsArray.length() > 0) {
            (0 until min(slotsArray.length(), 12)).map { TimeSlot.fromJson(slotsArray.optJSONObject(it) ?: JSONObject()) }
        } else DEFAULT_TIME_SLOTS
        return ParsedSchedule(
            name = source.optString("name", "我的课表").trim().ifEmpty { "我的课表" },
            semesterStart = source.optString("semesterStart").trim(),
            totalWeeks = source.optInt("totalWeeks").takeIf { it > 0 } ?: (maxWeek.takeIf { it > 0 } ?: 20),
            timeSlots = timeSlots,
            courses = mergeAndDistinctCourses(courses)
        )
    }

    fun parseImportContent(content: String?): ParsedSchedule {
        val trimmed = (content ?: "").trim()
        if (trimmed.isEmpty()) throw ParseException("导入内容为空")
        var source = trimmed
        if (source.startsWith("```")) {
            source = source
                .replace(Regex("^```(?:json|html|javascript)?\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*```$"), "")
        }
        val first = source.trimStart().firstOrNull()
        if (first == '{' || first == '[') {
            val value: Any? = try {
                JSONTokener(source).nextValue()
            } catch (e: Exception) {
                throw ParseException("JSON 格式错误：${e.message ?: "解析失败"}")
            }
            val obj = value as? JSONObject
            return try {
                normalizeJsonPackage(obj)
            } catch (e: ParseException) {
                if (e.message == "JSON 中没有 courses 数组" || e.message == "JSON 中没有有效课程") throw e
                throw ParseException("JSON 格式错误：${e.message}")
            }
        }
        return parseTimetable(source)
    }

    /** 供 WebView 注入脚本回传结果做二次规范化时使用。 */
    fun normalizeJsonString(json: String): ParsedSchedule = normalizeJsonPackage(JSONObject(json))
}
