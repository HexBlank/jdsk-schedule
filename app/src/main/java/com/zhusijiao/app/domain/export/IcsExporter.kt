package com.zhusijiao.app.domain.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把 [ExportSnapshot] 写成 iCalendar（RFC 5545）文件，导入 Outlook、Apple 日历、Google 日历等。
 *
 * - 一次上课写一个 VEVENT，不用 RRULE：调课、停课、补课会打乱规律，逐条写最准确；
 * - UID 用 [ExportSnapshot.Item.uid]，重复导入时按 UID 识别的日历会更新原事件而不是新增一份；
 * - 时间是课表的墙上时间，按 Asia/Shanghai 写并附上 VTIMEZONE（中国不实行夏令时，一段 STANDARD 即可）；
 * - 请假的课标题前加「【请假】」并设为 TRANSPARENT（不占忙闲），也不提醒。
 *
 * 课表没有开学日期时推不出每节课是哪一天，调用方应先拦下（[canExport]）。
 */
object IcsExporter {

    private const val TZID = "Asia/Shanghai"
    private const val UID_DOMAIN = "jidianshangke"
    private const val MAX_LINE_OCTETS = 75

    fun canExport(snapshot: ExportSnapshot.Snapshot): Boolean = snapshot.hasDates

    fun build(snapshot: ExportSnapshot.Snapshot, options: ExportOptions, nowMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMillis))
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//$UID_DOMAIN//schedule export//ZH",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:${escape(snapshot.scheduleName)}",
            "X-WR-TIMEZONE:$TZID",
            "BEGIN:VTIMEZONE",
            "TZID:$TZID",
            "BEGIN:STANDARD",
            "DTSTART:19700101T000000",
            "TZOFFSETFROM:+0800",
            "TZOFFSETTO:+0800",
            "TZNAME:CST",
            "END:STANDARD",
            "END:VTIMEZONE"
        )
        snapshot.weeks.forEach { week ->
            week.items.forEach { item ->
                val day = week.days.firstOrNull { it.day == item.day } ?: return@forEach
                val date = day.dateIso?.replace("-", "") ?: return@forEach
                val start = item.startTime?.replace(":", "") ?: return@forEach
                val end = item.endTime?.replace(":", "") ?: return@forEach
                if (end <= start) return@forEach
                lines += event(item, week.week, date, start, end, stamp, options)
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private fun event(
        item: ExportSnapshot.Item,
        week: Int,
        date: String,
        start: String,
        end: String,
        stamp: String,
        options: ExportOptions
    ): List<String> {
        val leave = item.leave
        val summary = (if (leave != null) "【${leave.label}】" else "") + item.title
        val sections = if (item.startSection == item.endSection) "第${item.startSection}节"
        else "第${item.startSection}–${item.endSection}节"
        val description = listOfNotNull(
            item.teacher.takeIf { it.isNotBlank() }?.let { "教师：$it" },
            "第${week}周 · $sections",
            when (item.kind) {
                ExportSnapshot.Kind.ADJUSTED -> "本次为调课"
                ExportSnapshot.Kind.MAKEUP -> "本次为补课"
                else -> null
            },
            item.note.takeIf { it.isNotBlank() }
        ).joinToString("\n")
        val result = mutableListOf(
            "BEGIN:VEVENT",
            "UID:${escape(item.uid)}@$UID_DOMAIN",
            "DTSTAMP:$stamp",
            "DTSTART;TZID=$TZID:${date}T${start}00",
            "DTEND;TZID=$TZID:${date}T${end}00",
            "SUMMARY:${escape(summary)}"
        )
        if (item.position.isNotBlank()) result += "LOCATION:${escape(item.position)}"
        result += "DESCRIPTION:${escape(description)}"
        result += "CATEGORIES:${if (item.isEvent) "日程" else "课程"}"
        result += "TRANSP:${if (leave != null) "TRANSPARENT" else "OPAQUE"}"
        val alarm = options.alarmMinutes
        if (alarm != null && alarm >= 0 && leave == null) {
            result += listOf(
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "DESCRIPTION:${escape(summary)}",
                "TRIGGER:-PT${alarm}M",
                "END:VALARM"
            )
        }
        result += "END:VEVENT"
        return result
    }

    /** TEXT 值转义：反斜杠、分号、逗号、换行。 */
    fun escape(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    /**
     * 折行：每行不超过 75 个**字节**（UTF-8），续行以一个空格开头（空格也算在 75 里）。
     * 按码点切，不会把一个汉字或表情的字节切成两半。
     */
    fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_OCTETS) return line
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > MAX_LINE_OCTETS) {
                out.append("\r\n ")
                used = 1
            }
            out.appendCodePoint(codePoint)
            used += bytes
            index += chars
        }
        return out.toString()
    }
}
