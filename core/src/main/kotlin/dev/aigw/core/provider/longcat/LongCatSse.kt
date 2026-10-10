package dev.aigw.core.provider.longcat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import dev.aigw.core.util.stringOrNull
import java.util.UUID

/**
 * LongCat 私有 SSE -> OpenAI 增量事件的翻译器。
 *
 * 上游每帧形如：
 * ```
 * data: {"event":{"type":"content","content":"你"},"conversationId":"...","lastOne":false}
 * data: [DONE]
 * ```
 *
 * `event.type` 语义：
 *  - `create`         建流，无内容
 *  - `content`        正文增量
 *  - `reason`/`think` 思维链增量（oversea 用 `reason`，登录态用 `think`）
 *  - `summary`        Thinker 阶段结束标记，内容丢弃
 *  - `*_search`       联网检索片段，按正文处理
 *  - `finish`         结束，带 `finalContentX`（完整正文）与 `usage`
 *  - `event_error`    流内错误
 *
 * 关键坑：开思考且**未联网**时，`content` 事件里既有开场套话也有最终答案。
 * 上游没有明确分隔，只有 `finish.finalContentX` 能给出「什么是答案」。
 * 因此本翻译器在 [LongCatSseTranslator.finish] 阶段用 `finalContentX` 校正：
 * 若累计正文与 `finalContentX` 不匹配，则把此前的正文整体降级为思维链。
 */
class LongCatSseTranslator(
    private val model: String,
    private val id: String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24),
) {
    private var created = System.currentTimeMillis() / 1000
    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private var sentRole = false
    private var finished = false

    /** 记录流内业务错误；非空表示这次调用应被判定为失败。 */
    var error: String? = null
        private set

    /** 结束时的 stopReason（stop / content_filter）。 */
    private var stopReason = "stop"

    /** 上游给的 usage（原始字段名，映射成 OpenAI 命名）。 */
    private var usage: JsonObject? = null

    private fun chunk(delta: JsonObject, finishReason: String? = null): String {
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finishReason != null) addProperty("finish_reason", finishReason)
        }
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
            if (finishReason != null) usage?.let { add("usage", it) }
        }
        return "data: $obj\n\n"
    }

    private fun roleChunk(): String {
        if (sentRole) return ""
        sentRole = true
        return chunk(JsonObject().apply { addProperty("role", "assistant") })
    }

    /**
     * 处理上游一行；返回要写给客户端的若干 SSE 帧（可能为空）。
     */
    fun feed(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") return finish()

        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return emptyList()

        // 上游可能 200 但 body 直接是业务错误 JSON（非 SSE）
        val topCode = obj.longOrNull("code")
        if (obj.objOrNull("event") == null && topCode != null && topCode != 0L) {
            error = obj.str("message").ifEmpty { "上游返回 code=$topCode" }
            return emptyList()
        }

        obj.longOrNull("created")?.takeIf { it > 0 }?.let { created = it }

        val event = obj.objOrNull("event") ?: return emptyList()
        return when (val type = event.str("type")) {
            "create" -> listOf(roleChunk())

            "content" -> {
                val text = event.str("content")
                if (text.isEmpty()) return emptyList()
                contentBuffer.append(text)
                listOf(roleChunk(), chunk(JsonObject().apply { addProperty("content", text) }))
            }

            "reason", "think" -> {
                val text = event.str("content")
                if (text.isEmpty()) return emptyList()
                reasoningBuffer.append(text)
                listOf(
                    roleChunk(),
                    chunk(JsonObject().apply { addProperty("reasoning_content", text) }),
                )
            }

            // 阶段标记，无内容
            "summary" -> emptyList()

            // 联网检索片段：能取到文本就当正文，取不到就丢弃
            "common_search", "general_search", "local_life_search" -> {
                val text = event.str("content")
                if (text.isEmpty()) return emptyList()
                contentBuffer.append(text)
                listOf(roleChunk(), chunk(JsonObject().apply { addProperty("content", text) }))
            }

            "finish" -> {
                val finalText = event.str("finalContentX")
                val finishType = event.str("finishType")
                if (finishType.equals("sensitive", ignoreCase = true)) stopReason = "content_filter"
                event.objOrNull("usage")?.let { usage = toOpenAiUsage(it) }
                // finalContentX 才是真答案：与累计正文不一致时，把累计内容整体降级为思维链
                val correction = correctFinalText(finalText)
                if (correction != null) listOf(correction) else emptyList()
            }

            "event_error" -> {
                error = event.str("message").ifEmpty { event.str("content").ifEmpty { "上游流内错误" } }
                emptyList()
            }

            else -> emptyList()
        }.also { if (type == "finish") finished = true }
    }

    /**
     * 用 `finalContentX` 校正正文。
     *
     * 返回一个额外的 SSE 帧：把「已经发出去的正文」与最终答案的差异补上。
     * 由于 SSE 是单向追加的，无法撤回已发内容，这里只在**尚未发送内容**时补发全文；
     * 已发过内容则不动（宁可多一段开场白，也不能重复输出）。
     */
    private fun correctFinalText(finalText: String): String? {
        if (finalText.isEmpty()) return null
        val sent = contentBuffer.toString()
        if (sent.isNotEmpty()) return null
        contentBuffer.append(finalText)
        return roleChunk() + chunk(JsonObject().apply { addProperty("content", finalText) })
    }

    /** 流结束：产出收尾帧（finish_reason + usage + [DONE]）。未发过任何内容则补一个空 role 帧。 */
    fun finish(): List<String> {
        if (finished) return emptyList()
        finished = true
        val out = ArrayList<String>(3)
        out += roleChunk()
        out += chunk(JsonObject(), stopReason)
        out += "data: [DONE]\n\n"
        return out
    }

    /** 整条流的聚合正文（非流式请求时用来拼 chat.completion）。 */
    fun aggregatedResponse(): String {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", contentBuffer.toString())
        }
        if (reasoningBuffer.isNotEmpty()) {
            message.addProperty("reasoning_content", reasoningBuffer.toString())
        }
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("message", message)
            addProperty("finish_reason", stopReason)
        }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
            usage?.let { add("usage", it) }
        }.toString()
    }

    /** 是否一条内容都没有。 */
    fun isEmpty(): Boolean = contentBuffer.isEmpty() && reasoningBuffer.isEmpty()

    private fun toOpenAiUsage(raw: JsonObject): JsonObject {
        // 上游字段名有两种：tokenInfo.{promptTokens,completionTokens} 与 event.usage.{inputTokens,outputTokens}
        val prompt = raw.longOrNull("promptTokens") ?: raw.longOrNull("inputTokens") ?: 0L
        val completion = raw.longOrNull("completionTokens") ?: raw.longOrNull("outputTokens") ?: 0L
        val total = raw.longOrNull("totalTokens") ?: (prompt + completion)
        return JsonObject().apply {
            addProperty("prompt_tokens", prompt)
            addProperty("completion_tokens", completion)
            addProperty("total_tokens", total)
        }
    }

    companion object {
        /** 从任意 JSON 里抽取顶层 `tokenInfo`（有些帧把它放在事件外层）。 */
        fun usageFrom(obj: JsonObject): JsonObject? = obj.objOrNull("tokenInfo")
    }
}
