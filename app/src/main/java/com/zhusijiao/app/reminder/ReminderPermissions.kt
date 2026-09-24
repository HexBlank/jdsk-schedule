package com.zhusijiao.app.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 上课提醒依赖的三项系统授权：检测是否已允许，以及跳到对应系统页面的 Intent。
 *
 * - 通知（必需）：Android 13 起要运行时授权；更早的系统默认允许，但用户可以手动关掉。
 * - 闹钟和提醒（Android 12 起，决定准不准时）：系统设置里就叫「闹钟和提醒」，
 *   开关名「允许设置闹钟和提醒」。本应用只用它准时发通知，不会设置响铃闹钟。
 * - 后台运行（建议）：不在电池优化名单里时，系统更不容易把定时提醒推迟或拦掉。
 */
object ReminderPermissions {

    /** 通知是否能发出去：整个应用的通知开着，且「上课提醒」这个类别没被单独关掉。 */
    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return !channelBlocked(context)
    }

    /** 应用通知开着，但用户在系统里单独关掉了「上课提醒」类别。 */
    fun channelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(ClassReminders.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** 这台手机的系统里有没有「闹钟和提醒」这项授权（Android 12 起才有）。 */
    val exactAlarmApplies: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 能否准时定时；Android 12 以下不需要授权，恒为 true。 */
    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** 是否已允许在后台运行（不受电池优化限制）。 */
    fun backgroundUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** 本应用的通知设置页；「上课提醒」类别被单独关掉时直接进这个类别的页面。 */
    fun notificationSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            return if (manager?.areNotificationsEnabled() == true && channelBlocked(context)) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ClassReminders.CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        return appDetailsIntent(context)
    }

    /** 直接打开本应用的「闹钟和提醒」开关页（仅 Android 12 起有效）。 */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
        } else {
            appDetailsIntent(context)
        }

    /** 系统弹窗询问是否允许本应用在后台运行（不受电池优化限制）。 */
    fun backgroundRequestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    /** 本应用的「应用信息」页：各品牌手机的自启动、耗电设置一般都能从这里找到。 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}
