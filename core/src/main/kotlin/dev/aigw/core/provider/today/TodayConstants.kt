package dev.aigw.core.provider.today

/**
 * Today（api.todayai.cn）的常量与模型映射。
 *
 * 协议来自 today-proxy 系列项目的实测抓包（github.com/Main8986/today-proxy-android）：
 *
 *   session token ──GET /api/auth/token──> ES256 JWT（约 115s）──Bearer──> api.todayai.cn/v1/…
 *
 * 与其它供应商不同，Today **没有 SSE 流式接口**：发一句话拿 threadId，再轮询
 * `/v1/conversations/messages` 直到 state=completed。所以 [TodayProvider] 的流式是
 * 「轮询出来的增量 + 本地拼 SSE」，客户端看到的是标准 OpenAI 流。
 */
object TodayConstants {

    const val AUTH_BASE = "https://auth.todayai.cn"
    const val API_BASE = "https://api.todayai.cn"

    /** 上游客户端版本号：写死在请求头里，改大了会被判客户端不合法。 */
    const val APP_VERSION = "1.23.0"

    /** 上游的模型是用「选项 id」表达的，不是 model 字段。 */
    const val OPT_DEEPSEEK = "model.deepseek-v4.1-flash"
    const val OPT_GLM = "model.glm-5.3"

    /** 暴露给客户端的短名。 */
    const val MODEL_DEEPSEEK = "deepseek-v4.1-flash"
    const val MODEL_GLM = "glm-5.3"

    val MODEL_IDS = listOf(MODEL_DEEPSEEK, MODEL_GLM)

    private const val CAPS =
        "chat.browser.v1,connectors.account_management.v1,chat.brief_card.v1," +
            "chat.qa_cardflow_single.v1,chat.health_metric_card.v1,chat.automation_card.v1," +
            "chat.follow_up_recommendations.v1,chat.message_reactions.unicode.v1"

    fun capabilities(): String = CAPS

    /**
     * 上游按 UA 里的版本号与设备串做灰度，UA 必须和 APP_VERSION 对得上。
     *
     * 设备串刻意保持成一台固定机型：漂移的 UA 会被当成「未知客户端」，
     * 部分账号会被降级到不含 v4.1 选项的旧界面。
     */
    fun userAgent(): String =
        "Today/$APP_VERSION Version/$APP_VERSION Build/2768 " +
            "Device/(OnePlus;OP5D2BL1)(PKG110; Android 15) NetType/WiFi"

    /** 把客户端传来的模型名归一到上游 optionId；不认识的返回 null（交给调用方报错）。 */
    fun optionIdOf(model: String): String? {
        val s = model.trim().lowercase()
        if (s.isEmpty()) return OPT_DEEPSEEK
        // 允许带供应商前缀：today/deepseek-v4.1-flash
        val bare = s.substringAfter('/', s).substringAfter(':')
        return when {
            bare == "glm" || bare == MODEL_GLM || bare == OPT_GLM || bare.startsWith("glm-") -> OPT_GLM
            bare == "deepseek" || bare == MODEL_DEEPSEEK || bare == OPT_DEEPSEEK ||
                bare.startsWith("deepseek") -> OPT_DEEPSEEK
            else -> null
        }
    }

    /** optionId → 短名。 */
    fun displayNameOf(optionId: String): String =
        if (optionId == OPT_GLM) MODEL_GLM else MODEL_DEEPSEEK
}
