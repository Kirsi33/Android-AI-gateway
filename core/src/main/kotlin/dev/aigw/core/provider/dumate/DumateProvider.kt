package dev.aigw.core.provider.dumate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 百度搭子 DuMate → OpenAI 兼容。
 *
 * 上游对话本身就是 OpenAI 协议（`dumate-svc.baidu.com/gateway/apis/v1/chat/completions`），
 * 所以不需要协议转换：**改模型名、换 Bearer 头、原样转发**即可。
 *
 * 认证分两段：网页 cookie → `/api/dumate/app/token/get` 换短期 token → 对话。
 * cookie 过期要用户重新取，token 过期自己刷。
 */
class DumateProvider(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : Provider {

    private val client = DumateClient()

    /** uid → 换好的 token（避免每次请求都打一次换票接口）。 */
    private data class TokenEntry(val token: String, val expiresAt: Long)

    private val tokens = ConcurrentHashMap<String, TokenEntry>()

    override val id: String = DumateConstants.ID
    override val displayName: String = "百度搭子"
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    // ------------------------------------------------------------------ 模型

    /**
     * 上游只有两个可用模型：`model-text`（通用文本）与 `glm-5`。
     * 其它名字一律映射到 `model-text`——上游没有 claude/gpt，原样发会被拒。
     */
    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val models = DumateConstants.SUPPORTED_MODELS.map { name ->
            ProviderModel(id = "$id/$name", name = name, contextWindow = 0, extra = emptyMap())
        }
        return ProviderModelCatalogView(models, fromFallback = false, error = "")
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfter('/').substringAfter('$')
        return if (model in DumateConstants.SUPPORTED_MODELS) model else DumateConstants.MODEL_TEXT
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val parsed = runCatching { DumateAccount.parse(account.secret) }.getOrNull()
            ?: return FailedChatCall(401, "账号凭证无法解析")
        val request = runCatching { JsonParser.parseString(openAiBody).asJsonObject }
            .getOrElse { return FailedChatCall(400, "请求体不是合法 JSON") }

        request.addProperty("model", resolveModel(request.stringOrNull("model").orEmpty()))

        val withToken = try {
            ensureToken(parsed)
        } catch (e: DumateExpiredException) {
            return FailedChatCall(401, e.message ?: "登录态已失效")
        } catch (e: Exception) {
            return FailedChatCall(502, e.message ?: "换取对话 token 失败")
        }

        val conn = try {
            HttpSupport.open(
                url = "https://" + DumateConstants.GATEWAY_HOST + DumateConstants.GATEWAY_PREFIX + DumateConstants.PATH_CHAT,
                method = "POST",
                headers = client.chatHeaders(withToken),
                body = request.toString(),
                connectTimeoutMs = DumateConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = DumateConstants.READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return FailedChatCall(502, "连接 DuMate 失败：${e.message}")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(502, "DuMate 未返回响应：${e.message}")
        }
        if (status !in 200..299) {
            val text = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            conn.disconnect()
            // token 失效：清缓存，下次请求重新换；cookie 真过期由上层禁用账号
            if (status == 401) tokens.remove(parsed.uid)
            return FailedChatCall(status, text.ifEmpty { "上游 HTTP $status" })
        }
        val stream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(status, "读取流失败：${e.message}")
        }
        return StreamingChatCall(status, stream, onClose = { conn.disconnect() })
    }

    /** 取（或复用）对话 token；提前 60 秒刷新。 */
    @Synchronized
    private fun ensureToken(account: DumateAccount): DumateAccount {
        val cached = tokens[account.uid]
        val now = nowMillis()
        if (cached != null && cached.expiresAt - DumateConstants.REFRESH_SKEW_MS > now) {
            return account.copy(token = cached.token, tokenExpiresAt = cached.expiresAt)
        }
        if (account.token.isNotEmpty() && account.tokenExpiresAt - DumateConstants.REFRESH_SKEW_MS > now) {
            tokens[account.uid] = TokenEntry(account.token, account.tokenExpiresAt)
            return account
        }
        // **token-only 账号**（粘贴 token 导入，没有 cookie）：没有换票的能力，
        // 直接用导入的 token——过期由上游 401 反映，报错提示用户重新取。
        if (account.cookie.isEmpty()) {
            if (account.token.isNotEmpty()) return account
            throw DumateExpiredException("没有 cookie 也没有 token，请重新导入")
        }
        val refreshed = client.fetchToken(account)
        tokens[account.uid] = TokenEntry(refreshed.token, refreshed.tokenExpiresAt)
        return refreshed
    }

    // ------------------------------------------------------------------ 额度 / 动作

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val parsed = runCatching { DumateAccount.parse(account.secret) }.getOrNull() ?: return null
        return try {
            val quota = client.quota(parsed)
            CreditInfo(
                balance = quota.remaining.coerceAtLeast(0),
                known = quota.remaining >= 0,
                detail = buildString {
                    if (quota.remaining >= 0) append("剩余 ").append(quota.remaining)
                    if (quota.total >= 0) append(" / ").append(quota.total)
                }.ifEmpty { "额度未知" },
            )
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询额度失败")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /** 每日登录奖励（DuMate 的「签到」，500/天）。先查状态再领，避免重复领被拒报成失败。 */
    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val parsed = runCatching { DumateAccount.parse(account.secret) }.getOrNull()
            ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            if (client.loginBonusInfo(parsed) == true) {
                return ProviderActionResult.success("今日已签到")
            }
            val (ok, message) = client.claimLoginBonus(parsed)
            if (ok) ProviderActionResult.success(message) else ProviderActionResult.failure(message)
        } catch (e: DumateExpiredException) {
            ProviderActionResult.failure("登录态已失效，请重新获取 cookie")
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 粘贴导入：cookie 串，或 `{"cookie":"..."}`。
     *
     * 导入时立刻换一次 token 验证 cookie——无效当场拒绝，不要等对话时才报错。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 支持**直接粘贴对话 token**（36 字符 UUID 形状）：token 由「网页 cookie → token/get」
        // 换出，24h 有效。没有 cookie 的账号对话可用，但签到/积分（走 cookie）不可用——
        // 这是给「WebView 登录链走不完」的场景留的兜底路径。
        val parsedCookie = runCatching { DumateAccount.parse(text) }.getOrNull()
        val bareToken = parsedCookie?.takeIf { it.cookie.isEmpty() && it.token.isNotEmpty() }?.token
            ?: text.takeIf {
                !it.startsWith("{") && it.length in 8..64 &&
                    it.all { c -> c.isLetterOrDigit() || c == '-' }
            }
        if (bareToken != null) {
            val uid = "dumate-tok-" + bareToken.take(8)
            val account = DumateAccount(
                uid = uid,
                nickname = "token 导入",
                cookie = "",
                token = bareToken,
            )
            return ProviderAccount(id, account.uid, account.nickname, account.toJson().toString())
        }
        var account = runCatching {
            DumateAccount.parse(text)
        }.getOrElse {
            // 返回 null 会被上层当成「该供应商不支持粘贴导入」，用户就永远看不到真正原因
            throw IllegalArgumentException("凭证解析失败：${it.message}。需要 cookie 串或 {\"cookie\":\"...\"}")
        }
        if (account.cookie.isEmpty()) throw IllegalArgumentException("cookie 为空")
        account = runCatching { client.userInfo(account) }.getOrDefault(account)
        account = runCatching { client.fetchToken(account) }.getOrElse {
            throw IllegalArgumentException("Cookie 换取对话 token 失败：${it.message}")
        }
        if (account.uid.isEmpty()) {
            account = account.copy(uid = "dumate-" + account.cookie.hashCode().toUInt())
        }
        tokens[account.uid] = TokenEntry(account.token, account.tokenExpiresAt)
        return ProviderAccount(id, account.uid, account.nickname, account.toJson().toString())
    }

    override fun classify(status: Int, body: String): UpstreamError = classifyDumate(status, body)

    override fun hosts(): List<String> = listOf(
        DumateConstants.WEB_BASE.removePrefix("https://"),
        DumateConstants.GATEWAY_HOST,
    )
}
