package dev.aigw.core.provider.qwenwork

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/** 设备授权票据。 */
data class QwenLoginTicket(
    val state: String,
    val authUrl: String,
    val verifier: String,
    val nonce: String,
    val createdAt: Long,
)

sealed interface QwenPollResult {
    data object Pending : QwenPollResult
    data object Expired : QwenPollResult
    data class Success(val account: QwenAccount) : QwenPollResult
    data class Failure(val message: String) : QwenPollResult
}

/** 三池积分余额。接口只给余额、不给上限——不要用余额反推上限当事实。 */
data class QwenCredits(
    val daily: Double,
    val monthly: Double,
    val longTerm: Double,
) {
    val total: Double get() = daily + monthly + longTerm

    fun summary(): String = buildString {
        append("日 ").append(fmt(daily))
        if (monthly != 0.0) append(" · 月 ").append(fmt(monthly))
        if (longTerm != 0.0) append(" · 长期 ").append(fmt(longTerm))
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)
}

/**
 * 千问办公认证与业务接口。
 *
 * 登录也是设备授权 + PKCE：
 * - 授权页在 **gateway** 上（`/device/selectAccounts`），302 到 qwenwork.cn 的标准 OAuth 页
 * - 取票轮询同样在 gateway（`/api/v1/deviceToken/poll`），**不需要 Authorization**
 *   所以整套流程能完全脱离桌面客户端跑
 */
class QwenAuthClient {

