package com.zhusijiao.app.ui.couple

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.domain.CoupleState
import com.zhusijiao.app.ui.common.ToggleView

/**
 * 「我们」页右上角的设置面板。三个显示开关只影响本机，改一下立即生效（[onDisplayChanged]）；
 * 名字颜色与解除绑定影响双方。面板不压暗背景，开关时能看到课表的变化。
 */
class CoupleSettingsSheet(
    context: Context,
    state: CoupleState,
    currentScheduleName: String?,
    syncedAt: Long,
    private val onDisplayChanged: () -> Unit,
    private val onEditProfile: () -> Unit,
    private val onUnbind: () -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet_Clear) {

    init {
        setContentView(R.layout.dialog_couple_settings)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        bindToggle(R.id.nowLineRow, R.id.nowLineToggle, Prefs.coupleShowNowLine) { Prefs.coupleShowNowLine = it }
        bindToggle(R.id.highlightRow, R.id.highlightToggle, Prefs.coupleHighlightFree) { Prefs.coupleHighlightFree = it }
        bindToggle(R.id.freeLabelRow, R.id.freeLabelToggle, Prefs.coupleFreeLabel) { Prefs.coupleFreeLabel = it }

        state.me?.let { findViewById<View>(R.id.profileSwatchMe).showSwatch(CoupleColors.of(it.color).fill, 12f) }
        state.partner?.let { findViewById<View>(R.id.profileSwatchPartner).showSwatch(CoupleColors.of(it.color).fill, 12f) }
        findViewById<View>(R.id.profileRow).setOnClickListener {
            dismiss()
            onEditProfile()
        }

        findViewById<TextView>(R.id.scheduleValue).text = currentScheduleName
            ?.let { context.getString(R.string.couple_setting_schedule_value, it) }
            ?: context.getString(R.string.couple_no_my_schedule)
        findViewById<TextView>(R.id.syncedValue).text = if (syncedAt > 0) {
            context.getString(R.string.couple_setting_synced, relativeTime(syncedAt))
        } else context.getString(R.string.couple_setting_never_synced)

        findViewById<View>(R.id.unbindRow).setOnClickListener {
            dismiss()
            onUnbind()
        }
    }

    private fun bindToggle(rowId: Int, toggleId: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val toggle = findViewById<ToggleView>(toggleId)
        toggle.setChecked(initial, animate = false)
        toggle.onCheckedChange = { checked ->
            save(checked)
            onDisplayChanged()
        }
        findViewById<View>(rowId).setOnClickListener { toggle.toggle() }
    }

    companion object {
        /** 「刚刚」「5 分钟前」「3 小时前」「2 天前」。 */
        fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
            val minutes = (now - millis).coerceAtLeast(0) / 60_000
            return when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "$minutes 分钟前"
                minutes < 60 * 24 -> "${minutes / 60} 小时前"
                else -> "${minutes / 60 / 24} 天前"
            }
        }
    }
}
