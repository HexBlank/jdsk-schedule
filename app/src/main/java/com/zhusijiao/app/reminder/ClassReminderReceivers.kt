package com.zhusijiao.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 提醒时刻到了：发出到点的上课提醒，并排好下一次。只接收本应用自己的定时（不导出）。 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ClassReminders.requestSync { pending.finish() }
    }
}

/**
 * 系统事件后重排提醒：开机（定时会被清空）、App 升级、改系统时间或时区（按钟点算的提醒会错位）、
 * 用户刚授予「闹钟和提醒」（可以从不精确定时换成准时定时）。
 */
class ClassReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        ClassReminders.requestSync { pending.finish() }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
