package com.zhusijiao.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.R
import com.zhusijiao.app.data.LeaveStore
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.domain.DateUtils
import com.zhusijiao.app.domain.DayAgenda
import com.zhusijiao.app.domain.Leave
import com.zhusijiao.app.domain.Leaves
import com.zhusijiao.app.domain.PersonalEvent
import com.zhusijiao.app.domain.Schedule
import com.zhusijiao.app.domain.ScheduleView
import com.zhusijiao.app.reminder.ClassReminders
import com.zhusijiao.app.reminder.ReminderPermissions
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 桌面小部件：三种尺寸，都显示「当前课表」（与课表页、上课提醒同一份）。
 *
 * - 小号「下一节课」：接下来要上哪节、几点、在哪；今天没课时预告之后最近的一节；
 * - 中号「今日课程」：今天的课一行一节，行数随高度变化，放得下时接着列出之后最近有课那天的；
 * - 大号「本周课表」：整周课表画成一张图（[WeekWidgetRenderer]）。
 *
 * 内容来自纯 Kotlin 的 [DayAgenda]（有单元测试），这里只负责读数据、拼 RemoteViews 和定时。
 *
 * 刷新时机：课表、日程、请假、当前课表任一变化时（[ClassReminders.requestSync] 里顺带触发）；
 * 每到今天某节课开始或结束、以及零点（[scheduleRefresh]）；系统每 30 分钟兜底一次。
 * 小部件上只写「几点到几点」，不写「还有几分钟」——桌面小部件做不到按分钟刷新，写了就会不准。
 */
object ScheduleWidgets {

    private const val ACTION_REFRESH = "com.zhusijiao.app.action.WIDGET_REFRESH"
    private const val REQUEST_REFRESH = 3201
    private const val REQUEST_OPEN = 3202

    /** 没有「闹钟和提醒」权限时系统只保证在这个时间窗内送达（Android 12 起最短 10 分钟）。 */
    private const val INEXACT_WINDOW_MILLIS = 10 * 60_000L

    /** 两次定时刷新至少隔这么久，防止边界时刻附近反复触发。 */
    private const val MIN_REFRESH_GAP_MILLIS = 15_000L

