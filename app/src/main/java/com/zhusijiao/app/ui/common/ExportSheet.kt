package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.View
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ScheduleExporter.Format
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.domain.export.ExportOptions

/**
 * 导出课表底部面板。标题是课表名；第一眼只要选格式，其余都是带默认值的行：
 * 周次（默认整个学期，点开是加课用的同一个 [WeeksSheet]）、提醒（只有日历有）、
 * 自加课程 / 日程 / 请假三个开关（请假默认关：导出的文件常常会发给别人）。
 * 备份总是完整的，所以选备份时没有周次这一行。
 *
 * 只收集选项，不碰文件：写出、系统文件选择器、分享都在 [ExportFlow] 里。
 */
class ExportSheet(
    context: Context,
    scheduleName: String,
    private val totalWeeks: Int,
    private val currentWeek: Int,
    private val hasDates: Boolean,
    private val onSave: (Format, ExportOptions) -> Unit,
    private val onShare: (Format, ExportOptions) -> Unit
) : SheetDialog(context) {

    private val formats = listOf(Format.XLSX, Format.ICS, Format.JSON)
    private val alarms = listOf<Int?>(null, 10, 15, 30)
    private val allWeeks = (1..totalWeeks).toList()
    private var weeks: List<Int> = allWeeks

    private val formatChoice by lazy { findViewById<SegmentedChoiceView>(R.id.exportFormat) }
    private val alarmChoice by lazy { findViewById<SegmentedChoiceView>(R.id.exportAlarm) }
    private val extrasToggle: ToggleView
    private val eventsToggle: ToggleView
    private val leavesToggle: ToggleView

    private val format: Format get() = formats[formatChoice.selectedIndex]

    /** 日历要靠开学日期推算每节课的日子，没有就导不了：按钮置灰，并在格式下面说一行。 */
    private val blocked: Boolean get() = format == Format.ICS && !hasDates

    init {
        setContentView(R.layout.dialog_export)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.exportTitle).text = context.getString(R.string.export_title, scheduleName)

        formatChoice.configure(
            listOf(
                context.getString(R.string.export_format_xlsx),
                context.getString(R.string.export_format_ics),
                context.getString(R.string.export_format_json)
            ),
            0,
            context.getString(R.string.export_format_prefix)
        ) { render() }
        alarmChoice.configure(
            alarms.map { minutes ->
                if (minutes == null) context.getString(R.string.export_alarm_none)
                else context.getString(R.string.export_alarm_minutes, minutes)
            },
            0,
            context.getString(R.string.export_alarm_prefix)
        ) {}

        findViewById<View>(R.id.exportWeeksRow).setOnClickListener {
            val anchor = currentWeek.coerceIn(1, totalWeeks)
            WeeksSheet(context, totalWeeks, from = 1, initial = weeks, onlyWeek = anchor) { picked ->
                weeks = picked
                render()
            }.show()
        }
        extrasToggle = bindToggle(R.id.exportExtrasRow, R.id.exportExtrasToggle, checked = true)
        eventsToggle = bindToggle(R.id.exportEventsRow, R.id.exportEventsToggle, checked = true)
        leavesToggle = bindToggle(R.id.exportLeavesRow, R.id.exportLeavesToggle, checked = false)

        findViewById<View>(R.id.exportSave).setOnClickListener { submit(onSave) }
        findViewById<View>(R.id.exportShare).setOnClickListener { submit(onShare) }
        render()
    }

    private fun bindToggle(rowId: Int, toggleId: Int, checked: Boolean): ToggleView {
        val toggle = findViewById<ToggleView>(toggleId)
        toggle.setChecked(checked, animate = false)
        findViewById<View>(rowId).setOnClickListener { toggle.toggle() }
        return toggle
    }

    private fun render() {
        val json = format == Format.JSON
        val ics = format == Format.ICS
        findViewById<View>(R.id.exportError).visibility = if (blocked) View.VISIBLE else View.GONE
        val weeksVisibility = if (json) View.GONE else View.VISIBLE
        findViewById<View>(R.id.exportWeeksRow).visibility = weeksVisibility
        findViewById<View>(R.id.exportWeeksDivider).visibility = weeksVisibility
        findViewById<TextView>(R.id.exportWeeksValue).text =
            if (weeks == allWeeks) context.getString(R.string.export_weeks_all)
            else ScheduleView.formatWeekSummary(weeks)
        val alarmVisibility = if (ics) View.VISIBLE else View.GONE
        findViewById<View>(R.id.exportAlarmRow).visibility = alarmVisibility
        findViewById<View>(R.id.exportAlarmDivider).visibility = alarmVisibility
        listOf(R.id.exportSave, R.id.exportShare).forEach { id ->
            findViewById<View>(id).apply {
                isEnabled = !blocked
                alpha = if (blocked) 0.4f else 1f
            }
        }
    }

    private fun submit(action: (Format, ExportOptions) -> Unit) {
        if (blocked) return
        val options = ExportOptions(
            weeks = if (format == Format.JSON || weeks == allWeeks) null else weeks,
            includeExtras = extrasToggle.isChecked,
            includeEvents = eventsToggle.isChecked,
            markLeaves = leavesToggle.isChecked,
            alarmMinutes = if (format == Format.ICS) alarms[alarmChoice.selectedIndex] else null
        )
        dismiss()
        action(format, options)
    }
}
