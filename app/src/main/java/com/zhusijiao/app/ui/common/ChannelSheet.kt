package com.zhusijiao.app.ui.common

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.R
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.domain.UpdateChannelOption
import com.zhusijiao.app.domain.UpdateChannelOptions
import com.zhusijiao.app.util.Ui
import kotlin.math.roundToInt

/**
 * 更新通道选择底部面板：内置「正式版 / 测试版」，支持添加与删除自定义通道。
 * 通道只影响「检查更新」读取的 release.json 地址，不影响课表数据。
 */
class ChannelSheet(
    context: Context,
    private val onChanged: () -> Unit
) : SheetDialog(context) {

    init {
        setContentView(R.layout.dialog_channel)
        setCanceledOnTouchOutside(true)

        renderList()

        findViewById<View>(R.id.channelAddRow).setOnClickListener { showAddForm(true) }
        findViewById<TextView>(R.id.channelFormCancel).setOnClickListener { showAddForm(false) }
        findViewById<TextView>(R.id.channelFormSave).setOnClickListener {
            val nameInput = findViewById<EditText>(R.id.channelNameInput)
            val urlInput = findViewById<EditText>(R.id.channelUrlInput)
            val error = findViewById<TextView>(R.id.channelFormError)
            val option = Prefs.addCustomUpdateChannel(nameInput.text.toString(), urlInput.text.toString())
            if (option == null) {
                error.visibility = View.VISIBLE
                error.text = context.getString(R.string.channel_form_invalid)
                return@setOnClickListener
            }
            error.visibility = View.GONE
            Prefs.updateChannelId = option.id
            nameInput.setText("")
            urlInput.setText("")
            showAddForm(false)
            renderList()
            onChanged()
            Ui.toastSuccess(context, context.getString(R.string.channel_switched))
        }
    }

    private fun showAddForm(show: Boolean) {
        findViewById<View>(R.id.channelAddForm).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.channelAddRow).visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun renderList() {
        val container = findViewById<LinearLayout>(R.id.channelList)
        container.removeAllViews()
        val selectedId = Prefs.updateChannelId
        val rows = UpdateChannelOptions.builtIns(AppConfig.apiBase) + Prefs.customUpdateChannels
        rows.forEachIndexed { index, option ->
            val builtIn = option.id == UpdateChannelOptions.STABLE_ID ||
                option.id == UpdateChannelOptions.BETA_ID
            container.addView(row(option, selectedId == option.id, deletable = !builtIn))
            if (index != rows.lastIndex) MenuRows.divider(container)
        }
    }

    private fun row(option: UpdateChannelOption, selected: Boolean, deletable: Boolean): View {
        val row = MenuRows.row(findViewById(R.id.channelList), option.label, R.drawable.ic_menu_channel, selected = selected) {
            if (Prefs.updateChannelId != option.id) {
                Prefs.updateChannelId = option.id
                renderList()
                onChanged()
                Ui.toastSuccess(context, context.getString(R.string.channel_switched))
            }
        }

        if (deletable) {
            row.addView(TextView(context).apply {
                text = "✕"
                textSize = 13f
                gravity = Gravity.CENTER
                minimumWidth = dp(48f)
                minimumHeight = dp(48f)
                setTextColor(ContextCompat.getColor(context, R.color.danger))
                contentDescription = context.getString(R.string.channel_delete_title)
                setOnClickListener {
                    Ui.confirm(
                        context,
                        context.getString(R.string.channel_delete_title),
                        context.getString(R.string.channel_delete_message, option.label),
                        confirmText = context.getString(R.string.channel_delete_confirm),
                        confirmColor = ContextCompat.getColor(context, R.color.danger_confirm_alt)
                    ) {
                        Prefs.removeCustomUpdateChannel(option.id)
                        if (Prefs.updateChannelId == option.id) {
                            Prefs.updateChannelId = UpdateChannelOptions.STABLE_ID
                        }
                        renderList()
                        onChanged()
                    }
                }
            }, row.childCount - 1)
        }

        return row
    }

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).roundToInt()
}