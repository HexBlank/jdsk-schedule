package com.zhusijiao.app

import android.app.Application
import android.content.Context
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.reminder.ClassReminders

/**
 * 全局 Application。仅持有应用级 Context，供 Prefs / ApiClient 等单例使用。
 * 保持轻量：不引入 DI 框架，避免增大包体。
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        Prefs.removeLegacyDemoData()
        // 每次进程启动都重排一次上课提醒：自愈被系统清掉的提醒，也补发错过但还没开始的课
        ClassReminders.requestSync()
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
