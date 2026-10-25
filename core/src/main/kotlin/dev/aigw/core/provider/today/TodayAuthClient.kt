package dev.aigw.core.provider.today

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Today 的短信登录客户端。
 *
 * 协议来自 today-proxy 系列的实测（github.com/Main8986/today-proxy-android，纯 Java 实现）：
 *
 * ```
 * POST {auth}/api/auth/phone-number/send-otp   {"phoneNumber":"+8613800000000"}
 * POST {auth}/api/auth/phone-number/verify     {"phoneNumber":"+86...","code":"123456"}
 *      → {"token":"<32 位 session token>","user":{"phoneNumber":"+86..."}}
 * ```
 *
 * 拿到的 `token` 与「手动粘贴 session token」完全是同一个东西，所以两条登录路径
 * 最终都落到同一个凭证模型 [TodayAccount] 上。
 *
 * 为什么优先走短信而不是网页登录：网页登录要靠 WebView 从 Cookie / localStorage 里
 * **猜**哪个值是 token，网站改一次登录态命名就失效；短信登录直接拿官方返回值。
 */
class TodayAuthClient {

    /**
     * 发验证码。
     *
     * Today 不返回 msgid（登录时也不需要回传），所以这里固定返回空串——
     * 接口签名要求一个 String，用空串比编一个假 id 诚实。
     */
    fun sendOtp(phone: String): String {
        val body = JsonObject().apply { addProperty("phoneNumber", phone) }.toString()
        val (status, text) = post("/api/auth/phone-number/send-otp", body)
        if (status !in 200..299) {
            throw IllegalStateException("发送验证码失败（HTTP $status）：${trim(text)}")
        }
        return ""
    }

    /** 校验验证码并换取 session token。失败抛 [IllegalStateException]，消息可直接展示给用户。 */
    fun verify(phone: String, code: String): TodayAccount {
        val body = JsonObject().apply {
            addProperty("phoneNumber", phone)
            addProperty("code", code)
        }.toString()
        val (status, text) = post("/api/auth/phone-number/verify", body)
        if (status !in 200..299) {
            throw IllegalStateException("登录失败（HTTP $status）：${trim(text)}")
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("登录返回不是合法 JSON")
        val token = obj.str("token")
        if (token.isEmpty()) {
            throw IllegalStateException("登录返回里没有 token：${trim(text)}")
        }
        val phoneNumber = obj.objOrNull("user")?.str("phoneNumber").orEmpty()
        return TodayAccount(
            token = token,
            // 有手机号就用手机号当昵称，一眼能看出是哪个号
            nickname = phoneNumber,
            uid = TodayAccount.uidOf(token),
        )
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val conn = URL(TodayConstants.AUTH_BASE + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "zh-CN")
            conn.setRequestProperty("Origin", "https://todayai.cn")
            conn.setRequestProperty("Referer", "https://todayai.cn/")
            conn.setRequestProperty("X-Client-Platform", "android")
            conn.setRequestProperty("X-App-Version", TodayConstants.APP_VERSION)
            conn.setRequestProperty("User-Agent", TodayConstants.userAgent())
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            status to (stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun trim(text: String): String =
        text.replace(Regex("<[^>]+>"), " ").trim().take(200)
}
