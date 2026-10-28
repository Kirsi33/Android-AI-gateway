package dev.aigw.core.provider.trae

import dev.aigw.core.util.long
import dev.aigw.core.util.randomHex
import dev.aigw.core.util.str
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/** 登录回调解析结果。 */
data class CallbackAccount(
    val refreshToken: String,
    val accessToken: String,
    val uid: String,
    val nickname: String,
    val enterpriseId: String,
    val expiresAt: Long,
)

/**
 * 登录 URL 构造与回调解析。
 *
 * Trae 登录页只会回调到 `127.0.0.1`，所以应用内要自己监听该端口接回调。
 * 回调不回传 machine_id/device_id，只回传派生的 login_trace_id，因此两者要用
 * [machineTraceId] 一一对应反查。
 */
object TraeLogin {

    private val random = SecureRandom()

    /**
     * 生成官方客户端格式的 device id：**纯数字**。
     *
     * 官方客户端的 `x-device-id` 是首次绑定时随机产生的数字 ID（非 hex）。
     * 用 hex 会被 UG 接口的设备校验拒绝，签到报 9074。
     */
    fun randomDeviceId(): String {
        val value = random.nextLong().let { if (it < 0) -it else it }
        return "%015d".format(value % 900_000_000_000_000L + 100_000_000_000_000L)
    }

    /** 机器码是 64 位 hex（32 字节）。 */
    fun newMachineId(): String = randomHex(32)

    /** 判断一个值像不像官方客户端的 device id（纯数字、长度合理）。 */
    fun looksLikeDeviceId(value: String): Boolean =
        value.length in 14..20 && value.all { it.isDigit() }

    /** machineId + deviceId 派生出的 login_trace_id（取拼接尾部 16 位）。 */
    fun machineTraceId(machineId: String, deviceId: String): String {
        val joined = machineId + deviceId
        if (joined.length >= 16) return joined.substring(joined.length - 16)
        return "0".repeat(16 - joined.length) + joined
    }

    /** 构造授权地址。参数集与 Trae 客户端一致，缺项会导致回调异常。 */
    fun buildLoginUrl(
        machineId: String,
        deviceId: String,
        callbackUrl: String,
        pluginVersion: String = TraeVersion().pluginVersion,
        region: TraeRegion = TraeRegion.CN,
    ): String {
        // 与 Go 的 url.Values.Encode() 一致：键按字典序排列
        val base = linkedMapOf(
            "auth_from" to "solo",
            "auth_type" to "local",
            "client_id" to region.oauthClientId,
            "login_channel" to "native_ide",
            "login_version" to "1",
            "plugin_version" to pluginVersion,
            "redirect" to "0",
        ).toSortedMap().entries.joinToString("&") { (k, v) -> "$k=" + enc(v) }

        // 国际版前端用 x_app_type=trae（国内版是 stable）；实测两版对同参数都能渲染，
        // 但按各自发行版的口径填更稳。
        val appType = if (region == TraeRegion.INTL) "trae" else "stable"
        val osVersion = if (region == TraeRegion.INTL) "Windows 11 Pro" else "1.0"

        val tail = listOf(
            "login_trace_id" to machineTraceId(machineId, deviceId),
            "auth_callback_url" to callbackUrl,
            "machine_id" to machineId,
            "device_id" to deviceId,
            "x_device_id" to deviceId,
            "x_machine_id" to machineId,
            "x_device_brand" to "PC",
            "x_device_type" to "PC",
            "x_os_version" to osVersion,
            "x_app_version" to pluginVersion,
            "x_app_type" to appType,
        ).joinToString("&") { (k, v) -> "$k=" + enc(v) }

        return region.consoleHost + "/authorization?" + base + "&" + tail
    }

    /**
     * 解析回调链接。refreshToken 优先；缺失时回退 `userJwt.RefreshToken`，
     * 再缺失则用 `userJwt.Token` 作为 accessToken。
     */
    fun parseCallback(rawUrl: String): CallbackAccount {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "回调链接为空" }
        val query = trimmed.substringAfter('?', "").ifEmpty { trimmed }
        val params = parseQuery(query)

        // 国际版与国内版的回调参数名不完全一致（大小写、下划线、甚至只给 access_token），
        // 这里一律按「归一化键名」取值：小写并去掉 _ 和 -。拿不到就把收到的参数名报出来，
        // 免得用户只能看到一句「凭证为空的」而无从下手。
        val refreshToken = paramValue(params, "refreshtoken")
        val userInfo = parseJsonParam(paramValue(params, "userinfo"))
        val userJwt = parseJsonParam(paramValue(params, "userjwt"))

