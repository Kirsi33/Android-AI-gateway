package dev.aigw.core.provider.qwenwork

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.cosy.Cosy
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.InputStream

/** 千问办公账号凭证。 */
data class QwenAccount(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val nickname: String,
    val organizationId: String,
    /** 机器码：与 token 配套进签名，多账号共用会被服务端认成同一台设备。 */
    val machineId: String,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("accessToken", accessToken)
        addProperty("refreshToken", refreshToken)
        addProperty("expiresAt", expiresAt)
        addProperty("nickname", nickname)
        addProperty("organizationId", organizationId)
        addProperty("machineId", machineId)
    }

    companion object {
        fun parse(raw: String): QwenAccount {
            val root = JsonParser.parseString(raw).asJsonObject
            val token = root.stringOrNull("accessToken").orEmpty()
            if (token.isEmpty()) throw IllegalArgumentException("凭证缺少 accessToken")
            return QwenAccount(
                uid = root.stringOrNull("uid").orEmpty(),
                accessToken = token,
                refreshToken = root.stringOrNull("refreshToken").orEmpty(),
                expiresAt = root.get("expiresAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                nickname = root.stringOrNull("nickname").orEmpty(),
                organizationId = root.stringOrNull("organizationId").orEmpty(),
                machineId = root.stringOrNull("machineId").orEmpty().ifEmpty { dev.aigw.core.provider.qoder.QoderAccount.newMachineId() },
            )
        }
    }
}

/**
 * 千问办公推理通道（COSY 签名 + SSE）。
 *
 * 与 Qoder 的差别只有三处：常量（cosyVersion / clientType / scene / UA）、
 * 身份 JSON 的字段名（下划线风格）、上下文上限。
 */
class QwenChatClient(private val account: QwenAccount) {

    private val session: Cosy.Session by lazy { newSession() }

