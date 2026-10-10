package dev.aigw.core.provider.longcat

import com.google.gson.JsonObject
import dev.aigw.core.util.str

/**
 * LongCat 账号凭证。
 *
 * 登录态只有 Cookie 一种凭证（没有 refresh_token 机制），核心是 `passport_token_key`。
 * 免登录通道不需要账号，[cookie] 为空即代表走 oversea。
 */
data class LongCatAccount(
    /** 完整 Cookie 串，直接透传给上游。 */
    val cookie: String = "",
    /** 展示名（昵称或 Cookie 里的标识）。 */
    val nickname: String = "",
    /** 账号唯一号：Cookie 决定，用于账号池去重。 */
    val uid: String = "",
) {
    /** 无 Cookie = 免登录通道。 */
    val anonymous: Boolean get() = cookie.isBlank()

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("cookie", cookie)
        addProperty("nickname", nickname)
        addProperty("uid", uid)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun parse(raw: String): LongCatAccount? {
            if (raw.isBlank()) return null
            val obj = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
            if (obj != null) {
                return LongCatAccount(
                    cookie = obj.str("cookie"),
                    nickname = obj.str("nickname"),
                    uid = obj.str("uid"),
                )
            }
            return null
        }

        /** 从整段 Cookie 串里抽出 `passport_token_key` 作为稳定 uid。 */
        fun uidOf(cookie: String): String {
            for (part in cookie.split(';')) {
                val trimmed = part.trim()
                if (trimmed.startsWith("passport_token_key=")) {
                    val value = trimmed.substringAfter('=').trim()
                    if (value.isNotEmpty()) {
                        return value.take(32)
                    }
                }
            }
            // 没有主键 Cookie：退化成整串的稳定哈希，至少保证同串映射同一账号
            return "anon-" + cookie.hashCode().toUInt().toString(16)
        }
    }
}
