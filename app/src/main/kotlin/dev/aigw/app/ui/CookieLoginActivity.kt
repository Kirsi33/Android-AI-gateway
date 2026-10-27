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

    /** 是否用桌面 UA 打开（部分上游按 UA 判定「地区不开放」）。 */
    private var desktopUserAgent = false
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
        desktopUserAgent = intent.getBooleanExtra(EXTRA_DESKTOP_UA, false)

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
            text = "若未自动跳转，请点右上角「完成」"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 显式关闭入口：这个页面盖住了 App，用户不知道「怎么退出去」，
        // 而 Android 13+ 的预测性返回不保证走 onBackPressed，只靠返回键会让人以为登录丢了。
        val close = TextView(this).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), dp(14), dp(8))
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        val done = Button(this).apply {
            text = "完成"
            textSize = 14f
            setOnClickListener { collectAndFinish(cookieHosts) }
        }
        bar.addView(close)
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

        // 桌面 UA：部分上游按 UA 判断客户端平台，手机 UA 会直接回「该地区不开放」，
        // 桌面 UA 同一个网络却能进。只在调用方显式要求时才改（默认保持移动端，
        // 因为有些站点的网页登录流程在桌面模式下反而更麻烦）。
        if (desktopUserAgent) {
            settings.userAgentString = DESKTOP_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }

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

    /**
     * 读取登录态并回传。
     *
     * 除了 Cookie，还会把 **localStorage / sessionStorage 里的键值**一起拼进来：
     * 现在的网站多半是 SPA，登录 token 存在 Web Storage 而不是 Cookie，
     * 只读 Cookie 会出现「页面明明登录了、宿主却什么都没读到」。
     * 拼接后的整串交给各供应商自己的正则去挑（Today 是 32 位 hex，LongCat 是 passport_token_key）。
     */
    private fun collectAndFinish(hosts: Array<String>, fromAuto: Boolean) {
        val manager = CookieManager.getInstance()
        manager.flush()
        val cookies = StringBuilder()
        for (host in hosts) {
            val cookie = manager.getCookie(host).orEmpty()
            if (cookie.isNotBlank()) {
                if (cookies.isNotEmpty()) cookies.append("; ")
                cookies.append(cookie)
            }
        }
        val view = web
        if (view == null) {
            deliver(cookies.toString(), fromAuto)
            return
        }
        // evaluateJavascript 是异步的，必须在回调里收尾
        runCatching {
            view.evaluateJavascript(STORAGE_PROBE_JS) { raw ->
                val storage = decodeJsString(raw)
                val merged = when {
                    cookies.isEmpty() -> storage
                    storage.isEmpty() -> cookies.toString()
                    else -> "$cookies; $storage"
                }
                deliver(merged, fromAuto)
            }
        }.onFailure { deliver(cookies.toString(), fromAuto) }
    }

    /** 把读到的登录态交回宿主；拿不到时给出可操作的提示而不是静默返回。 */
    private fun deliver(raw: String, fromAuto: Boolean) {
        if (raw.isBlank()) {
            if (!fromAuto) {
                Toast.makeText(this, "还没读到登录信息，请先在页面里完成登录", Toast.LENGTH_LONG).show()
                return
            }
            // 自动确认命中但读不到任何登录态：多半是登录还没真正落盘（或域名配错了）。
            // 必须复位并给出可见提示，否则界面会永久停在「正在保存…」。
            autoConfirmed = false
            status?.text = "没读到登录信息，请确认已登录成功，再点右上角「完成」"
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_COOKIE, raw),
        )
        finish()
    }

    /** WebView 的 evaluateJavascript 会把结果当 JSON 字面量返回（带引号、可能有 \u 转义）。 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            com.google.gson.JsonParser.parseString(raw).asString
        }.getOrDefault("")
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

        /** 桌面 UA：绕开「移动端不允许」的地域/客户端判定。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 用桌面 UA + 桌面视口打开登录页（Trae 国际版这类按 UA 拦人的上游）。 */
        const val EXTRA_DESKTOP_UA = "desktop_ua"

        /** 自动确认后等多久再收尾，给 Set-Cookie 落盘留时间。 */
        private const val AUTO_CONFIRM_DELAY_MS = 600L

        /**
         * 读取 Web Storage 的探针。
         *
         * 只收 500 字符以内的值：token 都很短，超长的是缓存正文，拼进来没意义还会把
         * 供应商那边的正则拖慢。整体用 try 包住——某些页面禁用了 storage，抛异常会让
         * evaluateJavascript 直接不回调。
         */
        private const val STORAGE_PROBE_JS = """
            (function(){try{
              var out=[];
              try{
                for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var v=localStorage.getItem(k);if(v&&v.length<500){out.push(k+'='+v);}}
              }catch(e){}
              try{
                for(var j=0;j<sessionStorage.length;j++){var k2=sessionStorage.key(j);var v2=sessionStorage.getItem(k2);if(v2&&v2.length<500){out.push(k2+'='+v2);}}
              }catch(e){}
              return out.join(';');
            }catch(e){return '';}})()
        """
        const val REQUEST_CODE = 8803

        /** 主题属性解析失败时的中性灰：比「一片全黑/全白」更容易看出是降级了。 */
        private const val FALLBACK_COLOR = 0xFF888888.toInt()
    }
}
