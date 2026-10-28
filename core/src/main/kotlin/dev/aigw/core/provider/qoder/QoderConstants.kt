package dev.aigw.core.provider.qoder

/**
 * Qoder 双区配置。
 *
 * 国内 `qoder.com.cn` 与国际 `qoder.sh` 是两套独立部署：client_id、授权页域名、
 * 推理网关都不通用。拿国内的 client_id 去国际版做设备授权会直接失败。
 *
 * 逆向来源：shuishuipingan/qoder2api-hub `qoder_accounts.py:40-90`（435★），
 * 与 Zhengyuuuui/qoder2api（Go）交叉验证一致。
 */
enum class QoderRealm(
    val id: String,
    val label: String,
    /** 业务 REST（userinfo / 额度 / 签到）基址。 */
    val openapi: String,
    /** 推理网关（对话走 COSY 签名）。 */
    val gateway: String,
    /** 授权页域名。 */
    val website: String,
    val clientId: String,
    val redirectUri: String,
    val userAgent: String,
    /** nonce 用带横线的 uuid（国内）还是 32 位 hex（国际）。 */
    val dashedNonce: Boolean,
    /** 设备授权 URL 是否带 redirect_uri（国际版新协议不带）。 */
    val sendRedirectUri: Boolean,
    /** 该区曾经有 /sash 每日签到（仅作提示，能力运行时探测）。 */
    val checkinHint: Boolean,
) {
    CN(
        id = "cn",
        label = "国内版",
        openapi = "https://openapi.qoder.com.cn",
        gateway = "https://gateway.qoder.com.cn",
        website = "https://qoder.com.cn",
        // dumate2api `src/qoder/constants.js:22` 实测：国内与国际**同一个 client_id**
        // （与千问办公也是同一个，同一平台）。Python 版里的 1c5e33e1-… 是另一条产品线的值。
        clientId = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb",
        redirectUri = "qoder-work-cn://",
        userAgent = "QoderWork/1.1.75",
        dashedNonce = true,
        sendRedirectUri = true,
        checkinHint = true,
    ),
    INTL(
        id = "global",
        label = "国际版",
        openapi = "https://openapi.qoder.sh",
        // 官方 0.4.8 的 endpoint 缓存：推理主选 api3，api2/api1 为故障切换
        gateway = "https://api3.qoder.sh",
        website = "https://qoder.com",
        clientId = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb",
        redirectUri = "",
        userAgent = "Qoder/1.1.75",
        dashedNonce = false,
        sendRedirectUri = false,
        checkinHint = false,
    ),
    ;

    /** 推理网关的备用域名（主域名连不上时按序试）。 */
    val gatewayFallbacks: List<String>
        get() = if (this == INTL) listOf("https://api2.qoder.sh", "https://api1.qoder.sh") else emptyList()

    companion object {
        fun of(id: String?): QoderRealm =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: CN

        /** 从凭证里的域名反查区域。 */
        fun infer(domain: String?): QoderRealm {
            val value = domain?.trim()?.lowercase().orEmpty()
            return when {
                value.contains("qoder.sh") -> INTL
                value.contains("qoder.com") -> CN
                value == INTL.id -> INTL
                else -> CN
            }
        }
    }
}

/** Qoder 协议常量。改这里之前先看 research/qoder-spec.md。 */
object QoderConstants {
    /**
     * COSY 签名版本：**Qoder 是 1.0.10，千问办公是 1.1.52**，两者不同。
     * 取 dumate2api `src/qoder/constants.js:18`（实测值），
     * 不要用 Python 版 qoder2api-hub 的 1.1.75——那是另一条产品线的版本号。
     */
    const val COSY_VERSION = "1.0.10"

    /** Qoder 的客户端身份三元组（`src/qoder/cosy.js:56-76` 的固定头）。 */
    const val CLIENT_TYPE = "5"
    const val SCENE = "assistant"
    const val BUSINESS_PRODUCT = "cli"
    const val BUSINESS_TYPE = "agent"
    const val LOGIN_VERSION = "v2"
    const val MACHINE_TYPE = "5"
    const val UA = "Go-http-client/2.0"

    // ---- 业务 REST（纯 Bearer，不走 COSY）----
    const val PATH_USER_INFO = "/api/v1/userinfo"
    const val PATH_QUOTA = "/api/v2/quota/usage"
    const val PATH_PLAN = "/api/v2/user/plan"
    const val PATH_CHECKIN_STATUS = "/sash/api/v1/me/daily-check-in/status"
    const val PATH_CHECKIN_CLAIM = "/sash/api/v1/me/daily-check-in/claim"
    const val PATH_CAMPAIGNS = "/sash/api/v1/me/campaigns"

    // ---- 设备授权（PKCE S256）----
    const val PATH_DEVICE_POLL = "/api/v1/deviceToken/poll"
    const val PATH_DEVICE_REFRESH = "/api/v1/deviceToken/refresh"
    const val DEVICE_AUTH_PAGE = "/device/selectAccounts"

    /** 授权窗口：官方给 600 秒。 */
    const val LOGIN_TTL_MS = 600_000L

    // ---- 推理（COSY）----
    const val MODELS_PATH = "/algo/api/v2/model/list?Encode=1"
    const val CHAT_PATH =
        "/algo/api/v2/service/pro/sse/agent_chat_generation" +
            "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1"

    /**
     * 无账号时的兜底模型表。
     *
     * `pro` 用于重创作与长任务；`flash` 会「播报进度就收尾」，不适合要完整产出的请求；
     * `gfmodel` 一类是 0.1 倍率的调试档。真实目录以 `model/list` 为准。
     */
    val FALLBACK_MODELS = listOf("pro", "flash", "standard", "gfmodel")

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 120_000
}
