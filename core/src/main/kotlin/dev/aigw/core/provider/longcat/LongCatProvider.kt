package dev.aigw.core.provider.longcat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.RegionAwareSupport
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import dev.aigw.core.util.stringOrNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * LongCat（美团「龙猫」大模型网页版 `longcat.chat`）。
 *
 * 上游是网页前端私有接口，两条通道，用 [RegionAwareSupport] 暴露为两个区域：
 *
 *  - `global`（默认，免登录）：`POST /api/v1/chat-completion-oversea-V2`，不带任何 Cookie。
 *    开箱即用，但有 **IP 级 429 限流**。
 *  - `cn`（登录态）：先 `POST /api/v1/session-create` 拿 conversationId，再
 *    `POST /api/v1/chat-completion-V2`；需要 `passport_token_key` Cookie。
 *
 * 上游请求体**没有 `model` 字段**：模型差异靠 `agentId` + `reasonEnabled` / `searchEnabled` 表达，
 * 多轮上下文把 OpenAI messages 拍平成带角色标签的纯文本塞进 `content`。
 */
class LongCatProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, RegionAwareSupport {

    override val id: String = ID
    override val displayName: String = "LongCat"
    override val authKind: AuthKind = AuthKind.NONE

    // ------------------------------------------------------------------ 区域

    override fun regions(): List<String> = listOf(REGION_GLOBAL, REGION_CN)

    /** 无 Cookie 的账号视为免登录区域，有 Cookie 的视为国内登录态。 */
    override fun regionOf(account: ProviderAccount): String? {
        val credential = LongCatAccount.parse(account.secret) ?: return REGION_GLOBAL
        return if (credential.anonymous) REGION_GLOBAL else REGION_CN
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val models = LongCatConstants.MODEL_IDS.map { id ->
            ProviderModel(
                id = id,
                name = describe(id),
                contextWindow = 128_000,
            )
        }
        return ProviderModelCatalogView(models, fromFallback = true, error = "")
    }

    private fun describe(id: String): String {
        val (agent, reason, search) = LongCatConstants.resolve(id)
        val tags = buildList {
            if (agent == LongCatConstants.AGENT_PRO) add("Pro")
            if (reason) add("思考")
            if (search) add("联网")
            if (isEmpty()) add("快速")
        }
        return "LongCat ${tags.joinToString(" · ")}"
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return LongCatConstants.DEFAULT_MODEL
        return model
    }

    override fun isInternalModel(id: String): Boolean = false

    override fun hosts(): List<String> = listOf("longcat.chat")

