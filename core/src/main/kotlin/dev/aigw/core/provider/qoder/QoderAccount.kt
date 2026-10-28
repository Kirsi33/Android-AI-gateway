package dev.aigw.core.provider.qoder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Qoder 账号凭证。 */
data class QoderAccount(
    val uid: String,
    val accessToken: String,
    /** 设备授权拿到的 refresh（`deviceToken/refresh` 用）。 */
    val refreshToken: String,
    /** access token 到期时刻（Unix 秒），0 表示未知。 */
    val expiresAt: Long,
    /** `cn` / `global`。 */
    val realm: String,
    val nickname: String,
    /** 官方 user_type，如 `personal_professional_trial`。 */
    val userType: String,
    val organizationId: String,
    /**
     * 设备指纹（`cosy-machineid`）。COSY 签名要求同一账号稳定，
     * 每次登录重新生成会让服务端认成另一台设备。
     */
    val machineId: String,
) {
    val region: QoderRealm get() = QoderRealm.of(realm)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("accessToken", accessToken)
        addProperty("refreshToken", refreshToken)
        addProperty("expiresAt", expiresAt)
        addProperty("realm", realm)
        addProperty("nickname", nickname)
        addProperty("userType", userType)
        addProperty("organizationId", organizationId)
        addProperty("machineId", machineId)
    }

    fun withTokens(accessToken: String, refreshToken: String, expiresAt: Long): QoderAccount =
        copy(accessToken = accessToken, refreshToken = refreshToken.ifEmpty { this.refreshToken }, expiresAt = expiresAt)

    companion object {
        private val random = SecureRandom()

        fun parse(raw: String): QoderAccount {
            val root = JsonParser.parseString(raw).asJsonObject
            val accessToken = root.stringOrNull("accessToken").orEmpty()
            if (accessToken.isEmpty()) throw IllegalArgumentException("凭证缺少 accessToken")
            return QoderAccount(
                uid = root.stringOrNull("uid").orEmpty(),
                accessToken = accessToken,
                refreshToken = root.stringOrNull("refreshToken").orEmpty(),
                expiresAt = root.longOrNull("expiresAt") ?: 0L,
                realm = root.stringOrNull("realm").orEmpty().ifEmpty { QoderRealm.CN.id },
                nickname = root.stringOrNull("nickname").orEmpty(),
                userType = root.stringOrNull("userType").orEmpty(),
                organizationId = root.stringOrNull("organizationId").orEmpty(),
                machineId = root.stringOrNull("machineId").orEmpty().ifEmpty { newMachineId() },
            )
        }

        /** 32 字节 hex 的设备指纹（COSY 头 `cosy-machineid`）。 */
        fun newMachineId(): String {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** PKCE verifier：64 个 URL-safe 随机字符。 */
        fun newVerifier(): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            return (1..64).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        /** code_challenge = base64url(sha256(verifier))，去掉 padding。 */
        fun challengeOf(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        /** 国内用带横线的 uuid，国际用 32 位 hex。 */
        fun newNonce(dashed: Boolean): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return if (dashed) {
                hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
                    "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
            } else {
                hex
            }
        }
    }
}

/** 设备授权票据（本地保存 verifier/nonce，轮询要用）。 */
data class QoderLoginTicket(
    val state: String,
    val authUrl: String,
    val verifier: String,
    val nonce: String,
    val realm: QoderRealm,
    val createdAt: Long,
)

/** 轮询结果。 */
sealed interface QoderPollResult {
    /** 用户还没在浏览器里完成授权（上游 404 / 202）。 */
    data object Pending : QoderPollResult

    /** 授权窗口过期（本地 600 秒）。 */
    data object Expired : QoderPollResult

    data class Success(val account: QoderAccount) : QoderPollResult

    data class Failure(val message: String) : QoderPollResult
}

/** 额度快照。字段缺失记 -1，不用 0 冒充「没有」。 */
data class QoderQuota(
    val total: Long,
    val used: Long,
    val remaining: Long,
    val planName: String,
    val raw: String,
) {
    fun summary(): String = buildString {
        if (remaining >= 0) append("剩余 ").append(remaining)
        if (total >= 0) append(" / ").append(total)
        if (planName.isNotEmpty()) append("（").append(planName).append("）")
    }.ifEmpty { "额度未知" }
}
