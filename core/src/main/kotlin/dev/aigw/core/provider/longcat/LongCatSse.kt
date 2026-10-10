package dev.aigw.core.provider.longcat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
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
 * ## 思考模式的「答案归属」问题
 *
 * 上游在 `reasonEnabled=1 && searchEnabled=0` 时，`content` 事件里**既有开场套话、
 * 也有最终答案**，二者没有结构区分，唯一可靠信号是 `finish.finalContentX`（它就是答案）。
 *
 * 因此本翻译器在思考模式下采取**延迟发送**策略：[deferContent] 为 true 时，
 * `content` 事件只累积不发送，等到 `finish` 拿到 `finalContentX` 才一次性发出；
 * 此前累积的内容作为思维链（`reasoning_content`）补发。
 *
 * 非思考模式（或开了联网搜索）下 content 就是正文，正常逐条流式下发。
 */
class LongCatSseTranslator(
    private val model: String,
    /** true = 把上游 content 推迟到 finish 再判定归属（思考模式用）。 */
    private val deferContent: Boolean = false,
    private val id: String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24),
) {
    private var created = System.currentTimeMillis() / 1000

    /** 已累积但未必已发送的正文。 */
    private val contentBuffer = StringBuilder()

    /** 已发送给客户端的正文（用于判断是否已流式输出过）。 */
    private val sentContent = StringBuilder()

    private val reasoningBuffer = StringBuilder()
    private var sentRole = false
    private var finished = false

    /** 记录流内业务错误；非空表示这次调用应被判定为失败。 */
    var error: String? = null
        private set

    /** 结束时的 stopReason（stop / content_filter）。 */
    private var stopReason = "stop"

    /** 上游给的 usage（已映射成 OpenAI 命名）。 */
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

    private fun textChunk(field: String, text: String): String {
        sentContent.append(text)
        return roleChunk() + chunk(JsonObject().apply { addProperty(field, text) })
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

        // 顶层 tokenInfo（有些帧把它放在事件外层）
        obj.objOrNull("tokenInfo")?.let { usage = toOpenAiUsage(it) }

        // 上游可能 200 但 body 直接是业务错误 JSON（非 SSE）
        val topCode = obj.longOrNull("code")
        if (obj.objOrNull("event") == null && topCode != null && topCode != 0L) {
            error = obj.str("message").ifEmpty { "上游返回 code=$topCode" }
            return emptyList()
        }

        obj.longOrNull("created")?.takeIf { it > 0 }?.let { created = it }

        val event = obj.objOrNull("event") ?: return emptyList()
        val type = event.str("type")
        val out = when (type) {
            "create" -> listOf(roleChunk())

            "content" -> {
                val text = event.str("content")
                if (text.isEmpty()) return emptyList()
                contentBuffer.append(text)
                // 思考模式：先不发，等 finish 判定归属
                if (deferContent) emptyList() else listOf(textChunk("content", text))
            }

            "reason", "think" -> {
                val text = event.str("content")
                if (text.isEmpty()) return emptyList()
                reasoningBuffer.append(text)
                listOf(textChunk("reasoning_content", text))
            }

            // 阶段标记，无内容
            "summary" -> emptyList()

            // 联网检索片段：能取到文本就当正文
            "common_search", "general_search", "local_life_search" -> {
                val text = event.str("content")
                if (text.isEmpty()) return emptyList()
                contentBuffer.append(text)
                if (deferContent) emptyList() else listOf(textChunk("content", text))
            }

            "finish" -> {
                val finishType = event.str("finishType")
                if (finishType.equals("sensitive", ignoreCase = true)) stopReason = "content_filter"
                event.objOrNull("usage")?.let { usage = toOpenAiUsage(it) }
                resolveFinalContent(event.str("finalContentX"))
            }

            "event_error" -> {
                error = event.str("message").ifEmpty { event.str("content").ifEmpty { "上游流内错误" } }
                emptyList()
            }

            else -> emptyList()
        }

        if (type == "finish") finished = true
        return out
    }

    /**
     * 用 `finalContentX` 定稿正文。
     *
     * 上游可能返回的形态：
     *  1. `finalContentX` 非空 → **它就是答案**；此前累积的 content 归入思维链（只在延迟模式下改写）；
     *  2. `finalContentX` 为空 → 用累积的 content 兜底；
     *  3. 已流式发送过 → 不动（SSE 无法撤回，避免重复输出）。
     */
    private fun resolveFinalContent(finalText: String): List<String> {
        // 已经流式发过正文：不做任何改写
        if (sentContent.isNotEmpty()) return emptyList()

        val accumulated = contentBuffer.toString()
        val answer = finalText.ifEmpty { accumulated }
        if (answer.isEmpty()) return emptyList()

        val out = ArrayList<String>(2)
        // 延迟模式：此前累积的内容不是答案，作为思维链补发
        if (deferContent && accumulated.isNotEmpty() && accumulated != answer) {
            reasoningBuffer.append(accumulated)
            out += textChunk("reasoning_content", accumulated)
        }
        out += textChunk("content", answer)
        return out
    }

    /** 流结束：产出收尾帧（finish_reason + usage + [DONE]）。 */
    fun finish(): List<String> {
        if (finished) return emptyList()
        finished = true
        val out = ArrayList<String>(3)
        // 没有 finish 事件时（异常断流）也要把累积内容发出去，避免「有 token 无输出」
        if (sentContent.isEmpty() && contentBuffer.isNotEmpty()) {
            out += textChunk("content", contentBuffer.toString())
        }
        out += roleChunk()
        out += chunk(JsonObject(), stopReason)
        out += "data: [DONE]\n\n"
        return out
    }

    /** 整条流的聚合正文（非流式请求时用来拼 chat.completion）。 */
    fun aggregatedResponse(): String {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", sentContent.toString().ifEmpty { contentBuffer.toString() })
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
}
