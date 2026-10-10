package dev.aigw.core.provider.longcat

/**
 * LongCat（美团「龙猫」大模型网页版 `longcat.chat`）私有接口常量。
 *
 * 上游是网页版前端接口，**没有官方契约**，全部字段来自前端还原：
 *  - 请求体里**没有 `model` 字段**，模型差异靠 `agentId` + `reasonEnabled` / `searchEnabled` 表达；
 *  - `reasonEnabled` 只有 0/1 两档，没有 OpenAI 那种 low/medium/high effort；
 *  - 多轮上下文没有结构化 messages，只能把历史拍平成带角色标签的纯文本塞进 `content`。
 */
object LongCatConstants {

    const val BASE = "https://longcat.chat"

    /** 免登录通道：无需任何 Cookie，但有 IP 级 429 限流。 */
    const val OVERSEA_CHAT = "$BASE/api/v1/chat-completion-oversea-V2"

    /** 登录态通道：必须先 `session-create` 拿 conversationId。 */
    const val CN_CHAT = "$BASE/api/v1/chat-completion-V2"

    const val SESSION_CREATE = "$BASE/api/v1/session-create"

    /** 登录态探活备用端点。 */
    const val USER_CURRENT = "$BASE/api/v1/user-current"

    /** 美团系前端埋点/网关路由头，缺了可能被风控。 */
    const val APP_KEY = "fe_com.sankuai.friday.fe.longcat"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 上游 agent：1 = 普通文本，2 = pro 类。 */
    const val AGENT_DEFAULT = "1"
    const val AGENT_PRO = "2"

    /**
     * 模型别名表：[别名] -> (agentId, 是否开思考, 是否联网)。
     *
     * 这些别名是本 provider 自己起的，不是上游真实模型名（上游压根没有 model 字段）。
     */
    val ALIASES: Map<String, Triple<String, Boolean, Boolean>> = linkedMapOf(
        "longcat" to Triple(AGENT_DEFAULT, false, false),
        "longcat-default" to Triple(AGENT_DEFAULT, false, false),
        "longcat-flash" to Triple(AGENT_DEFAULT, false, false),
        "longcat-reason" to Triple(AGENT_DEFAULT, true, false),
        "longcat-thinking" to Triple(AGENT_DEFAULT, true, false),
        "longcat-r1" to Triple(AGENT_DEFAULT, true, false),
        "longcat-search" to Triple(AGENT_DEFAULT, false, true),
        "longcat-reason-search" to Triple(AGENT_DEFAULT, true, true),
        "longcat-pro" to Triple(AGENT_PRO, true, true),
        "LongCat-Flash" to Triple(AGENT_DEFAULT, false, false),
        "LongCat-2.0" to Triple(AGENT_DEFAULT, false, false),
    )

    /** 无 Cookie 时的默认模型。 */
    const val DEFAULT_MODEL = "longcat-flash"

    /** 展示用模型清单（上游不提供模型列表接口，这里给固定别名）。 */
    val MODEL_IDS: List<String> = listOf(
        "longcat-flash",
        "longcat-thinking",
        "longcat-search",
        "longcat-reason-search",
        "longcat-pro",
    )

    /**
     * 按模型名猜 (agentId, reason, search)。
     *
     * 别名表命中优先；否则模糊匹配（含 think/reason/r1 -> 思考，含 search/online -> 联网，含 pro -> agentId=2）。
     */
    fun resolve(model: String): Triple<String, Boolean, Boolean> {
        val key = model.trim()
        ALIASES[key]?.let { return it }
        ALIASES.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { return it.value }
        val lower = key.lowercase()
        val agent = if (lower.contains("pro")) AGENT_PRO else AGENT_DEFAULT
        val reason = lower.contains("think") || lower.contains("reason") || lower.contains("r1")
        val search = lower.contains("search") || lower.contains("online")
        return Triple(agent, reason, search)
    }
}
