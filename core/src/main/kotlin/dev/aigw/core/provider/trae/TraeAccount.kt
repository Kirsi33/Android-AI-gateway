package dev.aigw.core.provider.trae

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull

/**
 * 一个 Trae 账号的凭证。
 *
 * accessToken 即 `Cloud-IDE-JWT` 头；refreshToken 每次 ExchangeToken 都会轮换，
 * 因此刷新成功后必须原子写回磁盘。
 */
data class TraeAccount(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    /** accessToken 过期时刻（Unix 秒），0 表示未知。 */
    val expiresAt: Long,
    val nickname: String,
    val enterpriseId: String,
    val machineId: String,
    val deviceId: String,
    val domain: String = TraeConstants.REGION_CN,
    /** 鉴权 host；空串时由 [TraeAuthClient] 按区域补上该区域的 OAuth host。 */
    val apiHost: String = "",
) {
    fun needsRefresh(withinSeconds: Long, nowSeconds: Long): Boolean =
        expiresAt <= 0L || nowSeconds + withinSeconds >= expiresAt

    fun withTokens(accessToken: String, refreshToken: String, expiresAt: Long): TraeAccount =
        copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)

    fun withIdentity(uid: String, nickname: String, enterpriseId: String): TraeAccount =
        copy(
            uid = uid.ifEmpty { this.uid },
            nickname = nickname.ifEmpty { this.nickname },
            enterpriseId = enterpriseId.ifEmpty { this.enterpriseId },
        )

    /** 落盘格式（嵌套形，与登录脚本产出保持一致）。 */
    fun toJson(): JsonObject {
        val auth = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("refreshToken", refreshToken)
            addProperty("expiresAt", expiresAt)
            addProperty("domain", domain)
            addProperty("apiHost", apiHost)
            addProperty("machineId", machineId)
            addProperty("deviceId", deviceId)
        }
        val account = JsonObject().apply {
            addProperty("uid", uid)
            addProperty("enterpriseId", enterpriseId)
            addProperty("nickname", nickname)
        }
        return JsonObject().apply {
            add("auth", auth)
            add("account", account)
        }
    }

    companion object {
        /** 兼容嵌套形 `{auth:{...},account:{...}}` 与扁平形 `{accessToken:...,uid:...}`。 */
        fun parse(raw: String): TraeAccount {
            val root = JsonParser.parseString(raw).asJsonObject
            val nested = root.has("auth")
            val auth = if (nested) root.objOrNull("auth") ?: JsonObject() else root
            val account = if (nested) root.objOrNull("account") ?: JsonObject() else root

            // 归一化取值：accessToken / access_token / token / Token 都认，
            // refreshToken / refresh_token / rt 都认。用户从不同地方导出的 JSON 字段名不一致，
            // 之前只认驼峰，粘进来就报「两把都没有」。
            val accessToken = auth.pick("accesstoken", "token", "jwt", "cloudidejwt")
            val refreshToken = auth.pick("refreshtoken", "rt")
            // 只要求「两把里有一把」：国际版登录回调有时只给 refreshToken（accessToken 靠
            // ExchangeToken 换），旧逻辑在这里直接抛「凭证缺少 accessToken」，
            // 用户看到的就是「登录成功但没有 accessToken」，账号根本进不了池子。
            // 兜底：官方客户端的 storage.json / 网页 localStorage 导出是嵌套结构，
            // token 藏在很深的键里（如 trae.token / auth.credentials.accessToken）。
            // 全树扫一遍按字段名找，只在浅层拿不到时才用。
            val deepAccess = if (accessToken.isEmpty()) deepPick(root, ACCESS_KEYS) else ""
            val deepRefresh = if (refreshToken.isEmpty()) deepPick(root, REFRESH_KEYS) else ""
            val finalAccess = accessToken.ifEmpty { deepAccess }
            val finalRefresh = refreshToken.ifEmpty { deepRefresh }
            if (finalAccess.isEmpty() && finalRefresh.isEmpty()) {
                throw IllegalArgumentException(
                    "凭证里没有可识别的 token（顶层字段：${root.keySet().joinToString(",").ifEmpty { "空对象" }}）。" +
                        "需要 accessToken 或 refreshToken 之一。",
                )
            }

            return TraeAccount(
                uid = account.str("uid"),
                accessToken = finalAccess,
                refreshToken = finalRefresh,
                expiresAt = auth.long("expiresAt"),
                nickname = account.str("nickname"),
                enterpriseId = account.str("enterpriseId"),
                machineId = auth.str("machineId"),
                deviceId = auth.str("deviceId"),
                domain = auth.str("domain").ifEmpty { TraeConstants.REGION_CN },
                apiHost = auth.str("apiHost"),
            )
        }
    }
}

/** 深度找 token 时认的字段名（归一化后）。 */
private val ACCESS_KEYS = setOf("accesstoken", "token", "jwt", "cloudidejwt", "idtoken")
private val REFRESH_KEYS = setOf("refreshtoken", "refresh", "rt")

/**
 * 深度优先找第一个「键名命中且值是字符串」的字段。
 *
 * 用来吃官方客户端 storage.json / 网页 localStorage 导出——结构层层嵌套，
 * 不知道具体路径，只能按字段名扫。
 */
internal fun deepPick(node: com.google.gson.JsonElement?, keys: Set<String>, depth: Int = 0): String {
    if (node == null || depth > 8) return ""
    if (node.isJsonObject) {
        // 先看本层，命中就返回（越浅越可信）
        for ((k, v) in node.asJsonObject.entrySet()) {
            val norm = k.lowercase().replace("_", "").replace("-", "")
            if (norm in keys && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                val text = v.asString
                if (text.length >= 12) return text
            }
        }
        for ((_, v) in node.asJsonObject.entrySet()) {
            val found = deepPick(v, keys, depth + 1)
            if (found.isNotEmpty()) return found
        }
    } else if (node.isJsonArray) {
        for (item in node.asJsonArray) {
            val found = deepPick(item, keys, depth + 1)
            if (found.isNotEmpty()) return found
        }
    }
    return ""
}

/** 按归一化键名（小写、去掉 _ 和 -）在对象里取字符串值。 */
internal fun JsonObject.pick(vararg normalizedKeys: String): String {
    for ((k, v) in entrySet()) {
        val norm = k.lowercase().replace("_", "").replace("-", "")
        if (norm in normalizedKeys && v.isJsonPrimitive) return v.asString
    }
    return ""
}

internal fun JsonObject.str(key: String): String {
    val v = get(key) ?: return ""
    return if (v.isJsonNull) "" else v.asString
}

internal fun JsonObject.long(key: String): Long {
    val v = get(key) ?: return 0L
    if (v.isJsonNull) return 0L
    return runCatching { v.asLong }.getOrDefault(0L)
}

internal fun JsonObject.obj(key: String): JsonObject? {
    val v = get(key) ?: return null
    return if (v.isJsonObject) v.asJsonObject else null
}
