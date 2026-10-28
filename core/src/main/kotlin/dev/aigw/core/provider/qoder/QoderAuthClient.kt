package dev.aigw.core.provider.qoder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * Qoder 认证与业务 REST。
 *
 * 登录走 **OAuth 设备授权 + PKCE S256**：
 * 1. 生成 verifier/challenge/nonce → 打开授权页（用户扫码或登录）
 * 2. `GET {openapi}/api/v1/deviceToken/poll?nonce=..&verifier=..&challenge_method=S256`
 * 3. **404 / 202 = 用户还没授权**（继续轮询），不是错误——这是最容易写错的一步
 * 4. 成功返回 token/user_id/refresh_token
 *
 * 业务端点（userinfo / 额度 / 签到）全部是纯 Bearer，**不走 COSY 签名**；
 * 只有推理网关才需要签名（见 [Cosy]）。
 */
class QoderAuthClient {

    /** 构造授权 URL 与本地凭据（verifier/nonce 必须留住，轮询要用）。 */
    fun beginLogin(realm: QoderRealm): QoderLoginTicket {
        val verifier = QoderAccount.newVerifier()
        val challenge = QoderAccount.challengeOf(verifier)
        val nonce = QoderAccount.newNonce(realm.dashedNonce)
        // machineId 仍要生成：它是 COSY 签名头 `cosy-machineid` 的一部分，
        // 只是**不进授权 URL**。
        QoderAccount.newMachineId()

        // **只带这四个参数**：nonce / challenge / challenge_method / client_id。
        // 加 machine_id、redirect_uri 会让授权页与取票端点对不上，表现为轮询永远 404
        // （用户只看到「等到超时」，完全猜不到原因）。
        val params = listOf(
            "nonce" to nonce,
            "challenge" to challenge,
            "challenge_method" to "S256",
            "client_id" to realm.clientId,
        )
        val query = params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val url = realm.website + QoderConstants.DEVICE_AUTH_PAGE + "?" + query
        return QoderLoginTicket(
            state = "qd-" + System.nanoTime(),
            authUrl = url,
            verifier = verifier,
            nonce = nonce,
            realm = realm,
            createdAt = System.currentTimeMillis(),
        )
    }

    /** 轮询一次授权结果。 */
    fun poll(ticket: QoderLoginTicket): QoderPollResult {
        if (System.currentTimeMillis() - ticket.createdAt > QoderConstants.LOGIN_TTL_MS) {
            return QoderPollResult.Expired
        }
        val query = "nonce=" + enc(ticket.nonce) +
            "&verifier=" + enc(ticket.verifier) +
            "&challenge_method=S256"
        val (status, body) = request(
            url = ticket.realm.openapi + QoderConstants.PATH_DEVICE_POLL + "?" + query,
            method = "GET",
            body = null,
            headers = mapOf(
                "Accept" to "application/json",
                // 参考实现取票用 qoder/1.0.0（不是 QoderWork），UA 不匹配会被当未知客户端
                "User-Agent" to "qoder/1.0.0",
            ),
        )
        // 404 / 202 = 尚未授权，继续等
        if (status == 404 || status == 202) return QoderPollResult.Pending
        if (status !in 200..299) {
            return QoderPollResult.Failure("设备授权轮询失败：HTTP $status ${body.take(120)}")
        }
        val data = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return QoderPollResult.Failure("轮询响应无法解析：${body.take(120)}")
        val token = data.stringOrNull("token").orEmpty().ifEmpty {
            data.stringOrNull("device_token").orEmpty()
        }
        if (token.isEmpty()) return QoderPollResult.Pending

        var account = QoderAccount(
            uid = data.stringOrNull("user_id").orEmpty(),
            accessToken = token,
            refreshToken = data.stringOrNull("refresh_token").orEmpty(),
            expiresAt = expiresAtOf(data),
            realm = ticket.realm.id,
            nickname = "",
            userType = "",
            organizationId = "",
            machineId = QoderAccount.newMachineId(),
        )
        // 补昵称/用户类型（尽力而为，失败不影响入库）
        account = runCatching { userInfo(account) }.getOrDefault(account)
        if (account.uid.isEmpty()) {
            return QoderPollResult.Failure("授权成功但上游没给 user_id，无法入库")
        }
        return QoderPollResult.Success(account)
    }