    // 「今日课程」各部分的高度（dp），与 widget_today.xml / widget_row.xml 对应，用来算放得下几行
    private const val TODAY_CHROME_DP = 12 + 20 + 6 + 10
    private const val TODAY_ROW_DP = 26
    private const val DEFAULT_TODAY_HEIGHT_DP = 110

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "schedule-widgets").apply { isDaemon = true }
    }

    /** 数据变了或到点了：异步重画所有小部件。任意线程都可以调用；没有添加小部件时几乎零开销。 */
    fun requestUpdate(onDone: (() -> Unit)? = null) {
        executor.execute {
            try {
                runCatching { updateAll(MainApplication.appContext) }
            } finally {
                onDone?.invoke()
            }
        }
    }

    private enum class Kind(val provider: Class<*>) {
        NEXT(NextClassWidget::class.java),
        TODAY(TodayWidget::class.java),
        WEEK(WeekWidget::class.java)
    }

    private class Data(
        val schedule: Schedule?,
        val events: List<PersonalEvent>,
        val leaves: List<Leave>,
        val snapshot: DayAgenda.Snapshot?,
        val now: Long
    )

    private fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = Kind.entries.associateWith { kind ->
            runCatching { manager.getAppWidgetIds(ComponentName(context, kind.provider)) }.getOrNull() ?: IntArray(0)
        }
        if (ids.values.all { it.isEmpty() }) {
            cancelRefresh(context)
            return
        }
        val now = System.currentTimeMillis()
        val schedule = ClassReminders.currentSchedule()
        val events = schedule?.let { PersonalEventStore.list(it.id) }.orEmpty()
        val leaves = LeaveStore.list()
        val data = Data(schedule, events, leaves, schedule?.let { DayAgenda.snapshot(it, events, leaves, now) }, now)

        ids.getValue(Kind.NEXT).forEach { id ->
            runCatching { manager.updateAppWidget(id, nextViews(context, data)) }
        }
        ids.getValue(Kind.TODAY).forEach { id ->
            runCatching { manager.updateAppWidget(id, todayViews(context, data, sizeOf(context, manager, id).second)) }
        }
        ids.getValue(Kind.WEEK).forEach { id ->
            runCatching {
                val (width, height) = sizeOf(context, manager, id)
                manager.updateAppWidget(id, weekViews(context, data, width, height))
            }
        }
        scheduleRefresh(context, data.snapshot?.refreshAtMillis ?: nextMidnight(now), now)
    }

    /** 小部件当前的宽高（dp）；桌面没有上报时为 0。竖屏取「最小宽 × 最大高」，横屏反过来。 */
    private fun sizeOf(context: Context, manager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val options = manager.getAppWidgetOptions(id) ?: return 0 to 0
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) to
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        } else {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) to
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        }
    }

    // ===== 定时刷新 =====

    private fun scheduleRefresh(context: Context, refreshAtMillis: Long, nowMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = maxOf(refreshAtMillis + 1_000L, nowMillis + MIN_REFRESH_GAP_MILLIS)
        val operation = refreshIntent(context)
        // 用不唤醒设备的定时：熄屏时不必为了桌面小部件叫醒手机，亮屏后系统会立刻补发
        if (ReminderPermissions.exactAlarmAllowed(context)) {
            try {
                manager.setExact(AlarmManager.RTC, trigger, operation)
                return
            } catch (_: SecurityException) {
                // 权限刚被收回：退回不精确定时
            }
        }
        manager.setWindow(AlarmManager.RTC, trigger, INEXACT_WINDOW_MILLIS, operation)
    }

    private fun cancelRefresh(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(refreshIntent(context))
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_REFRESH,
        Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun nextMidnight(nowMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** 点小部件任意位置：打开 App 并回到课表页。 */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 没有课表、没有开学日期、学期结束时的两行说明（标题 + 一句话）；一切正常返回 null。 */
    private fun blockerText(context: Context, data: Data): Pair<String, String>? = when {
        data.schedule == null ->
            context.getString(R.string.widget_no_schedule_title) to context.getString(R.string.widget_no_schedule_hint)
        data.snapshot?.blocker == DayAgenda.Blocker.NO_SEMESTER_START ->
            context.getString(R.string.widget_no_semester_title) to context.getString(R.string.widget_no_semester_hint)
        data.snapshot?.blocker == DayAgenda.Blocker.SEMESTER_OVER ->
            context.getString(R.string.widget_semester_over_title) to context.getString(R.string.widget_semester_over_hint)
        else -> null
    }

    // ===== 小号：下一节课 =====

    private fun nextViews(context: Context, data: Data): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_next)
        views.setOnClickPendingIntent(android.R.id.background, openApp(context))
        fun fill(label: String, title: String, time: String, place: String) {
            views.setTextViewText(R.id.widgetNextLabel, label)
            views.setTextViewText(R.id.widgetNextTitle, title)
            views.setTextViewText(R.id.widgetNextTime, time)
            views.setViewVisibility(R.id.widgetNextTime, if (time.isBlank()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widgetNextPlace, place)
            views.setViewVisibility(R.id.widgetNextPlace, if (place.isBlank()) View.GONE else View.VISIBLE)
        }
        blockerText(context, data)?.let { (title, hint) ->
            fill(context.getString(R.string.app_name), title, "", hint)
            return views
        }
        val snapshot = data.snapshot!!
        val next = snapshot.next
        if (next != null) {
            val item = next.occurrence
            val ongoing = next.state == DayAgenda.State.ONGOING
            fill(
                label = context.getString(
                    when {
                        item.isEvent && ongoing -> R.string.widget_label_event_ongoing
                        item.isEvent -> R.string.widget_label_event_next
                        ongoing -> R.string.widget_label_ongoing
                        else -> R.string.widget_label_next
                    }
                ),
                title = item.title,
                time = context.getString(R.string.widget_time_range, item.startTime, item.endTime),
                place = item.position
            )
            return views
        }
        // 今天没有要上的了：说清楚是「上完了」还是「本来就没课」，再预告之后最近的一节
        val label = context.getString(
            when {
                snapshot.today == null -> R.string.widget_label_before_semester
                snapshot.today.attending.isEmpty() -> R.string.widget_label_today_free
                else -> R.string.widget_label_today_done
            }
        )
        val upcoming = snapshot.upcoming
        val first = upcoming?.attending?.firstOrNull()
        if (upcoming == null || first == null) {
            fill(label, context.getString(R.string.widget_nothing_soon), "", "")
        } else {
            fill(
                label = label,
                title = first.occurrence.title,
                time = context.getString(
                    R.string.widget_day_time,
                    dayLabel(context, upcoming),
                    context.getString(R.string.widget_time_range, first.occurrence.startTime, first.occurrence.endTime)
                ),
                place = first.occurrence.position
            )
        }
        return views
    }

    // ===== 中号：今日课程 =====

    private fun todayViews(context: Context, data: Data, heightDp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        views.setOnClickPendingIntent(android.R.id.background, openApp(context))
        views.removeAllViews(R.id.widgetList)
        val today = Calendar.getInstance().apply { timeInMillis = data.now }
        views.setTextViewText(
            R.id.widgetTodayDate,
            context.getString(R.string.widget_date, today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
        )
        blockerText(context, data)?.let { (title, hint) ->
            views.setTextViewText(R.id.widgetTodayTitle, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widgetEmpty, "$title\n$hint")
            views.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
            return views
        }
        val snapshot = data.snapshot!!
        val schedule = data.schedule!!
        val dayName = Leaves.dayName(DateUtils.dayOfWeek(today))
        views.setTextViewText(
            R.id.widgetTodayTitle,
            snapshot.today?.let { context.getString(R.string.widget_week_day_title, it.week, dayName) }
                ?: "${context.getString(R.string.widget_label_before_semester)} · $dayName"
        )

        val height = if (heightDp > 0) heightDp else DEFAULT_TODAY_HEIGHT_DP
        var budget = ((height - TODAY_CHROME_DP) / TODAY_ROW_DP).coerceAtLeast(1)
        val palette = ScheduleView.buildCoursePaletteMap(
            schedule.courses + schedule.adjustments.map { it.courseSnapshot },
            schedule.courseColors
        )
        val accent = ContextCompat.getColor(context, R.color.accent)
        fun addRow(item: DayAgenda.Item) {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            val occurrence = item.occurrence
            val faded = item.state == DayAgenda.State.DONE || item.leave != null
            val ongoing = item.state == DayAgenda.State.ONGOING && item.leave == null
            val strong = ContextCompat.getColor(
                context,
                when {
                    faded -> R.color.text_disabled
                    ongoing -> R.color.accent_strong
                    else -> R.color.title_ink
                }
            )
            row.setTextViewText(R.id.widgetRowTime, occurrence.startTime)
            row.setTextColor(R.id.widgetRowTime, strong)
            row.setTextViewText(R.id.widgetRowTitle, occurrence.title)
            row.setTextColor(R.id.widgetRowTitle, strong)
            val color = if (occurrence.isEvent) accent else palette[occurrence.title]?.background ?: accent
            row.setInt(R.id.widgetRowBar, "setColorFilter", color)
            row.setInt(R.id.widgetRowBar, "setImageAlpha", if (faded) 90 else 255)
            // 请假的课不写教室，直接写「请假 / 公假」
            row.setTextViewText(R.id.widgetRowPlace, item.leave?.label ?: occurrence.position)
            row.setTextColor(
                R.id.widgetRowPlace,
                ContextCompat.getColor(context, if (item.leave != null) R.color.warn else R.color.text_tertiary)
            )
            views.addView(R.id.widgetList, row)
        }
        fun addNote(text: String) {
            val note = RemoteViews(context.packageName, R.layout.widget_section)
            note.setTextViewText(R.id.widgetSectionText, text)
            views.addView(R.id.widgetList, note)
        }

        val todayItems = snapshot.today?.items.orEmpty()
        val upcoming = snapshot.upcoming
        if (todayItems.isEmpty() && upcoming == null) {
            views.setTextViewText(
                R.id.widgetEmpty,
                if (snapshot.today == null) context.getString(R.string.widget_label_before_semester)
                else context.getString(R.string.widget_label_today_free) + "\n" + context.getString(R.string.widget_nothing_soon)
            )
            views.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
            return views
        }
        views.setViewVisibility(R.id.widgetEmpty, View.GONE)

        if (todayItems.isEmpty()) {
            if (snapshot.today != null) {
                addNote(context.getString(R.string.widget_label_today_free))
                budget--
            }
        } else {
            // 放不下时先丢掉已经上完的，还放不下就截断并写「还有 N 节」
            var shown = todayItems
            while (shown.size > budget && shown.first().state == DayAgenda.State.DONE) shown = shown.drop(1)
            if (shown.size > budget) {
                val visible = shown.take((budget - 1).coerceAtLeast(1))
                visible.forEach(::addRow)
                addNote(context.getString(R.string.widget_more, shown.size - visible.size))
                return views
            }
            shown.forEach(::addRow)
            budget -= shown.size
        }
        // 还有空位：接着列出之后最近有课的那天（标题一行 + 至少一节）
        if (upcoming != null && budget >= 2) {
            addNote(dayLabel(context, upcoming))
            upcoming.attending.take(budget - 1).forEach(::addRow)
        }
        return views
    }

    /** 之后某天的叫法：明天 / 后天 / 这几天内写「周五」/ 再远写「10月12日 周一」。 */
    private fun dayLabel(context: Context, day: DayAgenda.Day): String {
        val name = Leaves.dayName(day.day)
        return when {
            day.offsetDays == 1 -> context.getString(R.string.widget_section_day, context.getString(R.string.widget_tomorrow), name)
            day.offsetDays == 2 -> context.getString(R.string.widget_section_day, context.getString(R.string.widget_day_after), name)
            day.offsetDays < 7 -> name
            else -> {
                val parts = day.dateIso.split("-").mapNotNull { it.toIntOrNull() }
                if (parts.size == 3) context.getString(R.string.widget_date_day, parts[1], parts[2], name) else name
            }
        }
    }

    // ===== 大号：本周课表 =====

    private fun weekViews(context: Context, data: Data, widthDp: Int, heightDp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_week)
        views.setOnClickPendingIntent(android.R.id.background, openApp(context))
        val today = Calendar.getInstance().apply { timeInMillis = data.now }
        views.setTextViewText(
            R.id.widgetWeekDate,
            context.getString(
                R.string.widget_date_day,
                today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH),
                Leaves.dayName(DateUtils.dayOfWeek(today))
            )
        )
        val schedule = data.schedule
        val blocker = blockerText(context, data)
        // 学期结束后仍然画最后一周，只有没课表、没开学日期时才只显示说明
        if (schedule == null || data.snapshot?.blocker == DayAgenda.Blocker.NO_SEMESTER_START) {
            views.setTextViewText(R.id.widgetWeekTitle, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widgetEmpty, blocker?.let { "${it.first}\n${it.second}" }.orEmpty())
            views.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
            views.setViewVisibility(R.id.widgetWeekImage, View.GONE)
            return views
        }
        val week = DateUtils.currentWeek(schedule.semesterStart, schedule.totalWeeks, data.now)
        views.setTextViewText(R.id.widgetWeekTitle, context.getString(R.string.widget_week_title, week))
        val bitmap = WeekWidgetRenderer.render(context, schedule, data.events, data.leaves, week, widthDp, heightDp, data.now)
        if (bitmap == null) {
            views.setTextViewText(R.id.widgetEmpty, context.getString(R.string.widget_loading))
            views.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
            views.setViewVisibility(R.id.widgetWeekImage, View.GONE)
        } else {
            views.setImageViewBitmap(R.id.widgetWeekImage, bitmap)
            views.setViewVisibility(R.id.widgetWeekImage, View.VISIBLE)
            views.setViewVisibility(R.id.widgetEmpty, View.GONE)
        }
        return views
    }
}
