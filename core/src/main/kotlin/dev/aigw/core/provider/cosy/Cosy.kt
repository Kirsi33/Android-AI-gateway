package dev.aigw.core.provider.cosy

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * COSY 协议签名（Qoder 与千问办公**共用同一套**）。
 *
 * 移植自 dumate2api `src/cosy.js`（MIT）。三步流程：
 *
 * ```
 * 1. cosyKey = base64( RSA_PKCS1_v1.5( tempKey ) )        // 服务端公钥硬编码
 * 2. info    = base64( AES-128-CBC( 身份JSON, tempKey ) )  // IV = tempKey[0:16]，PKCS#7 手补
 * 3. 每次请求：
 *      payloadB64 = base64({cosyVersion, ideVersion, info, requestId, version:"v1"})
 *      sig        = md5( payloadB64 \n cosyKey \n date \n body \n pathSig )
 *      Authorization: Bearer COSY.<payloadB64>.<sig>
 * ```
 *
 * 两个「签名无效但很难自查」的坑（原实现踩过，这里都锁住）：
 * 1. **身份 JSON 的键必须字母序**——服务端按 Go 的 map 序列化校验，
 *    `JSON.stringify` 的插入顺序会让 AES 密文不同，签名随之不同。
 * 2. **AES 必须关自动补位**——本算法手工做 PKCS#7，再让 JCE 补一次就多一个 block。
 */
object Cosy {

    /** 服务端公钥（RSA-1024，PKCS#1 v1.5）。协议常量，不是用户凭证。 */
    private const val SERVER_PUBKEY_DER_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc" +
            "4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l" +
            "6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17" +
            "XcW+ML9FoCI6AOvOzwIDAQAB"

    /** Encode=1 的自定义字母表（标准 base64 字母表的置换）。 */
    private const val CUSTOM_ALPHABET = "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!"

    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private const val CUSTOM_PAD = '$'

    /** std 字符 → custom 字符（'=' 映射成 '$'）。 */
    private val S2C: IntArray = IntArray(256).also { table ->
        for (i in 0 until 64) table[STD_ALPHABET[i].code] = CUSTOM_ALPHABET[i].code
        table['='.code] = CUSTOM_PAD.code
    }

    private val random = SecureRandom()

    /** 一次会话的三把材料。 */
    data class Session(val tempKey: String, val cosyKey: String, val info: String)

    /**
     * 自定义 base64：先标准 base64，再按 1/3 处重排，最后换字母表。**顺序不能改**。
     *
     * 重排规则（原实现注释）：`rearranged = std[n-a:] + std[a:n-a] + std[0:a]`，`a = n/3`。
     * 为了 O(n)，这里直接按下标算源位置，不做三次字符串切片。
     */
    fun encode(plaintext: ByteArray): String {
        if (plaintext.isEmpty()) return ""
        val std = Base64.getEncoder().encodeToString(plaintext)
        val n = std.length
        val a = n / 3
        val out = CharArray(n)
        for (i in 0 until n) {
            val src = when {
                i < a -> n - a + i
                i < n - a -> i
                else -> i - (n - a)
            }
            val mapped = S2C[std[src].code]
            require(mapped != 0) { "cosy: 字符不在字母表内 (${std[src].code})" }
            out[i] = mapped.toChar()
        }
        return String(out)
    }

    fun encode(plaintext: String): String = encode(plaintext.toByteArray(Charsets.UTF_8))

    /**
     * AES-128-CBC + PKCS#7 手补，IV = key 前 16 字节。
     *
     * 用 `AES/CBC/NoPadding` 而不是 `PKCS5Padding`：后者会自动再补一次，
     * 密文多一个 block，签名必错。
     */
    fun aesEncrypt(plain: ByteArray, key: String): ByteArray {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        require(keyBytes.size >= 16) { "cosy: tempKey 至少 16 字节" }
        val blockSize = 16
        val pad = blockSize - (plain.size % blockSize)
        val padded = ByteArray(plain.size + pad)
        plain.copyInto(padded)
        for (i in plain.size until padded.size) padded[i] = pad.toByte()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes.copyOfRange(0, 16), "AES"),
            IvParameterSpec(keyBytes.copyOfRange(0, 16)),
        )
        return cipher.doFinal(padded)
    }

    /** RSA PKCS#1 v1.5 加密（等价 Go 的 `rsa.EncryptPKCS1v15`）。 */
    fun rsaEncrypt(plaintext: String): ByteArray {
        val spec = X509EncodedKeySpec(Base64.getDecoder().decode(SERVER_PUBKEY_DER_B64))
        val key = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    /**
     * 生成一次会话材料。
     *
     * tempKey 是 16 字节随机数的 **hex 前 16 个字符**（即 16 个 ASCII 字节），
     * 不是 16 字节二进制——必须与参考实现一致，否则服务端解不开。
     */
    fun newSession(identityJson: String): Session {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val tempKey = hex.substring(0, 16)
        val cosyKey = Base64.getEncoder().encodeToString(rsaEncrypt(tempKey))
        val info = Base64.getEncoder().encodeToString(aesEncrypt(identityJson.toByteArray(Charsets.UTF_8), tempKey))
        return Session(tempKey, cosyKey, info)
    }

    /** 每次请求都要重建 payload：`requestId` 必须新。 */
    fun buildPayloadB64(info: String, cosyVersion: String): String {
        val json = """{"cosyVersion":"$cosyVersion","ideVersion":"","info":"$info",""" +
            """"requestId":"${newUuid()}","version":"v1"}"""
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** 五段以 `\n` 连接后 md5。 */
    fun signRequest(payloadB64: String, cosyKey: String, date: String, body: String, pathSig: String): String =
        md5Hex(listOf(payloadB64, cosyKey, date, body, pathSig).joinToString("\n"))

    fun composeBearer(payloadB64: String, sig: String): String = "Bearer COSY.$payloadB64.$sig"

    /**
     * 身份 JSON：键必须**字母序**（ASCII 序，大写在小写前）。
     *
     * 用 Map 手动拼而不用 Gson：Gson 保留插入顺序，而服务端按 Go 的 map 序列化校验。
     */
    fun sortedJson(pairs: Map<String, String?>): String {
        val sb = StringBuilder("{")
        var first = true
        for (key in pairs.keys.sorted()) {
            val value = pairs[key] ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
        }
        return sb.append('}').toString()
    }

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 32 位随机 UUID（v4 形状）。 */
    fun newUuid(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
            hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
