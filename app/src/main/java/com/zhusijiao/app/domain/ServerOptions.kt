package com.zhusijiao.app.domain

import java.net.URI

/**
 * 服务器地址与更新通道的纯逻辑（不依赖 Android 环境，可直接单测）。
 *
 * 用户可在设置页更换数据服务器（默认地址由构建注入，仅供内部使用）；
 * 更新通道决定「检查更新」读取的 release.json 地址，支持内置通道 + 用户自建通道。
 */
object ServerAddress {

    private const val MAX_LENGTH = 200
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /**
     * 规范化用户输入的服务器地址；非法返回 null。
     * 规则：scheme://host[:port]，不允许路径/查询/片段/用户信息；
     * 未写 scheme 自动补 https://；公网地址必须 https（仅本机联调地址允许 http）。
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // 未写 scheme：本机联调地址默认 http，其余默认 https
            runCatching { URI("https://$trimmed").host }.getOrNull()
                ?.lowercase()?.trimEnd('.') in LOCAL_HOSTS -> "http://$trimmed"
            else -> "https://$trimmed"
        }
        return runCatching {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            if (host.isEmpty()) return null
            if (uri.userInfo != null) return null
            if (!uri.rawQuery.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return null
            if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
            val port = uri.port
            if (port !in -1..65535) return null
            if (scheme == "http" && host !in LOCAL_HOSTS) return null
            val portSuffix = if (port == -1) "" else ":$port"
            "$scheme://$host$portSuffix"
        }.getOrNull()
    }

    /** 展示用主机名（去协议头）。 */
    fun labelOf(base: String): String = base.trim().replace(Regex("^https?://"), "")
}

/** 一个更新通道：内置（正式版/测试版）或用户自建。 */
data class UpdateChannelOption(
    val id: String,
    val label: String,
    val manifestUrl: String
)

object UpdateChannelOptions {

    const val STABLE_ID = "stable"
    const val BETA_ID = "beta"
    const val MAX_CUSTOM = 5

    /** 内置通道，挂在当前数据服务器下。 */
    fun builtIns(apiBase: String): List<UpdateChannelOption> = listOf(
        UpdateChannelOption(STABLE_ID, "正式版", "$apiBase/app/release.json"),
        UpdateChannelOption(BETA_ID, "测试版", "$apiBase/app/ch/beta/release.json")
    )

    /**
     * 由 release.json 地址推导 APK 下载目录（自建后端目录结构）：
     * "…/release.json" → "…/download/"。
     * 非 release.json 结尾的自定义清单必须自带 downloadUrl 字段，此时返回 null。
     */
    fun downloadBaseFor(manifestUrl: String): String? {
        val trimmed = manifestUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/release.json")) {
            trimmed.removeSuffix("release.json") + "download/"
        } else {
            null
        }
    }

    // 自定义通道的持久化格式：每行一条，字段用单元分隔符 \u0001 连接（id、label、url）。
    private const val FIELD_SEP = '\u0001'

    fun parseCustom(text: String): List<UpdateChannelOption> = text.split('\n').mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val parts = line.split(FIELD_SEP)
        if (parts.size < 3) return@mapNotNull null
        val id = parts[0].trim()
        val label = parts[1].trim().take(20)
        val url = parts[2].trim()
        if (id.isBlank() || label.isBlank() || url.isBlank()) null else UpdateChannelOption(id, label, url)
    }

    fun toText(list: List<UpdateChannelOption>): String = list
        .takeLast(MAX_CUSTOM)
        .joinToString("\n") { "${it.id}$FIELD_SEP${it.label}$FIELD_SEP${it.manifestUrl}" }
}