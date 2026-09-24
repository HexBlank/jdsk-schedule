package com.zhusijiao.app.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhusijiao.app.MainActivity
import com.zhusijiao.app.MainApplication
import com.zhusijiao.app.R
import com.zhusijiao.app.data.LocalScheduleStore
import com.zhusijiao.app.data.PersonalEventStore
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.domain.ClassReminderPlanner
import com.zhusijiao.app.domain.ReminderOccurrence
import com.zhusijiao.app.domain.Schedule
import java.util.concurrent.Executors

/**
 * 上课通知提醒：在当前课表每节课开始前 N 分钟发一条普通通知（不是响铃闹钟）。
 *
 * 做法是「只排下一次」：算出下一节课的提醒时刻，交给系统定时唤醒；到点发通知后
 * 再排下一次。课表、日程、设置、当前课表任一变化，或开机、改系统时间、App 升级后，
 * 都调用 [requestSync] 从头重算并覆盖同一个定时，所以不会留下作废的旧提醒。
 *
 * 排期逻辑在纯 Kotlin 的 [ClassReminderPlanner] 里（有单元测试），这里只负责
 * 读数据、调系统定时和发通知。
 */
object ClassReminders {

    const val CHANNEL_ID = "class_reminder"

    private const val ACTION_FIRE = "com.zhusijiao.app.action.CLASS_REMINDER"
    private const val REQUEST_FIRE = 3101
    private const val REQUEST_OPEN = 3102
    private const val NOTIFICATION_ID = 3101
    private const val TEST_TAG = "class-reminder-test"

    /**
     * 没有「闹钟和提醒」权限时只能用不精确定时，系统保证在一个时间窗内送达。
     * 窗口开在目标时刻之前（宁可早几分钟，不能晚），Android 12 起窗口最短就是 10 分钟。
     */
    private const val INEXACT_WINDOW_MILLIS = 10 * 60_000L

    /** 所有重算串行执行：读到的总是最新数据，调用方也不会因此被阻塞或产生锁嵌套。 */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "class-reminder").apply { isDaemon = true }
    }

    /** 数据或设置变了：异步重算并排好下一次提醒。任意线程、任意锁内都可以调用。 */
    fun requestSync() {
        executor.execute { runCatching { syncNow(MainApplication.appContext) } }
    }

    /** 广播接收器用：重算完成后回调 [onDone]（通常是 goAsync 的 finish）。 */
    fun requestSync(onDone: () -> Unit) {
        executor.execute {
            try {
                runCatching { syncNow(MainApplication.appContext) }
            } finally {
                onDone()
            }
        }
    }

    /** 当前课表（与课表页口径一致：记住的那份，找不到时取最近更新的一份）。 */
    fun currentSchedule(): Schedule? =
        LocalScheduleStore.getScheduleOrNull(Prefs.activeScheduleId)
            ?: LocalScheduleStore.listSchedules().firstOrNull()

    /**
     * 按当前设置和课表算一遍排期（不发通知、不改定时），设置页展示「下一次提醒」用。
     * 会读本机文件，请在后台线程调用。
     */
    fun previewPlan(schedule: Schedule, nowMillis: Long = System.currentTimeMillis()): ClassReminderPlanner.Plan {
        val settings = Prefs.classReminderSettings
        val events = if (settings.includeEvents) PersonalEventStore.list(schedule.id) else emptyList()
        return ClassReminderPlanner.plan(schedule, events, settings, nowMillis, Prefs.classReminderNotifiedUpTo)
    }

    private fun syncNow(context: Context) {
        val settings = Prefs.classReminderSettings
        val schedule = if (settings.enabled) currentSchedule() else null
        if (schedule == null) {
            cancelAlarm(context)
            return
        }
        val exact = ReminderPermissions.exactAlarmAllowed(context)
        val events = if (settings.includeEvents) PersonalEventStore.list(schedule.id) else emptyList()
        val now = System.currentTimeMillis()
        val plan = ClassReminderPlanner.plan(
            schedule = schedule,
            events = events,
            settings = settings,
            nowMillis = now,
            notifiedUpToMillis = Prefs.classReminderNotifiedUpTo,
            earlyToleranceMillis = if (exact) 0L else INEXACT_WINDOW_MILLIS
        )
        if (plan.due.isNotEmpty()) {
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            plan.due.forEach { item ->
                val minutes = ClassReminderPlanner.minutesUntil(item.startAtMillis, now)
                manager?.notify(item.key, NOTIFICATION_ID, buildNotification(context, item, minutes, now, test = false))
            }
            Prefs.classReminderNotifiedUpTo = plan.due.maxOf { it.startAtMillis }
        }
        val trigger = plan.nextTriggerAtMillis
        if (trigger == null) cancelAlarm(context) else setAlarm(context, trigger, now, exact)
    }

    private fun setAlarm(context: Context, triggerAtMillis: Long, nowMillis: Long, exact: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = firePendingIntent(context)
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                return
            } catch (_: SecurityException) {
                // 权限刚被收回：退回不精确定时
            }
        }
        val windowStart = maxOf(nowMillis, triggerAtMillis - INEXACT_WINDOW_MILLIS)
        manager.setWindow(
            AlarmManager.RTC_WAKEUP,
            windowStart,
            maxOf(triggerAtMillis - windowStart, 60_000L),
            operation
        )
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(context))
    }

    private fun firePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_FIRE,
        Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 发一条测试通知，样式与正式提醒完全一致：有下一节课就用它的真实内容，否则用示例课程。
     * 会读本机文件，请在后台线程调用。
     */
    fun sendTest(context: Context) {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val plan = currentSchedule()?.let { previewPlan(it, now) }
        val sample = plan?.due?.firstOrNull() ?: plan?.next?.firstOrNull() ?: ReminderOccurrence(
            isEvent = false,
            title = context.getString(R.string.reminder_test_sample_course),
            position = context.getString(R.string.reminder_test_sample_position),
            teacher = "",
            dateIso = "",
            startTime = "08:00",
            endTime = "09:35",
            startAtMillis = now,
            endAtMillis = now
        )
        val minutes = Prefs.classReminderSettings.leadMinutes
        context.getSystemService(NotificationManager::class.java)
            ?.notify(TEST_TAG, NOTIFICATION_ID, buildNotification(context, sample, minutes, now, test = true))
    }

    /** 创建「上课提醒」通知类别（重复调用无副作用）。用户可在系统里单独调它的铃声或关掉。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.reminder_channel_desc)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(
        context: Context,
        item: ReminderOccurrence,
        minutes: Int,
        nowMillis: Long,
        test: Boolean
    ): Notification {
        val lead = when {
            minutes <= 0 && item.isEvent -> context.getString(R.string.reminder_notify_event_now)
            minutes <= 0 -> context.getString(R.string.reminder_notify_course_now)
            item.isEvent -> context.getString(R.string.reminder_notify_event_in, minutes)
            else -> context.getString(R.string.reminder_notify_course_in, minutes)
        }
        var title = context.getString(R.string.reminder_notify_title, item.title, lead)
        if (test) title = context.getString(R.string.reminder_notify_test_prefix, title)
        val text = listOf("${item.startTime}–${item.endTime}", item.position)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val detail = if (item.teacher.isBlank()) text else "$text\n${item.teacher}"

        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_class_reminder)
            .setColor(context.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
        // 下课后自动收起，通知栏里不留过期提醒
        if (!test && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && item.endAtMillis > nowMillis) {
            builder.setTimeoutAfter(item.endAtMillis - nowMillis)
        }
        return builder.build()
    }
}
