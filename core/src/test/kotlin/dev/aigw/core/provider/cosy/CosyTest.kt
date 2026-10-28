package dev.aigw.core.provider.cosy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * COSY 签名回归。
 *
 * 测试向量全部来自 dumate2api 的 `test/cosy.test.js`（MIT），
 * 移植后必须逐字节一致，否则上游只会回一句「Signature invalid」。
 */
class CosyTest {

    @Test
    fun `自定义 base64 的重排与换表与参考实现一致`() {
        // 独立复算一遍参考实现的公式：new[i] = map(std[perm(i)])
        fun expected(plain: String): String {
            val std = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
            val n = std.length
            if (n == 0) return ""
            val a = n / 3
            val alphabet = "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!"
            val sb = StringBuilder()
            for (i in 0 until n) {
                val src = when {
                    i < a -> n - a + i
                    i < n - a -> i
                    else -> i - (n - a)
                }
                val ch = std[src]
                sb.append(if (ch == '=') '$' else alphabet["ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".indexOf(ch)])
            }
            return sb.toString()
        }

        for (plain in listOf("a", "ab", "abc", "abcd", "hello world!", "你好，COSY 签名测试。")) {
            assertEquals(expected(plain), Cosy.encode(plain), "plaintext: $plain")
        }
        assertEquals("", Cosy.encode(""))
    }

    @Test
    fun `自定义 base64 输出只含字母表字符`() {
        val legal = ("_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!" + "$").toSet()
        for (plain in listOf("payload-body", "x".repeat(999))) {
            for (ch in Cosy.encode(plain)) assertTrue(ch in legal, "非法字符: $ch")
        }
    }

    @Test
    fun `AES 手工补位后长度是 16 的整数倍`() {
        val key = "0123456789abcdef"
        // 3 字节 → 一个 block
        assertEquals(16, Cosy.aesEncrypt("abc".toByteArray(), key).size)
        // 正好 16 字节 → 再补一整块（这是「关掉自动补位」的判据）
        assertEquals(32, Cosy.aesEncrypt("a".repeat(16).toByteArray(), key).size)
    }

    @Test
    fun `AES 密文可以用 NoPadding 解回原文`() {
        val key = "0123456789abcdef"
        for (plain in listOf("hello world", "x".repeat(16), "长度不是块整数倍的字节串!")) {
            val enc = Cosy.aesEncrypt(plain.toByteArray(), key)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(), "AES"),
                IvParameterSpec(key.toByteArray().copyOfRange(0, 16)),
            )
            val dec = cipher.doFinal(enc)
            val pad = dec.last().toInt() and 0xFF
            assertEquals(plain, String(dec.copyOfRange(0, dec.size - pad)))
        }
    }

    @Test
    fun `身份 JSON 按键字母序输出`() {
        // 大写 ASCII 在小写前：A(65) < z(122)
        assertEquals("""{"A":"2","b":"1","c":"3"}""", Cosy.sortedJson(linkedMapOf("b" to "1", "a" to "2", "c" to "3", "A" to "2")))
        assertEquals("{}", Cosy.sortedJson(emptyMap()))
        // null 值直接省略（避免把 "null" 当字符串发上去）
        assertEquals("""{"a":"1"}""", Cosy.sortedJson(mapOf("a" to "1", "z" to null)))
    }

    @Test
    fun `md5 与已知向量一致`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Cosy.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Cosy.md5Hex(""))
    }

    @Test
    fun `签名是五段换行拼接后 md5 且确定性`() {
        val sig1 = Cosy.signRequest("p", "k", "2026-10-25", "body", "pathsig")
        val sig2 = Cosy.signRequest("p", "k", "2026-10-25", "body", "pathsig")
        assertEquals(sig1, sig2)
        assertEquals(32, sig1.length)
        assertEquals(Cosy.md5Hex("p\nk\n2026-10-25\nbody\npathsig"), sig1)
        // 任一段变化都必须改变签名
        assertTrue(sig1 != Cosy.signRequest("p", "k", "2026-10-26", "body", "pathsig"))
    }

    @Test
    fun `RSA 加密产出 128 字节且每次不同`() {
        // RSA-1024 + PKCS#1 v1.5 填充带随机数：密文定长、两次不同
        val a = Cosy.rsaEncrypt("0123456789abcdef")
        val b = Cosy.rsaEncrypt("0123456789abcdef")
        assertEquals(128, a.size)
        assertTrue(!a.contentEquals(b), "PKCS#1 v1.5 带随机填充，两次密文必须不同")
    }

    @Test
    fun `会话材料长度与格式`() {
        val session = Cosy.newSession("""{"clientId":"x"}""")
        assertEquals(16, session.tempKey.length)
        assertTrue(session.tempKey.all { it in "0123456789abcdef" })
        assertTrue(session.cosyKey.isNotEmpty())
        assertTrue(session.info.isNotEmpty())
        // payload 里的 requestId 每次都要变
        val p1 = Cosy.buildPayloadB64(session.info, "1.0.10")
        val p2 = Cosy.buildPayloadB64(session.info, "1.0.10")
        assertTrue(p1 != p2, "requestId 必须每次重新生成")
    }
}
