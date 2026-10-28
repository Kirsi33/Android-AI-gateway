package dev.aigw.core.provider.qwenwork

/**
 * 千问办公（QwenWork）协议常量。
 *
 * 与 Qoder **同一套协议栈**：同一个 client_id、同一个聊天路径、同一份 COSY 签名、
 * 连服务端公钥都是同一把。差异只在下面这些常量与身份 JSON 的字段名。
 *
 * 实测来源：dumate2api `src/qwenwork/constants.js`（2026-09-23 直连验证）
 * 与 `cosy-sign.js` 文件头（2026-10-14 确认**不需要官方 wasm**，纯本地签名即可）。
 */
object QwenConstants {
    const val ID = "qwenwork"

    /** 全部业务与推理都在这一个域名下（额度查询除外，那条在 qwenwork.cn）。 */
    const val GATEWAY = "https://gateway.qwenwork.cn"

    /** 积分余额在站点域，不在 gateway——两者不可互换。 */
    const val SITE_ORIGIN = "https://qwenwork.cn"

    // ---- 推理 ----
    const val CHAT_PATH = "/algo/api/v2/service/pro/sse/agent_chat_generation"
    /** Encode=1 是硬要求：缺了 400。 */
    const val CHAT_QUERY = "FetchKeys=llm_model_result&AgentId=agent_common&Encode=1"
    const val CHAT_PATH_SIG = "/api/v2/service/pro/sse/agent_chat_generation"

    // ---- 设备授权 ----
    const val DEVICE_SELECT_PATH = "/device/selectAccounts"
    const val DEVICE_POLL_PATH = "/api/v1/deviceToken/poll"
    const val REFRESH_PATH = "/api/v1/deviceToken/refresh"
    const val OAUTH_CLIENT_ID = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb"
    const val OAUTH_REDIRECT_URI = "qwenwork-cn://"
    /** 官方客户端轮询窗口就是 5 分钟，超时 nonce 作废。 */
    const val DEVICE_FLOW_TIMEOUT_MS = 300_000L

    // ---- 业务 ----
    const val MODELS_PATH = "/api/v2/model/list"
    const val ACCOUNT_CONTEXT_PATH =
        "/api/v1/adapter/user/account-context?include=user,plan,quota,page,data_sharing"
    const val WALLETS_PATH = "/user/wallets"

    // ---- 客户端身份（必须与请求头声明一致，混用会让 body 与 header 自相矛盾）----
    const val COSY_VERSION = "1.1.52"
    const val CLIENT_TYPE = "6"
    const val SCENE = "qwork"
    const val BUSINESS_PRODUCT = "qoder_work"
    const val BUSINESS_TYPE = "agent"
    const val USER_AGENT = "qoderwork/1.1.0"
    const val LOGIN_VERSION = "v2"
    const val IDE_VERSION = "1.1.0"

    /**
     * 上下文上限：实测（2026-09-24，中文输入，逐档上探 + 二分）
     * 1250K 汉字 ≈ 1,022,745 token 通过；超过 1M 之后不是截断而是整轮 400/502。
     * 填小了上游按错误声明硬拒，填大了也会失败——所以如实填实测值。
     */
    const val MAX_INPUT_TOKENS = 1_024_000

    /** 模型目录以 `/api/v2/model/list` 为准，这里只兜底。 */
    val FALLBACK_MODELS = listOf("pro", "flash")

    /** `pro` 用于重创作/长任务；`flash` 会播报进度就收尾。 */
    const val DEFAULT_MODEL = "pro"

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 300_000
}
