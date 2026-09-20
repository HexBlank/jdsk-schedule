package com.zhusijiao.app.ui.common

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 统一处理沉浸式边到边：内容延伸到状态栏/导航栏之下，由各页面自行避让安全区
 * （页头避让状态栏、底部导航避让手势条），统一按系统 insets 计算。
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}

/** 页签每次重新可见时刷新：Activity onResume 与切换到该页签时各调一次。 */
interface Refreshable {
    fun refresh()
}
