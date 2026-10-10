package dev.aigw.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        val cookieHosts = intent.getStringArrayExtra(EXTRA_COOKIE_HOSTS) ?: emptyArray()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "登录" }

        if (startUrl.isEmpty()) {
            Toast.makeText(this, "缺少登录地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
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
            setTextColor(Color.parseColor("#333333"))
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
            setTextColor(Color.parseColor("#666666"))
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
            ): Boolean = false

            @Deprecated("仅老系统走这个重载")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                status?.text = "已打开：${url.orEmpty().take(60)}"
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
            }
        }
    }

    /** 读取目标域名下的 Cookie 并回传；拿不到就提示用户先登录。 */
    private fun collectAndFinish(hosts: Array<String>) {
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
            Toast.makeText(this, "还没读到登录信息，请先在页面里完成登录", Toast.LENGTH_LONG).show()
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

    companion object {
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_COOKIE_HOSTS = "cookie_hosts"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RESULT_COOKIE = "cookie"
        const val REQUEST_CODE = 8803
    }
}
