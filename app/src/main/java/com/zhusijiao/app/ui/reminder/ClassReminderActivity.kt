package com.zhusijiao.app.ui.reminder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zhusijiao.app.R
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.databinding.ActivityClassReminderBinding
import com.zhusijiao.app.domain.ClassReminderPlanner
import com.zhusijiao.app.domain.ClassReminderSettings
import com.zhusijiao.app.reminder.ClassReminders
import com.zhusijiao.app.reminder.ReminderPermissions
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 上课提醒设置页：开关、提前量、是否提醒日程；三项系统授权的状态检测与引导；测试通知。
 * 授权状态在 [onResume] 重新检测，用户从系统设置返回后立刻看到结果。
 */
class ClassReminderActivity : BaseActivity() {

    private lateinit var binding: ActivityClassReminderBinding
    private var nextJob: Job? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClassReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.padBottomNav(binding.root)

        binding.header.setTitle(getString(R.string.reminder_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { finish() }

        // 先建好「上课提醒」通知类别：系统通知设置里才看得到它，类别开关的检测也才有意义
        ClassReminders.ensureChannel(this)

        val settings = Prefs.classReminderSettings
        binding.enabledToggle.setChecked(settings.enabled, animate = false)
        binding.enabledToggle.onCheckedChange = { checked -> onEnabledChanged(checked) }
        binding.enabledRow.setOnClickListener { binding.enabledToggle.toggle() }

        val options = ClassReminderSettings.LEAD_OPTIONS
        binding.leadChoice.configure(
            items = options.map { getString(R.string.reminder_lead_option, it) },
            initialIndex = options.indexOf(settings.leadMinutes).coerceAtLeast(0),
            contentDescriptionPrefix = getString(R.string.reminder_lead_prefix)
        ) { index ->
            Prefs.classReminderSettings = Prefs.classReminderSettings.copy(leadMinutes = options[index])
            render()
        }

        binding.eventsToggle.setChecked(settings.includeEvents, animate = false)
        binding.eventsToggle.onCheckedChange = { checked ->
            Prefs.classReminderSettings = Prefs.classReminderSettings.copy(includeEvents = checked)
            render()
        }
        binding.eventsRow.setOnClickListener { binding.eventsToggle.toggle() }

        binding.notificationAction.setOnClickListener { requestNotifications() }
        binding.exactAction.setOnClickListener { openSettings(ReminderPermissions.exactAlarmSettingsIntent(this)) }
        binding.backgroundAction.setOnClickListener { requestBackground() }
        binding.appDetailsLink.setOnClickListener { openSettings(ReminderPermissions.appDetailsIntent(this)) }
        binding.testButton.setOnClickListener { sendTest() }
    }

    override fun onResume() {
        super.onResume()
        render()
        // 用户可能刚在系统里改了授权（如开了「闹钟和提醒」），按最新状态重排一次
        ClassReminders.requestSync()
    }

    private fun onEnabledChanged(checked: Boolean) {
        Prefs.classReminderSettings = Prefs.classReminderSettings.copy(enabled = checked)
        render()
        if (!checked) return
        // 刚打开时只引导最关键的一项缺失授权，其余在下方权限检查里一目了然
        when {
            !ReminderPermissions.notificationsAllowed(this) -> requestNotifications()
            !ReminderPermissions.exactAlarmAllowed(this) -> Ui.confirm(
                this,
                getString(R.string.reminder_exact_dialog_title),
                getString(R.string.reminder_exact_dialog_msg),
                confirmText = getString(R.string.reminder_dialog_go),
                cancelText = getString(R.string.reminder_dialog_later)
            ) { openSettings(ReminderPermissions.exactAlarmSettingsIntent(this)) }
        }
    }

    private fun render() {
        val settings = Prefs.classReminderSettings
        val dimmed = if (settings.enabled) 1f else 0.45f
        binding.leadSection.alpha = dimmed
        binding.eventsRow.alpha = dimmed

        val notificationsOk = ReminderPermissions.notificationsAllowed(this)
        renderStatus(binding.notificationStatus, binding.notificationAction, notificationsOk, required = true)
        binding.notificationAction.setText(
            if (canAskNotificationInApp()) R.string.reminder_action_allow else R.string.reminder_action_enable
        )

        binding.exactGroup.visibility = if (ReminderPermissions.exactAlarmApplies) View.VISIBLE else View.GONE
        renderStatus(binding.exactStatus, binding.exactAction, ReminderPermissions.exactAlarmAllowed(this), required = false)
        renderStatus(
            binding.backgroundStatus,
            binding.backgroundAction,
            ReminderPermissions.backgroundUnrestricted(this),
            required = false
        )
        renderNext(settings)
    }

    private fun renderStatus(status: TextView, action: View, allowed: Boolean, required: Boolean) {
        val (text, background, color) = when {
            allowed -> Triple(R.string.reminder_status_allowed, R.drawable.bg_status_pill, R.color.status_pill_text)
            required -> Triple(R.string.reminder_status_denied, R.drawable.bg_tag_danger, R.color.danger)
            else -> Triple(R.string.reminder_status_suggest, R.drawable.bg_tag_warn, R.color.warn)
        }
        status.setText(text)
        status.setBackgroundResource(background)
        status.setTextColor(ContextCompat.getColor(this, color))
        action.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    /** 「下一次提醒」说明：读本机课表文件，放后台线程算。 */
    private fun renderNext(settings: ClassReminderSettings) {
        // 连续改设置时只保留最后一次计算，避免旧结果晚到覆盖新结果
        nextJob?.cancel()
        if (!settings.enabled) {
            binding.nextText.setText(R.string.reminder_next_off)
            return
        }
        binding.nextText.setText(R.string.reminder_next_loading)
        nextJob = lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val (schedule, plan) = withContext(Dispatchers.IO) {
                val schedule = ClassReminders.currentSchedule()
                schedule to schedule?.let { ClassReminders.previewPlan(it, now) }
            }
            binding.nextText.text = when {
                schedule == null || plan == null -> getString(R.string.reminder_next_no_schedule)
                plan.blocker == ClassReminderPlanner.Blocker.NO_SEMESTER_START ->
                    getString(R.string.reminder_next_no_semester, schedule.name)
                plan.blocker == ClassReminderPlanner.Blocker.SEMESTER_OVER ->
                    getString(R.string.reminder_next_semester_over, schedule.name)
                plan.nextTriggerAtMillis == null -> getString(R.string.reminder_next_nothing, schedule.name)
                else -> {
                    val first = plan.next.first().title
                    val what = if (plan.next.size > 1) {
                        getString(R.string.reminder_next_more, first, plan.next.size)
                    } else {
                        first
                    }
                    getString(
                        R.string.reminder_next,
                        schedule.name,
                        ClassReminderPlanner.formatMoment(plan.nextTriggerAtMillis, now),
                        what
                    )
                }
            }
        }
    }

    /** Android 13 起能在应用内弹系统授权框；被拒过且系统不再弹框时只能去设置里开。 */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private fun canAskNotificationInApp(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return !Prefs.notificationPermissionAsked ||
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotifications() {
        if (canAskNotificationInApp()) {
            Prefs.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        Ui.confirm(
            this,
            getString(R.string.reminder_notification_dialog_title),
            getString(R.string.reminder_notification_dialog_msg),
            confirmText = getString(R.string.reminder_dialog_go),
            cancelText = getString(R.string.reminder_dialog_later)
        ) { openSettings(ReminderPermissions.notificationSettingsIntent(this)) }
    }

    /** 优先用系统授权弹窗；个别系统没有这个弹窗时退到电池优化列表，再不行进应用信息。 */
    private fun requestBackground() {
        val candidates = listOf(
            ReminderPermissions.backgroundRequestIntent(this),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in candidates) {
            if (tryStart(intent)) return
        }
        openSettings(ReminderPermissions.appDetailsIntent(this))
    }

    /** 打开系统设置页；该页面不存在时退到应用信息页，仍不行则提示手动前往。 */
    private fun openSettings(intent: Intent) {
        if (tryStart(intent)) return
        if (tryStart(ReminderPermissions.appDetailsIntent(this))) return
        Ui.toast(this, getString(R.string.reminder_open_settings_failed))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun sendTest() {
        if (!ReminderPermissions.notificationsAllowed(this)) {
            requestNotifications()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ClassReminders.sendTest(applicationContext) }
            Ui.toast(this@ClassReminderActivity, getString(R.string.reminder_test_sent))
        }
    }
}
