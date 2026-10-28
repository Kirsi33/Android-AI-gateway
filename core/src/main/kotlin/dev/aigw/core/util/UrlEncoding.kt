package dev.aigw.core.util

/**
 * 百分号编码（RFC 3986 unreserved 保留）。
 *
 * **不能用 [java.net.URLEncoder] 代替**：它把 `~` 编成 `%7E`、把空格编成 `+`。
 * PKCE 的 verifier 字母表里就有 `~` 和 `.`，一旦被编码、而服务端按原始串比对，
 * 设备授权的 nonce/verifier 就永远对不上——表现是**轮询永远 pending**，
 * 用户只看到「等 10 分钟然后超时」，完全看不出是编码问题。
 */
fun percentEncode(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        val unreserved = (code in 0x30..0x39) || // 0-9
            (code in 0x41..0x5A) || // A-Z
            (code in 0x61..0x7A) || // a-z
            ch == '-' || ch == '_' || ch == '.' || ch == '~'
        if (unreserved) append(ch) else append('%').append("%02X".format(code))
    }
}
