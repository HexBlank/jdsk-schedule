package com.zhusijiao.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 三种桌面小部件共用的接收器逻辑：添加、系统定期更新、拉伸改尺寸、删除，都只做一件事——
 * 让 [ScheduleWidgets] 把所有小部件重画一遍（它也会顺便排好或撤掉下一次定时刷新）。
 * 读课表要访问文件，放到后台线程做，用 goAsync 撑住广播的生命周期。
 */
abstract class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh()

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) = refresh()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = refresh()

    private fun refresh() {
        val pending: PendingResult? = goAsync()
        ScheduleWidgets.requestUpdate { pending?.finish() }
    }
}

/** 小号：下一节课。 */
class NextClassWidget : ScheduleWidgetProvider()

/** 中号：今日课程。 */
class TodayWidget : ScheduleWidgetProvider()

/** 大号：本周课表。 */
class WeekWidget : ScheduleWidgetProvider()

/** 小部件内容该变的时刻到了（某节课开始或结束、过了零点）。只接收本应用自己的定时（不导出）。 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending: PendingResult? = goAsync()
        ScheduleWidgets.requestUpdate { pending?.finish() }
    }
}