    /** 用 refresh 换新 access token。 */
    fun refresh(account: QoderAccount): QoderAccount {
        if (account.refreshToken.isEmpty()) {
            throw IllegalStateException("缺少 refreshToken，需要重新授权")
        }
        val realm = account.region
        val (status, body) = request(
            url = realm.openapi + QoderConstants.PATH_DEVICE_REFRESH,
            method = "GET",
            body = null,
            headers = bearer(account) + mapOf("Accept" to "application/json"),
        )
        if (status !in 200..299) throw IllegalStateException("刷新凭证失败：HTTP $status ${body.take(120)}")
        val data = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("刷新响应无法解析")
        val token = data.stringOrNull("token").orEmpty()
        if (token.isEmpty()) throw IllegalStateException("刷新未返回 token")
        return account.withTokens(
            accessToken = token,
            refreshToken = data.stringOrNull("refresh_token").orEmpty(),
            expiresAt = expiresAtOf(data),
        )
    }

    /** 补全昵称/用户类型/组织。 */
    fun userInfo(account: QoderAccount): QoderAccount {
        val (status, body) = request(
            url = account.region.openapi + QoderConstants.PATH_USER_INFO,
            method = "GET",
            body = null,
            headers = bearer(account) + mapOf("Accept" to "application/json"),
        )
        if (status !in 200..299) throw IllegalStateException("获取用户信息失败：HTTP $status ${body.take(120)}")
        val data = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return account
        // 有的版本包一层 data/result
        val root = data.objOrNull("data") ?: data.objOrNull("result") ?: data
        return account.copy(
            uid = root.stringOrNull("id").orEmpty().ifEmpty { account.uid },
            nickname = root.stringOrNull("name").orEmpty().ifEmpty { account.nickname },
            userType = root.stringOrNull("user_type").orEmpty().ifEmpty { account.userType },
            organizationId = root.stringOrNull("organization_id").orEmpty()
                .ifEmpty { root.stringOrNull("organizationId").orEmpty() }
                .ifEmpty { account.organizationId },
        )
    }