    fun beginLogin(): QwenLoginTicket {
        val verifier = dev.aigw.core.provider.qoder.QoderAccount.newVerifier()
        val challenge = dev.aigw.core.provider.qoder.QoderAccount.challengeOf(verifier)
        val nonce = dev.aigw.core.provider.qoder.QoderAccount.newNonce(true)

        val params = linkedMapOf(
            "challenge" to challenge,
            "challenge_method" to "S256",
            "nonce" to nonce,
            "redirect_uri" to QwenConstants.OAUTH_REDIRECT_URI,
            "client_id" to QwenConstants.OAUTH_CLIENT_ID,
            "machine_id" to dev.aigw.core.provider.qoder.QoderAccount.newMachineId(),
        )
        val query = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return QwenLoginTicket(
            state = "qw-" + System.nanoTime(),
            authUrl = QwenConstants.GATEWAY + QwenConstants.DEVICE_SELECT_PATH + "?" + query,
            verifier = verifier,
            nonce = nonce,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun poll(ticket: QwenLoginTicket): QwenPollResult {
        if (System.currentTimeMillis() - ticket.createdAt > QwenConstants.DEVICE_FLOW_TIMEOUT_MS) {
            return QwenPollResult.Expired
        }
        val query = "nonce=" + enc(ticket.nonce) +
            "&verifier=" + enc(ticket.verifier) +
            "&challenge_method=S256"
        val (status, body) = request(
            url = QwenConstants.GATEWAY + QwenConstants.DEVICE_POLL_PATH + "?" + query,
            method = "GET",
            body = null,
            headers = mapOf("Accept" to "application/json", "User-Agent" to QwenConstants.USER_AGENT),
        )
        // pending 表现为 404 + 空 body
        if (status == 404 || status == 202) return QwenPollResult.Pending
        if (status !in 200..299) return QwenPollResult.Failure("设备授权轮询失败：HTTP $status ${body.take(120)}")
        val data = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return QwenPollResult.Failure("轮询响应无法解析：${body.take(120)}")
        val token = data.stringOrNull("token").orEmpty().ifEmpty { data.stringOrNull("device_token").orEmpty() }
        if (token.isEmpty()) return QwenPollResult.Pending
        val refresh = data.stringOrNull("refresh_token").orEmpty()
        if (refresh.isEmpty()) {
            return QwenPollResult.Failure("取票响应里没有 refresh_token，这个账号无法续期")
        }

        var account = QwenAccount(
            uid = "",
            accessToken = token,
            refreshToken = refresh,
            expiresAt = expiresAtOf(data),
            nickname = "",
            organizationId = "",
            machineId = dev.aigw.core.provider.qoder.QoderAccount.newMachineId(),
        )
        // 账号信息（uid/昵称/组织）必须拿到：uid 是签名的一部分
        val withInfo = runCatching { accountContext(account) }.getOrNull()
            ?: return QwenPollResult.Failure("取到了 token 但读不到账号信息，无法建立可用账号")
        account = withInfo
        if (account.uid.isEmpty()) return QwenPollResult.Failure("账号信息里没有 uid，无法签名")
        return QwenPollResult.Success(account)
    }

    fun refresh(account: QwenAccount): QwenAccount {
        if (account.refreshToken.isEmpty()) throw IllegalStateException("缺少 refreshToken，需要重新授权")
        val (status, body) = request(
            url = QwenConstants.GATEWAY + QwenConstants.REFRESH_PATH,
            method = "GET",
            body = null,
            headers = bearer(account),
        )
        if (status !in 200..299) throw IllegalStateException("刷新凭证失败：HTTP $status ${body.take(120)}")
        val data = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("刷新响应无法解析")
        val token = data.stringOrNull("token").orEmpty().ifEmpty { data.stringOrNull("device_token").orEmpty() }
        if (token.isEmpty()) throw IllegalStateException("刷新未返回 token")
        return account.copy(
            accessToken = token,
            refreshToken = data.stringOrNull("refresh_token").orEmpty().ifEmpty { account.refreshToken },
            expiresAt = expiresAtOf(data),
        )
    }

    /** 账号上下文：uid / 昵称 / 组织 / 套餐 / 额度。 */
    fun accountContext(account: QwenAccount): QwenAccount {
        val (status, body) = request(
            url = QwenConstants.GATEWAY + QwenConstants.ACCOUNT_CONTEXT_PATH,
            method = "GET",
            body = null,
            headers = bearer(account),
        )
        if (status !in 200..299) throw IllegalStateException("读取账号信息失败：HTTP $status ${body.take(120)}")
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return account
        val data = root.objOrNull("data") ?: root
        val user = data.objOrNull("user") ?: data
        return account.copy(
            uid = user.stringOrNull("id").orEmpty()
                .ifEmpty { user.stringOrNull("uid").orEmpty() }
                .ifEmpty { account.uid },
            nickname = user.stringOrNull("name").orEmpty()
                .ifEmpty { user.stringOrNull("nickname").orEmpty() }
                .ifEmpty { account.nickname },
            organizationId = user.stringOrNull("organization_id").orEmpty()
                .ifEmpty { data.stringOrNull("organization_id").orEmpty() }
                .ifEmpty { account.organizationId },
        )
    }

    /**
     * 三池积分余额（站点域 `/user/wallets`）。
     *
     * 接口**只给余额不给上限**——「今日已用」需要上限，而上限来自策略配置，
     * 不是接口给的，所以这里只报余额，不编造百分比。
     */
    fun credits(account: QwenAccount): QwenCredits {
        val (status, body) = request(
            url = QwenConstants.SITE_ORIGIN + QwenConstants.WALLETS_PATH,
            method = "GET",
            body = null,
            headers = bearer(account),
        )
        if (status !in 200..299) throw IllegalStateException("查询积分失败：HTTP $status ${body.take(120)}")
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return QwenCredits(0.0, 0.0, 0.0)
        val data = root.objOrNull("data") ?: root
        return QwenCredits(
            daily = data.doubleOrNull("daily_credits") ?: data.doubleOrNull("daily") ?: 0.0,
            monthly = data.doubleOrNull("monthly_credits") ?: data.doubleOrNull("monthly") ?: 0.0,
            longTerm = data.doubleOrNull("longterm_credits") ?: data.doubleOrNull("longterm") ?: 0.0,
        )
    }

    /** 模型目录（`/api/v2/model/list`）。失败返回 null，调用方用兜底表。 */
    fun fetchModels(account: QwenAccount): List<String>? {
        val (status, body) = request(
            url = QwenConstants.GATEWAY + QwenConstants.MODELS_PATH,
            method = "GET",
            body = null,
            headers = bearer(account),
        )
        if (status !in 200..299) return null
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val list = root.arrayOrNull("chat")
            ?: root.arrayOrNull("data")
            ?: root.arrayOrNull("models")
            ?: return null
        val ids = list.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject
            obj?.stringOrNull("key").orEmpty()
                .ifEmpty { obj?.stringOrNull("id").orEmpty() }
                .ifEmpty { obj?.stringOrNull("model").orEmpty() }
                .takeIf { it.isNotEmpty() }
        }
        return ids.ifEmpty { null }
    }

    // ------------------------------------------------------------------ 工具

    private fun bearer(account: QwenAccount): Map<String, String> = mapOf(
        "Authorization" to "Bearer " + account.accessToken,
        "Accept" to "application/json",
        "User-Agent" to QwenConstants.USER_AGENT,
    )

    private fun JsonObject.doubleOrNull(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

    private fun expiresAtOf(data: JsonObject): Long {
        val at = data.stringOrNull("expires_at").orEmpty()
        if (at.isNotEmpty()) {
            runCatching { java.time.Instant.parse(at).epochSecond }.getOrNull()?.let { return it }
        }
        val raw = data.longOrNull("expiresAt") ?: 0L
        if (raw > 0) return if (raw > 1e12) raw / 1000 else raw
        val ttl = data.longOrNull("expires_in") ?: 0L
        return if (ttl > 0) System.currentTimeMillis() / 1000 + ttl else 0L
    }

    private fun enc(value: String): String = dev.aigw.core.util.percentEncode(value)

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
            connectTimeoutMs = QwenConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = QwenConstants.READ_TIMEOUT_MS,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }
}