        var effectiveRefresh = refreshToken
        if (effectiveRefresh.isEmpty()) {
            effectiveRefresh = jsonValue(userJwt, "refreshtoken")
        }

        var accessToken = ""
        var expiresAt = 0L
        if (effectiveRefresh.isEmpty()) {
            accessToken = jsonValue(userJwt, "token").ifEmpty { paramValue(params, "accesstoken") }
            if (accessToken.isEmpty()) {
                // 只报参数名，不报值：既能定位问题，也不会把凭证打进日志/截图
                val keys = params.keys.joinToString(",").ifEmpty { "（无查询参数）" }
                require(false) {
                    "回调里没有 refreshToken / accessToken（收到参数：$keys）"
                }
            }
            val expireAt = userJwt?.long("TokenExpireAt") ?: 0L
            if (expireAt > 0L) expiresAt = normalizeExpireAt(expireAt)
        }

        val uid = jsonValue(userInfo, "userid")
        return CallbackAccount(
            refreshToken = effectiveRefresh,
            accessToken = accessToken,
            uid = uid,
            nickname = cleanNickname(userInfo?.str("ScreenName").orEmpty(), uid),
            // 注意回调里的字段名是 TenantID，不是 EnterpriseID
            enterpriseId = userInfo?.str("TenantID").orEmpty(),
            expiresAt = expiresAt,
        )
    }

    /** 中文昵称在回调里可能被双重 URL 编码，这里退编码并做一次乱码回转。 */
    fun cleanNickname(raw: String, uid: String): String {
        if (raw.isEmpty()) return fallbackNickname(uid)
        var value = raw
        // 还含 %xx 说明被编码了两层，再解一层
        if (value.contains('%')) {
            value = try {
                URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                value
            }
        }
        value = fixMojibake(value)
        if (value.isEmpty() || value.contains('\uFFFD')) return fallbackNickname(uid)
        return value
    }

    /** 把「UTF-8 字节被当成 Latin-1 读入」的乱码还原；不成立时原样返回。 */
    fun fixMojibake(value: String): String {
        if (value.isEmpty()) return value
        val bytes = ByteArray(value.length)
        for (i in value.indices) {
            val code = value[i].code
            if (code > 0xFF) return value
            bytes[i] = code.toByte()
        }
        return try {
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.contains('\uFFFD')) value else decoded
        } catch (_: Exception) {
            value
        }
    }

    private fun fallbackNickname(uid: String): String =
        if (uid.length >= 4) "用户" + uid.substring(uid.length - 4) else "用户"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = if (index < 0) pair else pair.substring(0, index)
            val value = if (index < 0) "" else pair.substring(index + 1)
            out[dec(key)] = dec(value)
        }
        return out
    }

    private fun dec(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    /** 按归一化键名取查询参数（忽略大小写与下划线/连字符）。 */
    private fun paramValue(params: Map<String, String>, normalizedKey: String): String {
        params[normalizedKey]?.let { return it }
        for ((k, v) in params) {
            if (k.lowercase().replace("_", "").replace("-", "") == normalizedKey) return v
        }
        return ""
    }

    /** 按归一化键名取 JSON 字段，值必须是字符串或数字（对象/数组忽略）。 */
    private fun jsonValue(obj: com.google.gson.JsonObject?, normalizedKey: String): String {
        if (obj == null) return ""
        for ((k, v) in obj.entrySet()) {
            if (k.lowercase().replace("_", "").replace("-", "") != normalizedKey) continue
            if (v.isJsonPrimitive) return v.asString
        }
        return ""
    }

    /**
     * 回调里的 JSON 参数可能被 URL 编码、也可能被 base64 包一层（国际版实测出现过），
     * 三种形态都试一遍，解析不出来就当没有。
     */
    private fun parseJsonParam(raw: String): com.google.gson.JsonObject? {
        if (raw.isEmpty()) return null
        val candidates = listOf(
            raw,
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw),
            runCatching { String(java.util.Base64.getUrlDecoder().decode(raw), Charsets.UTF_8) }
                .getOrElse { runCatching { String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8) }
                    .getOrDefault("") },
        )
        for (text in candidates) {
            if (text.isEmpty() || !text.trimStart().startsWith("{")) continue
            val obj = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (obj != null) return obj
        }
        return null
    }
}