    /**
     * 额度（`/api/v2/quota/usage`）。
     *
     * 上游字段名在不同版本间变过（total/limit/usage/used/remaining），
     * 这里按优先级逐个认，认不到就记 -1，界面显示「未知」而不是「0」。
     */
    fun quota(account: QoderAccount): QoderQuota {
        val (status, body) = request(
            url = account.region.openapi + QoderConstants.PATH_QUOTA,
            method = "GET",
            body = null,
            headers = bearer(account) + mapOf("Accept" to "application/json"),
        )
        if (status !in 200..299) {
            throw IllegalStateException("查询额度失败：HTTP $status ${body.take(120)}")
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return QoderQuota(-1, -1, -1, "", body.take(200))
        val data = root.objOrNull("data") ?: root.objOrNull("result") ?: root
        val total = firstLong(data, "total", "limit", "quota", "total_amount", "totalAmount")
        val used = firstLong(data, "used", "usage", "consumed", "used_amount", "usedAmount")
        val remaining = firstLong(data, "remaining", "remain", "left", "balance")
            .let { if (it >= 0) it else if (total >= 0 && used >= 0) total - used else -1L }
        val plan = data.objOrNull("plan")?.stringOrNull("name").orEmpty()
            .ifEmpty { data.stringOrNull("plan_name").orEmpty() }
        return QoderQuota(total, used, remaining, plan, root.toString())
    }

    /** 今日签到状态：true=已签，false=未签，null=该区不支持/查不到。 */
    fun checkinStatus(account: QoderAccount): Boolean? {
        val (status, body) = request(
            url = account.region.openapi + QoderConstants.PATH_CHECKIN_STATUS,
            method = "GET",
            body = null,
            headers = bearer(account) + mapOf("Accept" to "application/json"),
        )
        if (status == 404 || status == 405) return null
        if (status !in 200..299) return null
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val data = root.objOrNull("data") ?: root
        return when {
            data.get("checked_in")?.isJsonPrimitive == true ->
                runCatching { data.get("checked_in").asBoolean }.getOrDefault(false)
            data.get("checked")?.isJsonPrimitive == true ->
                runCatching { data.get("checked").asBoolean }.getOrDefault(false)
            data.get("status")?.isJsonPrimitive == true -> {
                val text = data.get("status").asString.lowercase()
                text.contains("checked") && !text.contains("not")
            }
            else -> null
        }
    }

    /** 领今日签到积分；返回 (是否成功, 展示文案)。 */
    fun claimCheckin(account: QoderAccount): Pair<Boolean, String> {
        val (status, body) = request(
            url = account.region.openapi + QoderConstants.PATH_CHECKIN_CLAIM,
            method = "POST",
            body = "{}",
            headers = bearer(account) + mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
        )
        if (status == 404 || status == 405) return false to "该区没有签到接口"
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val message = root?.stringOrNull("message").orEmpty()
            .ifEmpty { root?.stringOrNull("msg").orEmpty() }
            .ifEmpty { "HTTP $status" }
        if (status !in 200..299) return false to "签到失败：$message"
        val data = root?.objOrNull("data") ?: root
        val credits = data?.let { firstLong(it, "credits", "credit", "points", "amount") } ?: -1L
        val already = message.contains("已签") || message.contains("already")
        return when {
            credits > 0 -> true to "签到成功，+$credits credits"
            already -> true to "今日已签到"
            else -> true to "签到请求已提交（$message）"
        }
    }

    /** 拉模型目录（gateway 的 model/list）。失败返回 null，调用方用兜底表。 */
    fun fetchModels(account: QoderAccount): List<String>? {
        val (status, body) = request(
            url = account.region.gateway + QoderConstants.MODELS_PATH,
            method = "GET",
            body = null,
            headers = bearer(account) + mapOf("Accept" to "application/json"),
        )
        if (status !in 200..299) return null
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val list = root.arrayOrNull("data") ?: root.arrayOrNull("result") ?: root.arrayOrNull("models")
            ?: return null
        val ids = list.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject
            obj?.stringOrNull("id").orEmpty()
                .ifEmpty { obj?.stringOrNull("model").orEmpty() }
                .ifEmpty { obj?.stringOrNull("key").orEmpty() }
                .takeIf { it.isNotEmpty() }
                ?: item.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
        }
        return ids.ifEmpty { null }
    }

    // ------------------------------------------------------------------ 工具

    private fun bearer(account: QoderAccount): Map<String, String> = mapOf(
        "Authorization" to "Bearer " + account.accessToken,
        "User-Agent" to account.region.userAgent,
    )

    private fun expiresAtOf(data: JsonObject): Long {
        val raw = data.longOrNull("expires_at") ?: data.longOrNull("expiresAt") ?: 0L
        if (raw > 0) return if (raw > 1e12) raw / 1000 else raw
        val ttl = data.longOrNull("expires_in") ?: 0L
        return if (ttl > 0) System.currentTimeMillis() / 1000 + ttl else 0L
    }

    private fun firstLong(obj: JsonObject, vararg keys: String): Long {
        for (key in keys) obj.longOrNull(key)?.let { return it }
        return -1L
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
            connectTimeoutMs = QoderConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = QoderConstants.READ_TIMEOUT_MS,
        )
        return try {
            val status = conn.responseCode
            status to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }
}
