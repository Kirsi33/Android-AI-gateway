package dev.aigw.core.provider.dumate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * 百度搭子 DuMate 协议常量。
 *
 * **两条链路、两套认证**（别混）：
 * - 网页侧 `www.dumate.cn`：Cookie 认证，用于签到 / 积分 / 任务
 * - 对话侧 `dumate-svc.baidu.com`：Bearer 认证，token 由 cookie 换出来
 *
 * 来源：dumate2api `src/dumate-web.js`（网页侧）与 `src/web-pool.js`（对话侧）。
 */
object DumateConstants {
    const val ID = "dumate"

    const val WEB_BASE = "https://www.dumate.cn"
    const val GATEWAY_HOST = "dumate-svc.baidu.com"
    const val GATEWAY_PREFIX = "/gateway/apis/v1"

    // ---- 网页侧（Cookie）----
    const val PATH_USER_INFO = "/api/dumate/user/info"
    const val PATH_LOGIN_BONUS_INFO = "/api/dumate/points/loginBonusInfo"
    const val PATH_LOGIN_BONUS = "/api/dumate/points/loginBonus"
    const val PATH_QUOTA = "/api/dumate/points/quota_overview?clientType=desktop&timezone=Asia%2FShanghai"
    const val PATH_GROWTH_TASKS = "/api/dumate/activity/growth-plan/tasks"

    /** cookie 换对话 token。 */
    const val PATH_TOKEN = "/api/dumate/app/token/get"

    // ---- 对话侧（Bearer）----
    const val PATH_CHAT = "/chat/completions"

    /**
     * 上游模型表：`model-text` 是通用文本对话；`glm-5` 上游直接接受。
     * 其它名字（claude-* / gpt-*）一律映射到 `model-text`——上游没有这些模型，
     * 原样发会被拒。
     */
    const val MODEL_TEXT = "model-text"
    val SUPPORTED_MODELS = listOf("model-text", "glm-5")

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 600_000

    /** 对话 token 失效前提前刷新的余量（上游 expired_at 是 ISO 时间）。 */
    const val REFRESH_SKEW_MS = 60_000L

    /** 上游登录态失效码（HTTP 401 也同等处理）。 */
    val EXPIRED_CODES = setOf(10001L, 10006L)
}

