package com.zhusijiao.app.ui.eams

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.zhusijiao.app.AppConfig
import com.zhusijiao.app.R
import com.zhusijiao.app.databinding.ActivityEamsWebBinding
import com.zhusijiao.app.ui.common.BaseActivity
import com.zhusijiao.app.util.Rpx
import com.zhusijiao.app.util.Ui

/**
 * 教务系统 WebView 导入页。
 * 打开教务/WebVPN 入口 → 用户登录 → 在学生课表页注入导出脚本 → 脚本本地解析后经 JS 桥回传 JSON。
 * 全程不接触、不上传账号、密码或 Cookie（Cookie 仅存在于 WebView 内）。
 */
class EamsWebActivity : BaseActivity() {

    private lateinit var binding: ActivityEamsWebBinding
    private var delivered = false
    // 默认手机模式：多数教务系统对手机 UA 也能正常出课表，且手机版更贴合竖屏；
    // 排版异常时再点右上角切到电脑模式。
    private var desktopMode = false
    private var defaultMobileUA = ""
    private lateinit var modeToggle: TextView

    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        if (!delivered && !isFinishing && !isDestroyed) {
            Ui.alert(this, getString(R.string.eams_stuck_title), getString(R.string.eams_stuck_content))
        }
    }

    private val exportScript: String by lazy {
        assets.open("eams-export.js").bufferedReader().use { it.readText() }
    }

    private val allowedSuffix: String by lazy {
        val host = Uri.parse(AppConfig.EAMS_PORTAL_URL).host ?: ""
        val parts = host.split(".")
        if (parts.size >= 3) parts.takeLast(3).joinToString(".") else host
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEamsWebBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.setTitle(getString(R.string.eams_title))
        binding.header.setBackVisible(true)
        binding.header.onBackClick = { handleBack() }

        modeToggle = headerAction(getString(R.string.eams_mode_desktop)).apply {
            setOnClickListener { toggleMode() }
        }
        binding.header.addAction(modeToggle)
        binding.header.addAction(headerAction(getString(R.string.eams_inject)).apply {
            setOnClickListener { manualRun() }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        setupWebView()
        binding.webView.loadUrl(AppConfig.EAMS_PORTAL_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
        settings.userAgentString = settings.userAgentString + " ZhuSiJiaoApp/1.0"
        defaultMobileUA = settings.userAgentString
        applyMode()

        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, false)
        }

        // JS 桥只在这里注入一次。此前放在 onPageStarted 里反复 remove/add，会和页面 JS 上下文的
        // 创建时机赛跑：WebVPN 多次重定向后 window.ZSJBridge 常常不存在，脚本明明解析成功
        // （按钮显示「已读取 N 个时段」）却交不回 App，用户看到的就是「读完没反应」。
        // 页面可信性改在 Bridge 每个方法里按当前地址校验，非教务页面的调用一律丢弃。
        binding.webView.addJavascriptInterface(Bridge(), "ZSJBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (desktopMode) view?.let { injectDesktopViewportFix(it) }
                if (url != null && hostAllowed(url)) view?.evaluateJavascript(exportScript, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                Ui.alert(
                    this@EamsWebActivity,
                    getString(R.string.eams_ssl_title),
                    getString(R.string.eams_ssl_blocked_message)
                )
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun hostAllowed(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host == allowedSuffix || host.endsWith(".$allowedSuffix")
    }

    /**
     * 页头「导入课表」：脚本没注入时（页面中途跳转、注入时页面还没就绪）先补注入再跑。
     * 不再无条件弹「正在读取」——那会让什么都没发生的情况看起来像正在工作。
     */
    private fun manualRun() {
        if (!hostAllowed(binding.webView.url.orEmpty())) {
            Ui.toast(this, getString(R.string.eams_untrusted_page))
            return
        }
        binding.webView.evaluateJavascript("!!(window.ZSJExport && window.ZSJExport.run)") { ready ->
            if (ready == "true") {
                binding.webView.evaluateJavascript("window.ZSJExport.run()", null)
            } else {
                binding.webView.evaluateJavascript(exportScript) {
                    binding.webView.evaluateJavascript("window.ZSJExport && window.ZSJExport.run()", null)
                }
            }
        }
    }

    private fun headerAction(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#287760"))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(Rpx.dp(10f), Rpx.dp(6f), Rpx.dp(6f), Rpx.dp(6f))
        setBackgroundResource(R.drawable.bg_menu_row)
        isClickable = true
        isFocusable = true
    }

    /** 应用当前 UA 模式；教务页排版异常时可用右上角切到电脑模式。 */
    private fun applyMode() {
        binding.webView.settings.apply {
            userAgentString = if (desktopMode) DESKTOP_UA else defaultMobileUA
            // 桌面模式用 TEXT_AUTOSIZING，避免 PC 页被手机字体/排版策略压缩
            layoutAlgorithm = if (desktopMode) WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                else WebSettings.LayoutAlgorithm.NORMAL
        }
        // 按钮语义为「点击切换到该模式」：当前是桌面则显示“手机模式”，反之显示“电脑模式”
        modeToggle.text = getString(if (desktopMode) R.string.eams_mode_mobile else R.string.eams_mode_desktop)
    }

    /**
     * 桌面模式补全 viewport：移除移动端 viewport meta，注入 width=1280 并触发 resize，
     * 使 PC 版教务页按 1280 宽布局（再缩放到屏宽），避免被挤压成手机窄版。参考拾光课程表实现。
     */
    private fun injectDesktopViewportFix(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
                try {
                    var metas = document.getElementsByTagName('meta');
                    for (var i = metas.length - 1; i >= 0; i--) {
                        if (metas[i].getAttribute('name') === 'viewport') {
                            metas[i].parentNode.removeChild(metas[i]);
                        }
                    }
                    var meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=1280, initial-scale=1.0, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';
                    document.head.appendChild(meta);
                    window.dispatchEvent(new Event('resize'));
                } catch (e) {}
            })();
            """.trimIndent(), null
        )
    }

    private fun toggleMode() {
        desktopMode = !desktopMode
        applyMode()
        binding.webView.reload()
        Ui.toast(this, getString(if (desktopMode) R.string.eams_mode_desktop else R.string.eams_mode_mobile))
    }

    private fun handleBack() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
    }

    private fun deliver(json: String) {
        if (delivered) return
        delivered = true
        cancelWatchdog()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_JSON, json))
        finish()
    }

    /** 当前停留的页面是否可信；桥对所有页面可见，靠这里拦住非教务页面的调用。 */
    private fun currentPageTrusted(): Boolean = hostAllowed(binding.webView.url.orEmpty())

    /**
     * 看门狗：脚本报告开始读取后若迟迟没有结果（桥失效、教务接口卡住、页面被重定向走），
     * 到点主动把话说清楚并给出下一步，不让用户干等在「正在读取课表…」上。
     */
    private fun startWatchdog() {
        cancelWatchdog()
        watchdog.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun cancelWatchdog() = watchdog.removeCallbacks(watchdogRunnable)

    /** JS 桥：方法名需与注入脚本中的 window.ZSJBridge 调用一致（R8 已在 proguard 保留）。 */
    private inner class Bridge {
        @JavascriptInterface
        fun onSchedule(json: String) {
            runOnUiThread {
                if (!currentPageTrusted()) return@runOnUiThread
                cancelWatchdog()
                when {
                    json.isBlank() -> Ui.alert(
                        this@EamsWebActivity,
                        getString(R.string.eams_parse_failed_title),
                        getString(R.string.eams_result_empty)
                    )
                    json.length > MAX_BRIDGE_CHARS -> Ui.alert(
                        this@EamsWebActivity,
                        getString(R.string.eams_parse_failed_title),
                        getString(R.string.eams_result_too_large)
                    )
                    else -> deliver(json)
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                if (!currentPageTrusted()) return@runOnUiThread
                cancelWatchdog()
                Ui.alert(
                    this@EamsWebActivity,
                    getString(R.string.eams_parse_failed_title),
                    getString(R.string.eams_parse_error, message.ifBlank { getString(R.string.eams_error_unknown) })
                )
            }
        }

        @JavascriptInterface
        fun onLog(message: String) {
            runOnUiThread {
                if (!currentPageTrusted()) return@runOnUiThread
                if (message == "start") {
                    Ui.toast(this@EamsWebActivity, getString(R.string.eams_parsing))
                    startWatchdog()
                }
            }
        }
    }

    override fun onDestroy() {
        cancelWatchdog()
        binding.webView.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_JSON = "json"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val MAX_BRIDGE_CHARS = 1_800_000
        /** 点下「导入课表」后等结果的上限；教务接口慢时也够用，超时只提示、不打断页面。 */
        private const val WATCHDOG_MS = 25_000L
    }
}
