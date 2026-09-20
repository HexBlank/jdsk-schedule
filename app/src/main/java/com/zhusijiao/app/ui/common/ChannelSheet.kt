package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_channel)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        // 键盘弹出时把整个面板上抬到输入法上方，避免输入框被遮挡
        Ui.liftAboveIme(findViewById(android.R.id.content))

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
            Ui.toast(context, context.getString(R.string.channel_switched))
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
            if (index != rows.lastIndex) container.addView(divider())
        }
    }

    private fun row(option: UpdateChannelOption, selected: Boolean, deletable: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_menu_row)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48f)
            setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (Prefs.updateChannelId != option.id) {
                    Prefs.updateChannelId = option.id
                    renderList()
                    onChanged()
                    Ui.toast(context, context.getString(R.string.channel_switched))
                }
            }
        }

        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = option.label
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.ink))
            })
            addView(TextView(context).apply {
                text = option.manifestUrl
                textSize = 10.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.sub_8a))
            })
        })

        if (deletable) {
            row.addView(TextView(context).apply {
                text = "✕"
                textSize = 13f
                setPadding(dp(10f), dp(6f), dp(2f), dp(6f))
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
            })
        }

        row.addView(TextView(context).apply {
            text = if (selected) "✓" else ""
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), 0, 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.accent_strong))
        })
        return row
    }

    private fun divider() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
        setBackgroundColor(ContextCompat.getColor(context, R.color.detail_border))
    }

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).roundToInt()
}