/** DuMate 账号：一个网页 cookie 就是一份登录态。 */
data class DumateAccount(
    val uid: String,
    val nickname: String,
    /** 网页 cookie 原串（签到/积分用） */
    val cookie: String,
    /** 对话 token（由 cookie 换取，短期有效，可能为空=尚未换） */
    val token: String = "",
    val tokenExpiresAt: Long = 0L,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("nickname", nickname)
        addProperty("cookie", cookie)
        if (token.isNotEmpty()) {
            addProperty("token", token)
            addProperty("tokenExpiresAt", tokenExpiresAt)
        }
    }

    /** cookie 里有没有关键会话键（多个都要：缺一个就是未登录）。 */
    fun cookieKeys(): List<String> = cookie.split(';').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) null else part.substring(0, index).trim()
    }

    companion object {
        fun parse(raw: String): DumateAccount {
            val root = JsonParser.parseString(raw).asJsonObject
            val cookie = root.stringOrNull("cookie").orEmpty()
            if (cookie.isEmpty()) throw IllegalArgumentException("凭证缺少 cookie")
            return DumateAccount(
                uid = root.stringOrNull("uid").orEmpty(),
                nickname = root.stringOrNull("nickname").orEmpty(),
                cookie = cookie,
                token = root.stringOrNull("token").orEmpty(),
                tokenExpiresAt = root.get("tokenExpiresAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        }
    }
}

/** 额度快照（`quota_overview` 的字段名不固定，取不到的记 -1）。 */
data class DumateQuota(
    val remaining: Long,
    val total: Long,
    val detail: String,
)

/**
 * DuMate 网络层。
 *
 * 网页侧的请求头缺 `X-Dumate-Client-Type: web` 会被当成非浏览器来源直接拒——
 * 这是最容易漏的一条。
 */
class DumateClient {

    /** 用 cookie 换对话 token。 */
    fun fetchToken(account: DumateAccount): DumateAccount {
        val (status, body) = request(
            url = DumateConstants.WEB_BASE + DumateConstants.PATH_TOKEN,
            method = "GET",
            body = null,
            headers = webHeaders(account),
        )
        if (status == 401) throw DumateExpiredException("登录态已失效，请重新获取 cookie")
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("换 token 响应无法解析：${body.take(120)}")
        if (!ok(root, status)) {
            val message = root.stringOrNull("message").orEmpty().ifEmpty { "HTTP $status" }
            if (isExpired(root)) throw DumateExpiredException("登录态已失效：$message")
            throw IllegalStateException("换 token 失败：$message")
        }
        val result = root.objOrNull("result") ?: return account
        val token = result.stringOrNull("token").orEmpty()
        if (token.isEmpty()) throw IllegalStateException("换 token 响应里没有 token")
        val expiresAt = result.stringOrNull("expired_at").orEmpty().let { iso ->
            if (iso.isEmpty()) 0L else runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
        }.let { if (it > 0) it else System.currentTimeMillis() + 50 * 60_000L }
        return account.copy(token = token, tokenExpiresAt = expiresAt)
    }

    /** 用户信息（昵称）。 */
    fun userInfo(account: DumateAccount): DumateAccount {
        val (status, body) = request(
            url = DumateConstants.WEB_BASE + DumateConstants.PATH_USER_INFO,
            method = "GET",
            body = null,
            headers = webHeaders(account),
        )
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return account
        if (isExpired(root)) throw DumateExpiredException("登录态已失效")
        val data = pick(root)?.takeIf { it.isJsonObject }?.asJsonObject ?: return account
        return account.copy(
            uid = data.stringOrNull("id").orEmpty()
                .ifEmpty { data.stringOrNull("uid").orEmpty() }
                .ifEmpty { account.uid },
            nickname = data.stringOrNull("nickname").orEmpty()
                .ifEmpty { data.stringOrNull("name").orEmpty() }
                .ifEmpty { account.nickname },
        )
    }

    /** 今日签到状态：true=已签，null=查不到。 */
    fun loginBonusInfo(account: DumateAccount): Boolean? {
        val (status, body) = request(
            url = DumateConstants.WEB_BASE + DumateConstants.PATH_LOGIN_BONUS_INFO,
            method = "GET",
            body = null,
            headers = webHeaders(account),
        )
        if (status !in 200..299) return null
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        if (isExpired(root)) throw DumateExpiredException("登录态已失效")
        val data = pick(root)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        // 字段名各版本不同：received / claimed / signed / today_received
        for (key in listOf("received", "claimed", "signed", "today_received", "is_receive")) {
            val value = data.get(key)?.takeIf { it.isJsonPrimitive } ?: continue
            return runCatching { value.asBoolean }.getOrNull()
        }
        return null
    }

    /** 领每日登录奖励（签到）。 */
    fun claimLoginBonus(account: DumateAccount): Pair<Boolean, String> {
        val (status, body) = request(
            url = DumateConstants.WEB_BASE + DumateConstants.PATH_LOGIN_BONUS,
            method = "POST",
            body = "{}",
            headers = webHeaders(account) + mapOf("Content-Type" to "application/json"),
        )
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val message = root?.stringOrNull("message").orEmpty()
            .ifEmpty { root?.stringOrNull("msg").orEmpty() }
            .ifEmpty { "HTTP $status" }
        if (root != null && isExpired(root)) {
            return false to "登录态已失效：$message"
        }
        if (status !in 200..299 || (root != null && !ok(root, status))) {
            return false to "签到失败：$message"
        }
        val data = pick(root)?.takeIf { it.isJsonObject }?.asJsonObject
        val points = data?.get("points")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: data?.get("score")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: -1L
        val already = message.contains("已领") || message.contains("already")
        return when {
            points > 0 -> true to "签到成功，+$points 积分"
            already -> true to "今日已签到"
            else -> true to "签到请求已提交"
        }
    }

    /** 额度总览。 */
    fun quota(account: DumateAccount): DumateQuota {
        val (status, body) = request(
            url = DumateConstants.WEB_BASE + DumateConstants.PATH_QUOTA,
            method = "GET",
            body = null,
            headers = webHeaders(account),
        )
        if (status !in 200..299) throw IllegalStateException("查询额度失败：HTTP $status ${body.take(120)}")
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return DumateQuota(-1, -1, "响应无法解析")
        if (isExpired(root)) throw DumateExpiredException("登录态已失效")
        val data = pick(root)?.takeIf { it.isJsonObject }?.asJsonObject ?: return DumateQuota(-1, -1, "")
        fun long(vararg keys: String): Long {
            for (key in keys) {
                data.get(key)?.takeIf { it.isJsonPrimitive }?.let { return runCatching { it.asLong }.getOrDefault(-1L) }
            }
            return -1L
        }
        val remain = long("remain_points", "remaining", "remain", "available")
        val total = long("total_points", "total", "quota")
        return DumateQuota(remain, total, data.toString().take(120))
    }

    // ------------------------------------------------------------------ 工具

    private fun webHeaders(account: DumateAccount): Map<String, String> = mapOf(
        "Cookie" to account.cookie,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        // 缺这个头会被当成非浏览器来源
        "X-Dumate-Client-Type" to "web",
        "Referer" to DumateConstants.WEB_BASE + "/app",
        "Origin" to DumateConstants.WEB_BASE,
        "User-Agent" to WEB_UA,
    )

    /** 对话侧请求头（Bearer）。 */
    fun chatHeaders(account: DumateAccount): Map<String, String> = mapOf(
        "Authorization" to "Bearer " + account.token,
        "X-Dumate-Client-Type" to "web",
        "X-Dumate-Account-Id" to "global",
        "Content-Type" to "application/json",
        "Accept" to "text/event-stream",
        "User-Agent" to WEB_UA,
    )

    private fun ok(root: JsonObject, status: Int): Boolean {
        if (status !in 200..299) return false
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
        if (code != null && code != 0L) return false
        val success = root.get("success")?.takeIf { it.isJsonPrimitive }
        if (success != null) return runCatching { success.asBoolean }.getOrDefault(true)
        return true
    }

    private fun isExpired(root: JsonObject): Boolean {
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
        return code != null && code in DumateConstants.EXPIRED_CODES
    }

    /** 结果体可能在 result / data / 顶层（上游三种都有）。 */
    private fun pick(root: JsonObject?): com.google.gson.JsonElement? {
        if (root == null) return null
        if (root.has("result")) return root.get("result")
        if (root.has("data")) return root.get("data")
        return root
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val conn = HttpSupport.open(
            url = url,
            method = method,
            headers = headers,
            body = body,
            connectTimeoutMs = DumateConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = DumateConstants.READ_TIMEOUT_MS,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

/** 登录态失效（cookie 过期）。 */
class DumateExpiredException(message: String) : IllegalStateException(message)

/** 错误分类。 */
internal fun classifyDumate(status: Int, body: String): UpstreamError {
    val message = runCatching {
        JsonParser.parseString(body).asJsonObject.stringOrNull("message").orEmpty()
    }.getOrDefault("").ifEmpty { "上游 HTTP $status" }
    val code = runCatching {
        JsonParser.parseString(body).asJsonObject.get("code")?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()
    val kind = when {
        status == 401 || code in DumateConstants.EXPIRED_CODES -> ErrorKind.SESSION_DEAD
        status == 402 -> ErrorKind.QUOTA
        status == 404 -> ErrorKind.NOT_FOUND
        status == 429 -> ErrorKind.SOFT_RATE
        status in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.CLIENT
    }
    return UpstreamError(kind, message)
}
