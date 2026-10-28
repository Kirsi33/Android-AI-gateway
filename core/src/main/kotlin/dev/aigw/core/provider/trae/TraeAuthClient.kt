package dev.aigw.core.provider.trae

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.long
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/**
 * 凭证刷新与账号信息。
 *
 * ExchangeToken 每次都会轮换 refreshToken，所以刷新成功后调用方必须立刻原子写回，
 * 否则拿旧 refreshToken 再换会失败。
 */
class TraeAuthClient(private val version: TraeVersion) {

    /**
     * 鉴权 host：凭证自带的 apiHost 优先，缺省时按区域取该区域的 OAuth host。
     *
     * 另外修掉存量数据里的「国际版账号 + 国内 apiHost」组合：旧版 [TraeAccount.parse]
     * 把国内 OAuth host 当缺省值写进了凭证，拿国际版 token 打国内端点会直接失败。
     */
    internal fun authHost(account: TraeAccount): String {
        val region = TraeRegion.infer(account.domain, account.apiHost)
        val host = account.apiHost.trim()
        // 自定义代理 host（非官方域名）优先，原样用
        val official = host.isEmpty() ||
            listOf("trae.cn", "trae.ai", "mchost.guru", "growsg", "coresg", "coreva")
                .any { host.contains(it) }
        if (!official) return host
        // 官方 host 一律按区域重算：
        //  - 国际版 auth 统一在 growsg-normal.trae.ai（美区也走它），
        //    存量凭证里的 api.trae.ai / 国内 api 都是错的，留着就会 404/401；
        //  - 国内版缺省 api.trae.cn。
        return region.oauthHost
    }

    /** refreshToken → 新 accessToken，并轮换 refreshToken。 */
    fun exchangeToken(account: TraeAccount): TraeAccount {
        if (account.refreshToken.isBlank()) {
            throw TraeHttpException(0, TraeErrorKind.SESSION_DEAD, "缺少 refreshToken，需要重新登录")
        }
        val host = authHost(account)
        val region = TraeRegion.infer(account.domain, account.apiHost)
        val body = JsonObject().apply {
            // client_id 按区域取：国际版用国内的会 401「refresh token is invalid」
            addProperty("ClientID", region.oauthClientId)
            addProperty("RefreshToken", account.refreshToken)
            addProperty("ClientSecret", "-")
            addProperty("UserID", "")
        }
        val conn = TraeHttp.post(
            url = host + TraeConstants.EP_EXCHANGE,
            body = body.toString(),
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = TraeHttp.READ_TIMEOUT_MS,
        ) { TraeHeaders.oauth(it, version) }

        val status = conn.responseCode
        val raw = TraeHttp.readBody(conn)
        if (status >= 400) {
            throw TraeHttpException(status, TraeErrors.fromStatus(status, raw), raw, TraeErrors.extractCode(raw))
        }

        val result = runCatching { JsonParser.parseString(raw).asJsonObject.objOrNull("Result") }.getOrNull()
            ?: throw TraeHttpException(status, TraeErrorKind.SERVER, "ExchangeToken 响应无法解析: ${raw.take(200)}")
        val token = result.str("Token")
        if (token.isEmpty()) {
            throw TraeHttpException(status, TraeErrorKind.SESSION_DEAD, "ExchangeToken 未返回 Token，需要重新登录")
        }

        return account.withTokens(
            accessToken = token,
            refreshToken = result.str("RefreshToken").ifEmpty { account.refreshToken },
            expiresAt = expiresAtOf(result.long("TokenExpireAt"), result.long("TokenExpireDuration"), nowSeconds()),
        )
    }

    /** 补全 / 刷新账号身份信息。 */
    fun getUserInfo(account: TraeAccount): TraeAccount {
        val host = authHost(account)
        val body = JsonObject().apply {
            addProperty("ReqSource", "IDE")
            addProperty("IDEVersion", version.ideVersion)
        }
        val conn = TraeHttp.post(
            url = host + TraeConstants.EP_USER_INFO,
            body = body.toString(),
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = TraeHttp.READ_TIMEOUT_MS,
        ) {
            TraeHeaders.oauth(it, version)
            it.setRequestProperty("X-Cloudide-Token", account.accessToken)
        }

        val status = conn.responseCode
        val raw = TraeHttp.readBody(conn)
        if (status >= 400) {
            throw TraeHttpException(status, TraeErrors.fromStatus(status, raw), raw, TraeErrors.extractCode(raw))
        }

        val result = runCatching { JsonParser.parseString(raw).asJsonObject.objOrNull("Result") }.getOrNull()
            ?: throw TraeHttpException(status, TraeErrorKind.SERVER, "GetUserInfo 响应无法解析: ${raw.take(200)}")

        return account.withIdentity(
            uid = result.str("UserID"),
            nickname = result.str("ScreenName"),
            enterpriseId = result.str("EnterpriseID"),
        )
    }

    /** 仅当 token 在 [withinSeconds] 内将过期（或已过期）时才刷新；返回 null 表示无需刷新。 */
    fun refreshIfNeeded(account: TraeAccount, withinSeconds: Long): TraeAccount? {
        if (!account.needsRefresh(withinSeconds, nowSeconds())) return null
        return exchangeToken(account)
    }

    companion object {
        /** 优先用 TokenExpireAt，其次 now + TokenExpireDuration，都没有则 0（视为未知）。 */
        fun expiresAtOf(tokenExpireAt: Long, tokenExpireDuration: Long, now: Long): Long = when {
            tokenExpireAt > 0L -> normalizeExpireAt(tokenExpireAt)
            tokenExpireDuration > 0L -> now + tokenExpireDuration
            else -> 0L
        }
    }
}
