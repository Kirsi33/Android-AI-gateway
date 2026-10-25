package dev.aigw.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 内嵌网页登录：打开上游网站，用户自己登录，登录完成后把 Cookie 交回调用方。
 *
 * 为什么需要它：LongCat（美团龙猫）这类上游**没有开放 API**，凭证就是浏览器里的
 * Cookie（`passport_token_key`）。让用户去开发者工具里复制整串 Cookie 门槛太高，
 * 而且手机上根本没法开 DevTools —— 所以这里用 App 内嵌的 WebView 走一遍网页登录，
 * 登录态落在 WebView 自己的 Cookie 库里，完成后直接读出来。
 *
 * 兼容性（面向旧机器）：
 *  - 全程只用一个 WebView（不开多窗口），`onCreateWindow` 一律返回 false；
 *  - 不加 JS 注入、不依赖 `androidx.webkit`，只用 SDK 自带的 webkit；
 *  - WebView 缺失（极旧或精简 ROM）时降级为 Toast 提示，不崩溃。
 */
class CookieLoginActivity : Activity() {

    private var web: WebView? = null
    private var status: TextView? = null

    /** 判定「登录已完成」的 URL 前缀（Trae 51120 / Antigravity 51121 的回调地址）。 */
    private var successPrefixes: List<String> = emptyList()
    /** 需要等待出现的 Cookie 名（LongCat 这类站内跳转、没有回调地址的上游）。 */
    private var successCookieName: String = ""
    /** 已经触发过自动确认，避免重复。 */
    private var autoConfirmed = false
    /** 本次要读取 Cookie 的域名（保存下来给自动确认路径用）。 */
    private var pendingHosts: Array<String> = emptyArray()
    /** 判断「关键 Cookie 是否出现」时要查的域名。 */
    private var cookieHostsForCheck: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        val cookieHosts = intent.getStringArrayExtra(EXTRA_COOKIE_HOSTS) ?: emptyArray()
        successPrefixes = intent.getStringArrayExtra(EXTRA_SUCCESS_URL_PREFIXES)?.toList() ?: emptyList()
        successCookieName = intent.getStringExtra(EXTRA_SUCCESS_COOKIE).orEmpty()
        pendingHosts = cookieHosts
        cookieHostsForCheck = cookieHosts.toList()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "登录" }

        if (startUrl.isEmpty()) {
            Toast.makeText(this, "缺少登录地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 跟随窗口主题（values / values-night 各一套），不再硬编码白底：
            // 原来的 Color.WHITE 在深色模式下是一整块刺眼的白板。
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
        }

        // 顶部：说明 + 完成按钮（用户自己决定何时算登录完成）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val label = TextView(this).apply {
            text = "登录完成后点右侧按钮"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = Button(this).apply {
            text = "完成"
            textSize = 14f
            setOnClickListener { collectAndFinish(cookieHosts) }
        }
        bar.addView(label)
        bar.addView(done)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        val view = try {
            WebView(this)
        } catch (e: Throwable) {
            // 极少数精简 ROM 没带 WebView 实现
            Toast.makeText(this, "本机缺少 WebView 组件，无法内嵌登录", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        web = view

        val statusBar = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(dp(14), dp(4), dp(14), dp(6))
        }
        status = statusBar

        root.addView(bar)
        root.addView(progress)
        root.addView(statusBar)
        root.addView(view)
        setContentView(root)

        configure(view, progress)
        view.loadUrl(startUrl)
    }

    private fun configure(view: WebView, progress: ProgressBar) {
        val settings: WebSettings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // 缩放相关一律保持默认：项目里试过 useWideViewPort + loadWithOverviewMode，
        // 在部分机器上会让整页缩放错乱、输入框文字镜像。默认值最稳。
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        // 站内跳转都留在本 WebView；外链（支付、帮助等）也不新开窗口，避免多窗口在旧机器上崩
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                maybeAutoConfirm(request?.url?.toString().orEmpty())
                return false
            }

            @Deprecated("仅老系统走这个重载")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                maybeAutoConfirm(url.orEmpty())
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                maybeAutoConfirm(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                status?.text = "已打开：${url.orEmpty().take(60)}"
                // 回调页渲染完再确认：给上游一个把 Set-Cookie 写完的窗口
                maybeAutoConfirm(url.orEmpty())
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
            }
        }
    }

    /**
     * 检测登录是否已完成，完成就自动收尾。
     *
     * 两种信号：
     *  1. **回调地址**（Trae 51120 / Antigravity 51121）—— 页面跳到 `http://127.0.0.1:5112x/...`
     *     说明服务端已经下发凭证，此时直接读 Cookie 收尾，用户不用再点「完成」；
     *  2. **关键 Cookie 出现**（LongCat 这类站内跳转、没有回调地址的上游）—— 只有登录成功后
     *     浏览器才会拿到 `passport_token_key`，看到它就说明登录完成了。
     */
    private fun maybeAutoConfirm(url: String) {
        if (autoConfirmed) return
        val hitPrefix = successPrefixes.any { url.startsWith(it) }
        val hitCookie = successCookieName.isNotEmpty() && hasCookieNamed(successCookieName)
        if (!hitPrefix && !hitCookie) return
        autoConfirmed = true
        status?.text = "登录成功，正在保存…"
        // 稍等一拍：让 Set-Cookie 落盘、页面别闪一下就关
        web?.postDelayed({ collectAndFinish(true) }, AUTO_CONFIRM_DELAY_MS)
    }

    /** 目标域名下是否存在指定名字的 Cookie。 */
    private fun hasCookieNamed(name: String): Boolean {
        val manager = CookieManager.getInstance()
        for (host in cookieHostsForCheck) {
            val raw = manager.getCookie(host).orEmpty()
            if (raw.split(';').any { it.trim().startsWith("$name=") }) return true
        }
        return false
    }

    /** 读取目标域名下的 Cookie 并回传；拿不到就提示用户先登录。 */
    private fun collectAndFinish(hosts: Array<String>) = collectAndFinish(hosts, false)

    private fun collectAndFinish(fromAuto: Boolean) = collectAndFinish(pendingHosts, fromAuto)

    private fun collectAndFinish(hosts: Array<String>, fromAuto: Boolean) {
        val manager = CookieManager.getInstance()
        manager.flush()
        val builder = StringBuilder()
        for (host in hosts) {
            val cookie = manager.getCookie(host).orEmpty()
            if (cookie.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append("; ")
                builder.append(cookie)
            }
        }
        if (builder.isEmpty()) {
            if (!fromAuto) {
                Toast.makeText(this, "还没读到登录信息，请先在页面里完成登录", Toast.LENGTH_LONG).show()
            }
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_COOKIE, builder.toString()),
        )
        finish()
    }

    override fun onBackPressed() {
        val view = web
        if (view != null && view.canGoBack()) {
            view.goBack()
            return
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        try {
            web?.stopLoading()
            web?.destroy()
        } catch (_: Throwable) {
        }
        web = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 读取当前窗口主题里的颜色属性。
     *
     * Activity 是原生 View（不是 Compose），拿不到 AiGatewayTheme 的配色，
     * 所以用主题属性代替硬编码色值：系统深色时自动换成深色底 + 浅色字。
     */
    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return FALLBACK_COLOR
        return if (value.resourceId != 0) {
            runCatching { resources.getColor(value.resourceId, theme) }.getOrDefault(FALLBACK_COLOR)
        } else {
            value.data
        }
    }

    companion object {
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_COOKIE_HOSTS = "cookie_hosts"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RESULT_COOKIE = "cookie"
        /** 命中任一前缀即视为登录完成（回调地址）。 */
        const val EXTRA_SUCCESS_URL_PREFIXES = "success_url_prefixes"
        /** 出现这个 Cookie 即视为登录完成（没有回调地址的上游）。 */
        const val EXTRA_SUCCESS_COOKIE = "success_cookie"

        /** 自动确认后等多久再收尾，给 Set-Cookie 落盘留时间。 */
        private const val AUTO_CONFIRM_DELAY_MS = 600L
        const val REQUEST_CODE = 8803

        /** 主题属性解析失败时的中性灰：比「一片全黑/全白」更容易看出是降级了。 */
        private const val FALLBACK_COLOR = 0xFF888888.toInt()
    }
}