    fun open(openAiBody: String): ChatCall {
        val request = runCatching { JsonParser.parseString(openAiBody).asJsonObject }
            .getOrElse { return FailedChatCall(400, "请求体不是合法 JSON") }
        val modelKey = request.stringOrNull("model").orEmpty().ifEmpty { QwenConstants.DEFAULT_MODEL }
        // body 必须过自定义 base64（URL 上的 Encode=1），签名用的也是编码后的串
        val body = Cosy.encode(buildBody(modelKey, request))

        val payloadB64 = Cosy.buildPayloadB64(session.info, QwenConstants.COSY_VERSION)
        val date = (System.currentTimeMillis() / 1000).toString()
        val sig = Cosy.signRequest(payloadB64, session.cosyKey, date, body, QwenConstants.CHAT_PATH_SIG)

        val headers = buildMap {
            put("cosy-data-policy", "agree")
            put("content-type", "application/json")
            put("cosy-clienttype", QwenConstants.CLIENT_TYPE)
            put("cosy-date", date)
            put("cosy-user", account.uid)
            put("cosy-key", session.cosyKey)
            put("cosy-version", QwenConstants.COSY_VERSION)
            put("cosy-machineid", account.machineId)
            put("cosy-scene", QwenConstants.SCENE)
            put("cosy-business-product", QwenConstants.BUSINESS_PRODUCT)
            put("cosy-business-type", QwenConstants.BUSINESS_TYPE)
            put("login-version", QwenConstants.LOGIN_VERSION)
            put("cache-control", "no-cache")
            put("accept", "text/event-stream")
            put("accept-encoding", "identity")
            put("authorization", Cosy.composeBearer(payloadB64, sig))
            put("user-agent", QwenConstants.USER_AGENT)
            // 模型 key 必须同时进 body 与请求头：只设 body 时上游仍按 auto 走
            put("x-model-key", modelKey)
            put("x-model-source", "system")
        }

        val conn = try {
            HttpSupport.open(
                url = QwenConstants.GATEWAY + QwenConstants.CHAT_PATH + "?" + QwenConstants.CHAT_QUERY,
                method = "POST",
                headers = headers,
                body = body,
                connectTimeoutMs = QwenConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = QwenConstants.READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return FailedChatCall(502, "连接千问办公失败：${e.message}")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(502, "千问办公未返回响应：${e.message}")
        }
        if (status !in 200..299) {
            val text = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, text.ifEmpty { "上游 HTTP $status" })
        }
        val stream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(status, "读取流失败：${e.message}")
        }
        return StreamingChatCall(status, UnwrapStream(stream), onClose = { conn.disconnect() })
    }

    /**
     * 拉模型目录。**必须走 COSY 签名**（裸 Bearer 会被拒），
     * 且响应字段是 `qwork` 数组（不是 OpenAI 风格的 data/models）。
     */
    fun fetchModels(): List<String>? {
        val payloadB64 = Cosy.buildPayloadB64(session.info, QwenConstants.COSY_VERSION)
        val date = (System.currentTimeMillis() / 1000).toString()
        // **千问与 Qoder 在这里不一样**（dumate2api `qwenwork/index.js:289-302`）：
        // 千问的模型列表直接复用**聊天**签名——pathSig 用聊天路径、body 用 encode('{}')，
        // 再把 URL 换成 MODELS_PATH。按 models 路径自己算会 403 "Signature invalid"。
        val body = Cosy.encode("{}")
        val sig = Cosy.signRequest(payloadB64, session.cosyKey, date, body, CHAT_PATH_SIG)
        val headers = buildMap {
            put("cosy-data-policy", "agree")
            put("cosy-clienttype", QwenConstants.CLIENT_TYPE)
            put("cosy-date", date)
            put("cosy-user", account.uid)
            put("cosy-key", session.cosyKey)
            put("cosy-version", QwenConstants.COSY_VERSION)
            put("cosy-machineid", account.machineId)
            put("cosy-scene", QwenConstants.SCENE)
            put("cosy-business-product", QwenConstants.BUSINESS_PRODUCT)
            put("cosy-business-type", QwenConstants.BUSINESS_TYPE)
            put("login-version", QwenConstants.LOGIN_VERSION)
            put("accept", "application/json")
            put("accept-encoding", "identity")
            put("authorization", Cosy.composeBearer(payloadB64, sig))
            put("user-agent", QwenConstants.USER_AGENT)
            // 复用聊天签名时这两条也要带（参考实现直接沿用 prepareInfer 的头）
            put("x-model-key", QwenConstants.DEFAULT_MODEL)
            put("x-model-source", "system")
        }
        val conn = try {
            HttpSupport.open(
                url = QwenConstants.GATEWAY + QwenConstants.MODELS_PATH,
                method = "GET",
                headers = headers,
                body = null,
                connectTimeoutMs = QwenConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = 20_000,
            )
        } catch (e: Exception) {
            throw IllegalStateException("拉模型目录失败：${e.message}")
        }
        val status = conn.responseCode
        val body = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
        conn.disconnect()
        if (status !in 200..299) {
            throw IllegalStateException("拉模型目录失败：HTTP $status ${body.take(120)}")
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型目录响应不是 JSON：${body.take(120)}")
        val list = root.arrayOrNull("qwork") ?: root.arrayOrNull("chat") ?: root.arrayOrNull("data")
            ?: return null
        val keys = list.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (obj.get("enable")?.let { it.isJsonPrimitive && !it.asBoolean } == true) return@mapNotNull null
            obj.stringOrNull("key").orEmpty().takeIf { it.isNotEmpty() }
        }
        return keys.ifEmpty { null }
    }

    /**
     * 身份 JSON（字段与顺序对齐官方签名器）：
     * `uid / security_oauth_token / organization_id / organization_tags / data_policy_agreed`。
     */
    private fun newSession(): Cosy.Session {
        val identity = Cosy.sortedJson(
            mapOf(
                "uid" to account.uid,
                "security_oauth_token" to account.accessToken,
                "organization_id" to account.organizationId,
                "organization_tags" to "",
                "data_policy_agreed" to "true",
            ),
        )
        return Cosy.newSession(identity)
    }

    /**
     * 请求体与 Qoder 同构。两个实测约束写在这里：
     * 1. **`business` 对象必须有**，缺了整个请求一律 503 "Model catalog unavailable"（与签名无关）
     * 2. `system` 要留在 messages 里——上游只认 messages 里的 system 角色，顶层字段它不读
     */
    private fun buildBody(modelKey: String, request: JsonObject): String {
        val requestId = Cosy.newUuid()
        val messages = request.arrayOrNull("messages") ?: JsonArray()
        val system = request.stringOrNull("system").orEmpty()
        val lastUser = messages
            .mapNotNull { it.takeIf { m -> m.isJsonObject }?.asJsonObject }
            .lastOrNull { it.stringOrNull("role") == "user" }
            ?.stringOrNull("content")
            .orEmpty()

        val parameters = JsonObject().apply {
            for (key in listOf("temperature", "top_p", "max_tokens", "presence_penalty", "frequency_penalty")) {
                request.get(key)?.let { add(key, it) }
            }
            if (!has("max_tokens")) addProperty("max_tokens", 32_000)
        }
        // 只留标准 OpenAI 工具形状：namespace / web_search 会让整轮 400（不是丢弃该工具，是整轮失败）
        val tools = JsonArray()
        request.arrayOrNull("tools")?.forEach { item ->
            val obj = item.takeIf { t -> t.isJsonObject }?.asJsonObject ?: return@forEach
            val fn = obj.objOrNull("function") ?: return@forEach
            if (fn.stringOrNull("name").isNullOrEmpty()) return@forEach
            tools.add(obj)
        }

        return JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("request_set_id", requestId)
            addProperty("chat_record_id", requestId)
            addProperty("session_id", request.stringOrNull("session_id").orEmpty().ifEmpty { Cosy.newUuid() })
            addProperty("stream", true)
            addProperty("chat_task", "FREE_INPUT")
            add("chat_context", JsonObject().apply {
                addProperty("text", lastUser)
                add("features", JsonArray())
                add("extra", JsonObject().apply {
                    add("context", JsonArray())
                    add("modelConfig", JsonObject().apply {
                        addProperty("key", modelKey)
                        addProperty("is_reasoning", false)
                    })
                    addProperty("originalContent", lastUser)
                    addProperty("chatPrompt", "")
                })
                addProperty("chatPrompt", "")
                add("imageUrls", com.google.gson.JsonNull.INSTANCE)
            })
            addProperty("is_reply", true)
            addProperty("is_retry", false)
            addProperty("source", 1)
            addProperty("version", "3")
            addProperty("agent_id", "agent_common")
            addProperty("task_id", "common")
            addProperty("session_type", "qoder_work")
            addProperty("aliyun_user_type", "")
            add("model_config", JsonObject().apply {
                addProperty("key", modelKey)
                addProperty("display_name", modelKey)
                addProperty("model", "")
                addProperty("format", "openai")
                addProperty("is_vl", modelKey == "pro" || modelKey == "flash")
                addProperty("is_reasoning", false)
                addProperty("api_key", "")
                addProperty("url", "")
                addProperty("source", "system")
                addProperty("max_input_tokens", QwenConstants.MAX_INPUT_TOKENS)
            })
            add("business", JsonObject().apply {
                addProperty("product", QwenConstants.BUSINESS_PRODUCT)
                addProperty("version", QwenConstants.COSY_VERSION)
                addProperty("type", QwenConstants.BUSINESS_TYPE)
                addProperty("id", Cosy.newUuid())
                addProperty("name", "")
                addProperty("begin_at", System.currentTimeMillis())
                addProperty("stage", "processing")
            })
            addProperty("system", system)
            add("messages", messages)
            add("tools", tools)
            add("parameters", parameters)
        }.toString()
    }

    /** 剥外层信封（`{"body":"<内层 chunk>"}`），空帧直接丢。 */
    private class UnwrapStream(private val source: InputStream) : InputStream() {
        private val buffer = ArrayDeque<Byte>()
        private val reader = source.bufferedReader(Charsets.UTF_8)
        private var finished = false

        override fun read(): Int {
            if (buffer.isEmpty() && !fill()) return -1
            return buffer.removeFirst().toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (buffer.isEmpty() && !fill()) return -1
            var count = 0
            while (count < len && buffer.isNotEmpty()) {
                b[off + count] = buffer.removeFirst()
                count++
            }
            return count
        }

        override fun close() {
            runCatching { source.close() }
        }

        private fun fill(): Boolean {
            while (!finished) {
                val line = reader.readLine() ?: run {
                    finished = true
                    return false
                }
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") {
                    emit("data: [DONE]\n\n")
                    return true
                }
                val inner = dev.aigw.core.provider.qoder.QoderChatClient.unwrap(payload) ?: continue
                if (inner.isEmpty()) continue
                emit("data: $inner\n\n")
                return true
            }
            return false
        }

        private fun emit(text: String) {
            for (byte in text.toByteArray(Charsets.UTF_8)) buffer.addLast(byte)
        }
    }
}
