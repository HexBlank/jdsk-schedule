package com.zhusijiao.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.zhusijiao.app.R
import com.zhusijiao.app.data.SyncLog

/** 把同步诊断日志复制到剪贴板，方便用户直接粘贴给开发者。 */
object SyncLogClipboard {

    fun copy(context: Context) {
        val text = runCatching { SyncLog.export() }.getOrElse { "导出日志失败：${it.javaClass.name}: ${it.message}" }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.sync_copy_log), text))
        Ui.toast(context, context.getString(R.string.sync_log_copied))
    }
}
