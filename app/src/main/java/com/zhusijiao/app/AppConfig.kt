package com.zhusijiao.app

import com.zhusijiao.app.data.Prefs
import com.zhusijiao.app.domain.ServerAddress

/**
 * 全局配置：唯一真源。
 *
 * ★ 后端域名统一在这里改：只改 [API_BASE_URL] 一处即可全局生效
 *   （ApiClient、设置页「数据服务」展示、导出助手地址都读它）。
 *   - 留空 ""：课表只保存在本机，首次启动为空，不会注入任何示例数据。
 *   - 上线：填已备案的 HTTPS 域名，例如 "https://api.example.com"（结尾斜杠可有可无）。
 */
object AppConfig {

    const val APP_NAME = "几点上课"

    /** 后端 API 基础域名（全局唯一变量）。 */
    val API_BASE_URL: String = BuildConfig.API_BASE_URL

    /**
     * 生效的数据服务器：设置页里用户覆盖的地址优先，其次构建时注入的默认地址。
     * 两者都为空 = 本机模式（课表只保存在本机）。覆盖地址随安装保留，可随时恢复默认。
     */
    val apiBase: String
        get() {
            val override = Prefs.serverBaseUrlOverride
            if (override.isNotBlank()) return override.trimEnd('/')
            return API_BASE_URL.trimEnd('/')
        }

    val isLocalMode: Boolean get() = apiBase.isBlank()

    /** 设置页展示的主机名（去掉协议头）。 */
    val apiHostLabel: String
        get() = if (isLocalMode) "仅本机保存" else ServerAddress.labelOf(apiBase)

    /**
     * 教务系统 / WebVPN 入口。WebView 打开此地址，用户登录后注入导出脚本。
     * 入口可能在 webvpn 与 jw 之间变化，集中在此配置；解析脚本按当前页动态推导 /eams/ 前缀，不写死代理段。
     */
    const val EAMS_PORTAL_URL = "https://webvpn.hstc.edu.cn/"

    /** 仅用于连接本地 ENABLE_DEV_AUTH=true 的服务；上线必须留空。 */
    val DEV_AUTH_OPENID: String = BuildConfig.DEV_AUTH_OPENID

    /** 浏览器导出助手地址（部署后端后可用）。 */
    val helperScriptUrl: String get() = if (isLocalMode) "" else "$apiBase/tools/schedule-export.user.js"
}
