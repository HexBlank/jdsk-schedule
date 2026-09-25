package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.R
import com.zhusijiao.app.data.ApiClient
import com.zhusijiao.app.data.CoupleStore
import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.data.ScheduleSyncStore
import com.zhusijiao.app.data.SyncLog
import com.zhusijiao.app.domain.ServerAddress
import com.zhusijiao.app.util.Ui

/**
 * 数据服务器设置底部面板：查看/更换 API 地址、恢复内置默认。
 * 更换会退出当前登录并清除同步绑定（本机课表保留），均经确认弹窗二次确认。
 */
class ServerSheet(
    context: Context,
    private val onSaved: () -> Unit
) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_server)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        // 键盘弹出时把整个面板上抬到输入法上方，避免输入框被遮挡
        Ui.liftAboveIme(findViewById(android.R.id.content))

        val input = findViewById<EditText>(R.id.serverInput)
        val error = findViewById<TextView>(R.id.serverError)
        input.setText(
            if (Prefs.serverBaseUrlOverride.isBlank()) AppConfig.API_BASE_URL else Prefs.serverBaseUrlOverride
        )
        findViewById<TextView>(R.id.serverDefault).text = context.getString(
            R.string.server_default_label,
            ServerAddress.labelOf(AppConfig.API_BASE_URL)
                .ifEmpty { context.getString(R.string.settings_server_local_default) }
        )

        fun applyChange(newOverride: String, messageRes: Int, confirmRes: Int) {
            Ui.confirm(
                context,
                context.getString(R.string.server_switch_title),
                context.getString(
                    messageRes,
                    ServerAddress.labelOf(newOverride.ifBlank { AppConfig.API_BASE_URL })
                ),
                confirmText = context.getString(confirmRes)
            ) {
                Prefs.serverBaseUrlOverride = newOverride
                ApiClient.resetSession()
                ScheduleSyncStore.clear()
                CoupleStore.clear()
                SyncLog.log("切换数据服务，清空同步映射", newOverride.ifBlank { "恢复默认" })
                Ui.toast(context, context.getString(R.string.server_switched))
                onSaved()
                dismiss()
            }
        }

        findViewById<TextView>(R.id.serverSave).setOnClickListener {
            val normalized = ServerAddress.normalize(input.text.toString())
            if (normalized == null) {
                error.visibility = View.VISIBLE
                error.text = context.getString(R.string.server_invalid_url)
                return@setOnClickListener
            }
            error.visibility = View.GONE
            if (normalized == AppConfig.apiBase) {
                dismiss()
                return@setOnClickListener
            }
            // 与内置默认一致时清空覆盖值，等价于「恢复默认」，便于后续默认地址变更时自动跟随
            val newOverride = if (normalized == AppConfig.API_BASE_URL.trimEnd('/')) "" else normalized
            applyChange(newOverride, R.string.server_switch_message, R.string.server_switch_confirm)
        }

        findViewById<TextView>(R.id.serverCancel).setOnClickListener { dismiss() }

        findViewById<TextView>(R.id.serverReset).setOnClickListener {
            if (Prefs.serverBaseUrlOverride.isBlank()) {
                dismiss()
            } else {
                applyChange("", R.string.server_reset_message, R.string.server_reset_confirm)
            }
        }
    }
}