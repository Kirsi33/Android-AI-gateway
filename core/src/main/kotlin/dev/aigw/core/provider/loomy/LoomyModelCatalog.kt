package dev.aigw.core.provider.loomy

/**
 * 模型目录：从 `GET /api/v1/models` 拉取（需要 session）。
 *
 * 拉不到时返回本次运行内缓存的旧列表（没有则空列表），并置 [fromFallback] 与 [lastError]，
 * 不展示打包的硬编码快照。
 */
class LoomyModelCatalog(
    private val apiBase: String = LoomyConstants.API_BASE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 10 * 60 * 1000,
) {
    private var cached: List<LoomyModel>? = null
    private var cachedAt = 0L
    private var cachedSession = ""

    @Volatile
    var fromFallback: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    fun invalidate() {
        cached = null
        cachedAt = 0L
        cachedSession = ""
    }

    /**
     * 取模型列表。
     *
     * [session] 为空（没有可用账号）时返回空列表，不发请求，也不算失败——
     * 登录前本就不该有模型，报错只会在模型页吓人。
     */
    fun models(session: String?): List<LoomyModel> {
        if (session.isNullOrEmpty()) {
            fromFallback = false
            lastError = ""
            return emptyList()
        }
        val now = nowMillis()
        val fresh = cached != null && cachedSession == session && now - cachedAt < ttlMillis
        if (fresh) return cached!!

        return try {
            val list = fetch(session)
            if (list.isEmpty()) {
                fromFallback = true
                lastError = "上游返回了空的模型列表"
                cached ?: emptyList()
            } else {
                cached = list
                cachedAt = now
                cachedSession = session
                fromFallback = false
                lastError = ""
                list
            }
        } catch (e: LoomyApiException) {
            fromFallback = true
            lastError = e.message ?: "拉取模型列表失败"
            cached ?: emptyList()
        } catch (e: Exception) {
            fromFallback = true
            lastError = e.message ?: "拉取模型列表失败"
            cached ?: emptyList()
        }
    }

    private fun fetch(session: String): List<LoomyModel> {
        val headers = mapOf(
            LoomyConstants.TOKEN_HEADER to session,
            LoomyConstants.TRACEPARENT_HEADER to LoomyTrace.newTraceparent(),
            LoomyConstants.VERSION_HEADER to LoomyConstants.CLIENT_VERSION,
        )
        val (status, body) = httpJson("$apiBase${LoomyConstants.PATH_MODELS}", "GET", headers)
        val obj = decodeEnvelope(status, body)
        val array = obj.array("data") ?: return emptyList()
        val result = ArrayList<LoomyModel>()
        for (element in array) {
            val item = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val id = item.str("id").trim()
            if (id.isEmpty()) continue
            val limit = item.obj("limit")
            val context = limit?.long("context") ?: item.long("context_length")
            val output = limit?.long("output") ?: item.long("max_output_tokens")
            val capabilities = item.obj("capabilities")
            val inputModalities = item.obj("modalities")?.array("input")
            val hasImage = inputModalities?.any { it.asString == "image" } == true
            result.add(
                LoomyModel(
                    id = id,
                    name = item.str("name").ifEmpty { item.str("displayName").ifEmpty { id } },
                    contextLength = context,
                    outputLength = output,
                    reasoning = item.bool("reasoning") || item.bool("supports_reasoning"),
                    toolCall = item.bool("tool_call") || capabilities?.bool("function_calling") == true,
                    attachment = item.bool("attachment") || hasImage,
                ),
            )
        }
        return result
    }
}

/** 供界面展示的目录视图。 */
data class ModelCatalogView(
    val models: List<LoomyModel>,
    val fromFallback: Boolean,
    val error: String,
)
