package com.zhusijiao.app.data

import com.zhusijiao.app.BuildConfig
import com.zhusijiao.app.domain.ExtraCourse
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.export.ExportOptions
import com.zhusijiao.app.domain.export.ExportSnapshot
import com.zhusijiao.app.domain.export.IcsExporter
import com.zhusijiao.app.domain.export.ScheduleBackup
import com.zhusijiao.app.domain.export.ScheduleXlsx
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 导出课表：读本机的课表、自己加的课、日程、请假，按所选格式写进输出流。
 * 全在本机完成，不经服务器——自己加的课、日程、请假本来就只在本机（见 docs/DECISIONS.md D26）。
 */
object ScheduleExporter {

    enum class Format(val extension: String, val mimeType: String) {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        ICS("ics", "text/calendar"),
        JSON("json", "application/json")
    }

    /** 导出要用的全部本机数据；[stored] 未并入自己加的课。 */
    class Source(
        val stored: Schedule,
        val extras: List<ExtraCourse>,
        val events: List<PersonalEvent>,
        val leaves: List<Leave>
    )

    /** 会读本机文件，请在后台线程调用。 */
    fun load(scheduleId: String): Source {
        val stored = LocalScheduleStore.getSchedule(scheduleId)
        return Source(
            stored = stored,
            extras = ExtraCourseStore.list(stored.id),
            events = PersonalEventStore.list(stored.id),
            leaves = LeaveStore.list()
        )
    }

    fun snapshot(source: Source, options: ExportOptions): ExportSnapshot.Snapshot = ExportSnapshot.build(
        source.stored,
        source.extras,
        source.events,
        source.leaves,
        Prefs.weekendDisplayMode,
        options
    )

    /** 写出文件内容。导出日历但课表没有开学日期时抛 [ApiException]（由调用方先拦更好）。 */
    fun write(source: Source, format: Format, options: ExportOptions, output: OutputStream, nowMillis: Long = System.currentTimeMillis()) {
        when (format) {
            Format.XLSX -> ScheduleXlsx.write(snapshot(source, options), output)
            Format.ICS -> {
                val snapshot = snapshot(source, options)
                if (!IcsExporter.canExport(snapshot)) throw ApiException("课表没有开学日期，无法导出日历")
                output.write(IcsExporter.build(snapshot, options, nowMillis).toByteArray(Charsets.UTF_8))
            }
            Format.JSON -> {
                val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(nowMillis))
                val json = ScheduleBackup.encode(
                    source.stored, source.extras, source.events, source.leaves, options,
                    BuildConfig.VERSION_NAME, exportedAt
                )
                output.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }
        }
        output.flush()
    }

    /**
     * 文件名：`课表名-第1-20周.xlsx`、`课表名-第3周.ics`、`课表名-第1-15周单周.xlsx`、`课表名-备份-20261026.json`。
     * 零散的周次只写首尾（`第3-9周选`）。去掉文件系统不允许的字符；课表名为空时用「课表」。
     */
    fun fileName(source: Source, format: Format, options: ExportOptions, nowMillis: Long = System.currentTimeMillis()): String {
        val base = source.stored.name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(40).ifBlank { "课表" }
        val suffix = if (format == Format.JSON) {
            "备份-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMillis))
        } else {
            val total = if (source.stored.totalWeeks > 0) source.stored.totalWeeks else 20
            val weeks = ExportSnapshot.selectedWeeks(options, total).ifEmpty { listOf(1) }
            val first = weeks.first()
            val last = weeks.last()
            when {
                first == last -> "第${first}周"
                weeks.size == last - first + 1 -> "第$first-${last}周"
                else -> "第$first-${last}周" + ScheduleView.alternatingWeekBadge(weeks).ifEmpty { "选" }
            }
        }
        return "$base-$suffix.${format.extension}"
    }
}
