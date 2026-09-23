package com.zhusijiao.app.data

import android.os.Build
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.BuildConfig
import com.zhusijiao.app.MainApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步诊断日志：只记失败与关键状态变化，供用户一键复制给开发者排查。
 *
 * 绝不记录登录凭据：请求头里的 JWT、设备 id（长期凭证）都不进日志；
 * 请求体（整份课表）也不记，响应体截断保存。
 */
object SyncLog {

    private const val MAX_BYTES = 48 * 1024
    private const val KEEP_BYTES = 32 * 1024
    private const val MAX_BODY = 600

    private val lock = Any()
    @Volatile private var lastLine: String? = null

    /** 追加一条。与上一条内容完全相同时跳过，避免离线时每次进页面刷屏。 */
    fun log(event: String, detail: String = "") {
        val body = if (detail.isBlank()) event else "$event | $detail"
        synchronized(lock) {
            if (body == lastLine) return
            lastLine = body
            runCatching {
                val target = file()
                target.parentFile?.mkdirs()
                target.appendText("${timestamp()} $body\n", Charsets.UTF_8)
                if (target.length() > MAX_BYTES) {
                    val text = target.readText(Charsets.UTF_8)
                    val tail = text.substring(text.length - KEEP_BYTES)
                    target.writeText(tail.substringAfter('\n'), Charsets.UTF_8)
                }
            }
        }
    }

    /** 截断响应体，并把换行压成空格，保证一条日志一行。 */
    fun clip(text: String?): String {
        val flat = text.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (flat.length > MAX_BODY) flat.take(MAX_BODY) + "…(截断)" else flat
    }

    /** 复制给开发者的完整文本：环境信息 + 同步映射快照 + 最近日志。 */
    fun export(): String = buildString {
        appendLine("【几点上课 同步诊断日志】")
        appendLine("导出时间：${timestamp()}")
        appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})，${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("数据服务：${if (AppConfig.isLocalMode) "未配置（本机模式）" else AppConfig.apiBase}")
        appendLine("最近同步失败：${ApiClient.lastSyncError ?: "无"}")
        appendLine()
        appendLine("— 同步映射 —")
        val records = runCatching { ScheduleSyncStore.all() }.getOrDefault(emptyList())
        if (records.isEmpty()) appendLine("（无）")
        for (r in records) {
            val local = runCatching { LocalScheduleStore.getScheduleOrNull(r.localId) }.getOrNull()
            appendLine(
                "local=${r.localId} remote=${r.remoteId} rev=${r.remoteRevision} dirty=${r.dirty} " +
                    "pending=${r.pendingAction ?: "-"} role=${local?.role ?: "(本机无副本)"} " +
                    "name=${local?.name ?: "-"} error=${r.lastError ?: "-"}"
            )
        }
        appendLine()
        appendLine("— 最近日志 —")
        val log = synchronized(lock) { runCatching { file().takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull() }
        append(if (log.isNullOrBlank()) "（无）\n" else log)
    }

    fun clear() = synchronized(lock) {
        lastLine = null
        file().delete()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun file() = File(MainApplication.appContext.filesDir, "sync/sync_log.txt")
}
