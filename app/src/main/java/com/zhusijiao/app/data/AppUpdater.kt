package com.zhusijiao.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.BuildConfig
import com.zhusijiao.app.domain.UpdateChannelOptions
import com.zhusijiao.app.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 服务端 /app/release.json 的内容。 */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String
)

/** 检查更新的三种结果。 */
sealed class UpdateCheck {
    /** 服务端还没有 release.json，视为暂无更新 */
    object NoRelease : UpdateCheck()
    data class Found(val release: AppRelease) : UpdateCheck()
    object Failed : UpdateCheck()
}

/**
 * 应用内更新：读取自建后端的 /app/release.json，
 * 你只需把 APK 放进后端 public/app 目录并更新 release.json（见该目录 README）。
 */
object AppUpdater {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): UpdateCheck = withContext(Dispatchers.IO) {
        val manifest = currentManifestUrl() ?: return@withContext UpdateCheck.NoRelease
        try {
            val request = Request.Builder().url(manifest).build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use UpdateCheck.NoRelease
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use UpdateCheck.Failed
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@use UpdateCheck.Failed
                val release = parse(json, manifest) ?: return@use UpdateCheck.Failed
                UpdateCheck.Found(release)
            }
        } catch (error: Exception) {
            UpdateCheck.Failed
        }
    }

    /**
     * 当前更新通道的 release.json 地址：正式版/测试版挂在数据服务器下，
     * 也可能是用户自建通道的完整 URL。本机模式或所选自定义通道已被删除时返回 null。
     */
    fun currentManifestUrl(): String? {
        val base = AppConfig.apiBase
        if (base.isBlank()) return null
        val id = Prefs.updateChannelId
        UpdateChannelOptions.builtIns(base).firstOrNull { it.id == id }?.let { return it.manifestUrl }
        return Prefs.customUpdateChannels.firstOrNull { it.id == id }?.manifestUrl
    }

    /** 是否有可用更新：以 versionCode 为准。 */
    fun isNewer(release: AppRelease): Boolean = release.versionCode > BuildConfig.VERSION_CODE

    private fun parse(json: JSONObject, manifestUrl: String): AppRelease? {
        val versionCode = json.optInt("versionCode", -1)
        val versionName = json.optString("versionName")
        if (versionCode <= 0 || versionName.isBlank()) return null
        // 下载地址：优先完整 downloadUrl（允许外链云存储/GitHub 等）；
        // 否则按 fileName 拼到清单所在目录的 download/ 下（自建后端目录结构，含多通道）。
        val downloadUrl = when {
            json.optString("downloadUrl").startsWith("http") -> json.optString("downloadUrl")
            json.optString("fileName").isNotBlank() ->
                UpdateChannelOptions.downloadBaseFor(manifestUrl)
                    ?.let { base -> base + Uri.encode(json.optString("fileName")) }
            else -> null
        } ?: return null
        return AppRelease(versionCode, versionName, json.optString("changelog"), downloadUrl)
    }

    /** 用浏览器下载 APK（交给系统下载器/浏览器处理，避免自建下载与安装权限问题）。 */
    fun openDownload(context: Context, release: AppRelease) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
        }.onFailure {
            Ui.toast(context, context.getString(com.zhusijiao.app.R.string.update_open_failed))
        }
    }
}
