package com.zhusijiao.app

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.R
import com.zhusijiao.app.databinding.ActivityMainBinding
import com.zhusijiao.app.data.AppRelease
import com.zhusijiao.app.data.AppUpdater
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.data.UpdateCheck
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.launch
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.ui.common.BottomNavView
import com.zhusijiao.app.ui.common.Refreshable
import com.zhusijiao.app.ui.library.LibraryFragment
import com.zhusijiao.app.ui.schedule.ScheduleFragment
import com.zhusijiao.app.ui.settings.SettingsFragment

/**
 * 主界面：承载底部导航的三个页签（课表 / 课表库 / 设置）。
 * 页签用 Fragment 显示/隐藏切换而非重建，各自保留滚动位置与已加载数据。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scheduleFragment: ScheduleFragment
    private lateinit var libraryFragment: LibraryFragment
    private lateinit var settingsFragment: SettingsFragment
    private var active: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fm = supportFragmentManager
        scheduleFragment = (fm.findFragmentByTag(TAG_SCHEDULE) as? ScheduleFragment) ?: ScheduleFragment()
        libraryFragment = (fm.findFragmentByTag(TAG_LIBRARY) as? LibraryFragment) ?: LibraryFragment()
        settingsFragment = (fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment) ?: SettingsFragment()

        if (savedInstanceState == null) {
            val initialTab = if (Prefs.activeScheduleId.isBlank()) {
                BottomNavView.Tab.LIBRARY
            } else {
                BottomNavView.Tab.SCHEDULE
            }
            fm.beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .add(R.id.fragmentContainer, libraryFragment, TAG_LIBRARY)
                .add(R.id.fragmentContainer, scheduleFragment, TAG_SCHEDULE)
                .hide(settingsFragment)
                .apply {
                    if (initialTab == BottomNavView.Tab.LIBRARY) hide(scheduleFragment)
                    else hide(libraryFragment)
                }
                .commit()
            active = if (initialTab == BottomNavView.Tab.LIBRARY) libraryFragment else scheduleFragment
            binding.bottomNav.setCurrent(initialTab)
        } else {
            active = listOf(scheduleFragment, libraryFragment, settingsFragment)
                .firstOrNull { !it.isHidden } ?: scheduleFragment
            binding.bottomNav.setCurrent(
                when (active) {
                    libraryFragment -> BottomNavView.Tab.LIBRARY
                    settingsFragment -> BottomNavView.Tab.SETTINGS
                    else -> BottomNavView.Tab.SCHEDULE
                }
            )
        }

        binding.bottomNav.onTabSelected = { showTab(it) }
        maybeCheckUpdateOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        (active as? Refreshable)?.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false)) {
            openScheduleTab()
        }
    }

    /** 供其他页面（如课表库）切到课表页并刷新。 */
    fun openScheduleTab() {
        binding.bottomNav.setCurrent(BottomNavView.Tab.SCHEDULE)
        active = null
        showTab(BottomNavView.Tab.SCHEDULE)
    }

    /**
     * 设置页「课表外观」入口：先切到课表页再弹外观面板。
     * 外观要对着真实课表调才看得出效果，所以不在设置页就地弹。
     */
    fun openTimetableAppearance() {
        openScheduleTab()
        scheduleFragment.requestAppearanceSheet()
    }

    /** 供删除全部数据等明确流程回到课表库；空课表页本身不会强制跳转。 */
    fun openLibraryTab() {
        binding.bottomNav.setCurrent(BottomNavView.Tab.LIBRARY)
        active = null
        showTab(BottomNavView.Tab.LIBRARY)
    }

    /** 启动时自动检查更新（可在设置中关闭）；被「下次再说」跳过的版本不再重复提醒。 */
    private fun maybeCheckUpdateOnLaunch() {
        if (!Prefs.autoUpdateCheck || AppConfig.isLocalMode) return
        lifecycleScope.launch {
            val result = AppUpdater.fetch()
            if (result is UpdateCheck.Found && AppUpdater.isNewer(result.release) &&
                Prefs.dismissedUpdateCode < result.release.versionCode
            ) {
                showUpdateDialog(result.release, recordDismiss = true)
            }
        }
    }

    /** 更新弹窗：应用统一的 AppDialog 样式；确认后用浏览器下载 APK。 */
    fun showUpdateDialog(release: AppRelease, recordDismiss: Boolean) {
        Ui.confirm(
            this,
            getString(R.string.update_title, release.versionName),
            release.changelog.ifBlank { getString(R.string.update_no_changelog) },
            confirmText = getString(R.string.update_action),
            cancelText = getString(R.string.update_later),
            onCancel = { if (recordDismiss) Prefs.dismissedUpdateCode = release.versionCode }
        ) {
            AppUpdater.openDownload(this, release)
        }
    }

    private fun showTab(tab: BottomNavView.Tab) {
        val target: Fragment = when (tab) {
            BottomNavView.Tab.SCHEDULE -> scheduleFragment
            BottomNavView.Tab.LIBRARY -> libraryFragment
            BottomNavView.Tab.SETTINGS -> settingsFragment
        }
        if (target === active) return
        val tx = supportFragmentManager.beginTransaction()
        listOf(scheduleFragment, libraryFragment, settingsFragment).forEach {
            if (it !== target) tx.hide(it)
        }
        tx.show(target)
        tx.commit()
        active = target
        (target as? Refreshable)?.refresh()
    }

    companion object {
        const val EXTRA_OPEN_SCHEDULE = "open_schedule"
        private const val TAG_SCHEDULE = "schedule"
        private const val TAG_LIBRARY = "library"
        private const val TAG_SETTINGS = "settings"
    }
}
