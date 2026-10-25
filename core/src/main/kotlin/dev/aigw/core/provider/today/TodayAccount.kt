package dev.aigw.core.provider.today

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * Today 账号凭证。
 *
 * 只有一种凭证：**session token**（32 位十六进制串）。上游拿它换 115 秒有效的 ES256 JWT，
 * 所以 token 本身长期有效，不存在 refresh_token 轮换，也不需要 Cookie。
 *
 * 兼容三种导入形态（用户从网页/抓包/内嵌登录拿到的可能是任意一种）：
 *  - `{"token":"..."}`  —— 标准
 *  - 裸 token 串        —— 直接粘贴
 *  - `{"cookie":"..."}` —— 内嵌 WebView 登录回传的整段 Cookie，从里面抠 token
 */
data class TodayAccount(
    val token: String,
    val nickname: String = "",
    /** 账号唯一号：token 前 16 位（token 本身不该进日志）。 */
    val uid: String = "",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("token", token)
        addProperty("nickname", nickname)
        addProperty("uid", effectiveUid())
    }

    fun toJsonString(): String = toJson().toString()

    fun effectiveUid(): String = uid.ifEmpty { uidOf(token) }

    fun effectiveNickname(): String =
        nickname.ifEmpty { "Today ${token.take(6)}" }

    companion object {
        private val TOKEN_RE = Regex("[0-9a-fA-F]{32}")

        fun parse(raw: String): TodayAccount? {
            if (raw.isBlank()) return null
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            if (obj != null) {
                val direct = obj.str("token").ifEmpty { obj.str("session") }
                if (direct.isNotEmpty()) {
                    return TodayAccount(
                        token = direct,
                        nickname = obj.str("nickname"),
                        uid = obj.str("uid").ifEmpty { uidOf(direct) },
                    )
                }
                val cookie = obj.str("cookie")
                if (cookie.isNotEmpty()) {
                    val fromCookie = tokenFromCookie(cookie)
                    if (fromCookie.isNotEmpty()) {
                        return TodayAccount(
                            token = fromCookie,
                            nickname = obj.str("nickname"),
                            uid = obj.str("uid").ifEmpty { uidOf(fromCookie) },
                        )
                    }
                }
                return null
            }
            // 裸 token
            val bare = raw.trim().removeSurrounding("\"")
            return if (TOKEN_RE.matches(bare)) {
                TodayAccount(token = bare, uid = uidOf(bare))
            } else {
                null
            }
        }

        /**
         * 从整段 Cookie 里找 32 位 token。
         *
         * 上游没有固定 cookie 名（换过域名与登录态命名），所以按「值形似 token」匹配，
         * 优先取名字里带 token/session 的那一条。
         */
        fun tokenFromCookie(cookie: String): String {
            var best = ""
            for (part in cookie.split(';')) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val name = trimmed.substringBefore('=', "").lowercase()
                val value = trimmed.substringAfter('=', "").trim()
                if (!TOKEN_RE.matches(value)) continue
                if (name.contains("token") || name.contains("session")) return value
                if (best.isEmpty()) best = value
            }
            return best
        }

        /** 稳定 uid：token 的前 16 位，足够区分账号又不至于把完整凭证写进日志。 */
        fun uidOf(token: String): String =
            if (token.length >= 16) "today-" + token.take(16) else "today-" + token.hashCode().toUInt().toString(16)
    }
}
