package com.zhusijiao.app

import android.app.Application
import android.content.Context
import com.zhusijiao.app.data.Prefs

/**
 * 全局 Application。仅持有应用级 Context，供 Prefs / ApiClient 等单例使用。
 * 保持轻量：不引入 DI 框架，避免增大包体。
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        Prefs.removeLegacyDemoData()
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