    /**
     * 导入凭证：直接收 `{"cookie":"..."}` 或裸 Cookie 串。
     *
     * Cookie 为空即免登录通道（uid 固定为 `anonymous`，一个供应商只会有一个这样的账号）。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val credential = runCatching {
            com.google.gson.JsonParser.parseString(text).asJsonObject
        }.getOrNull()?.let { obj ->
            val cookie = obj.str("cookie").trim()
            LongCatAccount(
                cookie = cookie,
                nickname = obj.str("nickname").ifEmpty {
                    if (cookie.isEmpty()) "免登录通道" else "LongCat"
                },
                uid = obj.str("uid").ifEmpty {
                    if (cookie.isEmpty()) "anonymous" else LongCatAccount.uidOf(cookie)
                },
            )
        } ?: LongCatAccount(
            cookie = text,
            nickname = "LongCat",
            uid = LongCatAccount.uidOf(text),
        )

        return ProviderAccount(
            providerId = ID,
            uid = credential.uid,
            nickname = credential.nickname,
            secret = credential.toJsonString(),
        )
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = LongCatAccount.parse(account.secret)
            ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))
        val (agentId, reason, search) = LongCatConstants.resolve(model)
        val prompt = buildPrompt(openAiBody)

        return if (credential.anonymous) {
            chatOversea(credential, prompt, agentId, reason, search, model, streaming)
        } else {
            chatCn(credential, prompt, agentId, reason, search, model, streaming)
        }
    }

    /** 免登录通道：一次 POST 直接拿 SSE。 */
    private fun chatOversea(
        credential: LongCatAccount,
        prompt: String,
        agentId: String,
        reason: Boolean,
        search: Boolean,
        model: String,
        streaming: Boolean,
    ): ChatCall {
        val payload = buildOverseaPayload(prompt, agentId, reason, search)
        val conn = try {
            open(LongCatConstants.OVERSEA_CHAT, credential, payload)
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        return consume(conn, model, streaming)
    }

    /** 登录态通道：先建会话，再发消息。 */
    private fun chatCn(
        credential: LongCatAccount,
        prompt: String,
        agentId: String,
        reason: Boolean,
        search: Boolean,
        model: String,
        streaming: Boolean,
    ): ChatCall {
        val conversationId = try {
            createSession(credential, agentId)
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "建立会话失败")
        }
        if (conversationId.isEmpty()) {
            return FailedChatCall(401, "会话创建失败：Cookie 可能已失效")
        }
        val payload = buildCnPayload(prompt, conversationId, agentId, reason, search)
        val conn = try {
            open(LongCatConstants.CN_CHAT, credential, payload)
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        return consume(conn, model, streaming)
    }

    private fun createSession(credential: LongCatAccount, agentId: String): String {
        val body = JsonObject().apply {
            addProperty("model", "")
            addProperty("agentId", agentId)
        }
        val conn = open(LongCatConstants.SESSION_CREATE, credential, body.toString())
        val status = conn.responseCode
        val text = runCatching {
            (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
        }.getOrDefault("")
        conn.disconnect()
        if (status !in 200..299) {
            throw IllegalStateException("session-create HTTP $status: ${text.take(200)}")
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("session-create 响应非 JSON")
        val code = obj.get("code")?.asLong ?: 0L
        if (code != 0L) {
            throw IllegalStateException("session-create 失败：${obj.str("message")}")
        }
        return obj.objOrNull("data")?.str("conversationId").orEmpty()
    }

    /** 统一处理上游响应：非 2xx / 非 SSE / 空回复都要有可检测的结论。 */
    private fun consume(conn: HttpURLConnection, model: String, streaming: Boolean): ChatCall {
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val raw = conn.inputStream

        // 上游 200 但直接回 JSON（非 SSE）——业务错误走这条
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            val text = try {
                readLimited(raw)
            } finally {
                conn.disconnect()
            }
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val code = obj?.get("code")?.asLong ?: 0L
            if (obj?.objOrNull("choices") == null && code != 0L) {
                val message = obj.str("message").ifEmpty { "上游返回 code=$code" }
                val kind = if (code == 401L || code == 403L) ErrorKind.SESSION_DEAD else ErrorKind.CLIENT
                return StreamFailureChatCall(status, UpstreamError(kind, message, code))
            }
            if (text.isBlank()) {
                return StreamFailureChatCall(
                    status,
                    UpstreamError(ErrorKind.SERVER, "上游返回空响应（upstream_empty）"),
                )
            }
            // 上游给的是 JSON，客户端要流式就包成单 chunk SSE
            return if (streaming) {
                AggregatedChatCall(status, text)
            } else {
                AggregatedChatCall(status, text)
            }
        }

        val translator = LongCatSseTranslator(model)
        val transformed = LineTransformStream(
            source = raw,
            transform = { line -> translator.feed(line) },
            onFinish = { translator.finish() },
        ) { conn.disconnect() }

        return if (streaming) {
            StreamingChatCall(status, transformed) { conn.disconnect() }
        } else {
            val aggregated = try {
                OpenAiSseAggregator.aggregate(transformed, model)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return StreamFailureChatCall(
                    status,
                    UpstreamError(ErrorKind.SERVER, "上游没有返回任何内容（upstream_empty）"),
                )
            }
            AggregatedChatCall(status, aggregated)
        }
    }

    // ------------------------------------------------------------------ 请求构造

    /**
     * 把 OpenAI `messages` 拍平成带角色标签的纯文本。
     *
     * 上游没有结构化多轮消息，只有单个 `content` 字段，所以上下文只能这样表达。
     */
    private fun buildPrompt(openAiBody: String): String {
        val obj = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: return openAiBody
        val messages = obj.arrayOrNull("messages") ?: return ""
        val parts = ArrayList<String>(messages.size())
        for (element in messages) {
            val message = element.asObjectOrNull() ?: continue
            val role = message.str("role", "user")
            val content = contentText(message)
            if (content.isEmpty()) continue
            val label = when (role) {
                "system" -> "[System]"
                "developer" -> "[Developer]"
                "user" -> "[User]"
                "assistant" -> "[Assistant]"
                "tool" -> "[Tool Result]"
                else -> "[$role]"
            }
            parts += "$label\n$content"
        }
        return parts.joinToString("\n\n")
    }

    /** content 可能是字符串，也可能是 `[{type,text}]` / `[{type:image_url,...}]` 数组。 */
    private fun contentText(message: JsonObject): String {
        val raw = message.get("content") ?: return ""
        if (raw.isJsonPrimitive) return raw.asString
        val array = raw.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val builder = StringBuilder()
        for (element in array) {
            val part = element.asObjectOrNull() ?: continue
            when (part.str("type")) {
                "text" -> builder.append(part.str("text"))
                "image_url" -> {
                    val url = part.objOrNull("image_url")?.str("url").orEmpty()
                    if (url.isNotEmpty()) builder.append("\n[Image: $url]\n")
                }
            }
        }
        return builder.toString()
    }

    private fun buildOverseaPayload(
        prompt: String,
        agentId: String,
        reason: Boolean,
        search: Boolean,
    ): String {
        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            add("events", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "userMsg")
                    addProperty("content", prompt)
                    addProperty("status", "FINISHED")
                })
            })
            addProperty("chatStatus", "FINISHED")
            addProperty("messageId", msgId())
            addProperty("idType", "custom")
        }
        val assistantMessage = JsonObject().apply {
            addProperty("role", "assistant")
            add("events", JsonArray())
            addProperty("chatStatus", "LOADING")
            addProperty("messageId", msgId())
            addProperty("idType", "custom")
        }
        return JsonObject().apply {
            addProperty("content", prompt)
            addProperty("agentId", agentId)
            add("messages", JsonArray().apply {
                add(userMessage)
                add(assistantMessage)
            })
            addProperty("reasonEnabled", if (reason) 1 else 0)
            addProperty("searchEnabled", if (search) 1 else 0)
            addProperty("regenerate", 0)
        }.toString()
    }

    private fun buildCnPayload(
        prompt: String,
        conversationId: String,
        agentId: String,
        reason: Boolean,
        search: Boolean,
    ): String = JsonObject().apply {
        addProperty("content", prompt)
        addProperty("conversationId", conversationId)
        addProperty("agentId", agentId)
        addProperty("reasonEnabled", if (reason) 1 else 0)
        addProperty("searchEnabled", if (search) 1 else 0)
        addProperty("regenerate", 0)
        addProperty("parentMessageId", 0)
        add("files", JsonArray())
    }.toString()

    private fun msgId(): Long = 10_000_000L + (Math.random() * 90_000_000L).toLong()

    private fun open(url: String, credential: LongCatAccount, body: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream,application/json")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        conn.setRequestProperty("X-Client-Language", "zh")
        conn.setRequestProperty("Origin", LongCatConstants.BASE)
        conn.setRequestProperty("Referer", "${LongCatConstants.BASE}/t")
        conn.setRequestProperty("User-Agent", LongCatConstants.UA)
        conn.setRequestProperty("m-appkey", LongCatConstants.APP_KEY)
        conn.setRequestProperty(
            "m-traceid",
            (nowMillis().toString() + (Math.random() * 1_000_000).toLong()),
        )
        if (!credential.anonymous) {
            conn.setRequestProperty("Cookie", credential.cookie)
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return conn
    }

    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val buffer = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < 65536) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toString(StandardCharsets.UTF_8.name())
        }
    }

    // ------------------------------------------------------------------ 错误分类

    override fun classify(status: Int, body: String): UpstreamError {
        val text = body.take(400)
        val kind = when (status) {
            401, 403 -> ErrorKind.SESSION_DEAD
            402 -> ErrorKind.QUOTA
            404 -> ErrorKind.NOT_FOUND
            429 -> ErrorKind.SOFT_RATE
            in 500..599 -> ErrorKind.SERVER
            400, 422 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        val message = extractMessage(text).ifEmpty { "上游 HTTP $status" }
        return UpstreamError(kind, message)
    }

    private fun extractMessage(body: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.stringOrNull("message")?.let { if (it.isNotEmpty()) return it }
        obj.objOrNull("error")?.let { error ->
            error.stringOrNull("message")?.let { if (it.isNotEmpty()) return it }
        }
        return ""
    }

    companion object {
        const val ID = "longcat"

        const val REGION_GLOBAL = "global"
        const val REGION_CN = "cn"
    }
}
