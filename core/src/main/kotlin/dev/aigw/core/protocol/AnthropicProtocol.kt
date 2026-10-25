package dev.aigw.core.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.io.InputStream
import java.util.UUID

/**
 * Anthropic Messages 协议 ↔ OpenAI Chat Completions 协议的双向翻译。
 *
 * 存在的理由：网关内部只认 OpenAI 一种形状（[dev.aigw.core.provider.Provider.openChat]
 * 收发的都是 OpenAI 请求/响应），但 Claude Code / Anthropic SDK 只发 `/v1/messages`。
 * 与其让每个 provider 各写一套 Anthropic 实现，不如在网关入口一次性翻译。
 *
 * 三条铁律（都是实测踩出来的）：
 *  1. **`message_start` 要延迟到真有内容才写**——空流还能换号，写了就换不了了；
 *  2. **`input_tokens` 必须在 `message_start` 里就给**——SDK 拿它算上下文余量；
 *  3. 流结束必须补齐 `content_block_stop` → `message_delta` → `message_stop`，
 *     少一个 SDK 就会认为响应被截断并抛错。
 */
object AnthropicProtocol {

    // ------------------------------------------------------------------ 请求

    /**
     * Anthropic Messages 请求 → OpenAI Chat Completions 请求。
     *
     * 无法解析、缺 model、或 messages 为空时返回 null，由调用方回 400。
     */
    fun toOpenAi(raw: String): String? {
        val src = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val model = src.str("model")
        if (model.isEmpty()) return null

        val out = JsonObject()
        out.addProperty("model", model)

        val messages = JsonArray()

        // Anthropic 的 system 是顶层字段（字符串或 text block 数组），OpenAI 里是一条 message
        val system = textOf(src.get("system"))
        if (system.isNotEmpty()) {
            messages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", system)
            })
        }

        val source = src.arrayOrNull("messages") ?: JsonArray()
        if (source.size() == 0) return null
        for (element in source) {
            val message = element.asObjectOrNull() ?: continue
            val role = message.str("role", "user")
            val text = textOf(message.get("content"))
            if (text.isEmpty()) continue
            messages.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", text)
            })
        }
        out.add("messages", messages)

        // 采样参数：名字基本一一对应，只有 stop_sequences → stop
        copyInt(src, out, "max_tokens")
        copyInt(src, out, "top_k")
        copyNumber(src, out, "temperature")
        copyNumber(src, out, "top_p")
        src.get("stream")?.let { out.addProperty("stream", it.asBoolean) }
        src.arrayOrNull("stop_sequences")?.let { out.add("stop", it) }

        // 工具：Anthropic 的 input_schema 就是 JSON Schema，直接搬成 parameters
        src.arrayOrNull("tools")?.let { tools ->
            val converted = JsonArray()
            for (element in tools) {
                val tool = element.asObjectOrNull() ?: continue
                val name = tool.str("name")
                if (name.isEmpty()) continue
                converted.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", name)
                        addProperty("description", tool.str("description"))
                        add(
                            "parameters",
                            tool.get("input_schema")?.takeIf { it.isJsonObject } ?: JsonObject().apply {
                                addProperty("type", "object")
                                add("properties", JsonObject())
                            },
                        )
                    })
                })
            }
            if (converted.size() > 0) out.add("tools", converted)
        }

        // tool_choice：auto / any → auto / required，指定工具 → 具体 function
        src.objOrNull("tool_choice")?.let { choice ->
            when (choice.str("type")) {
                "any" -> out.addProperty("tool_choice", "required")
                "auto" -> out.addProperty("tool_choice", "auto")
                "tool" -> {
                    val name = choice.str("name")
                    if (name.isNotEmpty()) {
                        out.add("tool_choice", JsonObject().apply {
                            addProperty("type", "function")
                            add("function", JsonObject().apply { addProperty("name", name) })
                        })
                    }
                }
            }
        }

        return out.toString()
    }

    /** content 既可能是字符串，也可能是 block 数组（text / image / tool_result）。 */
    private fun textOf(element: com.google.gson.JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (!element.isJsonArray) return ""
        val sb = StringBuilder()
        for (item in element.asJsonArray) {
            val block = item.asObjectOrNull() ?: continue
            when (block.str("type")) {
                "text" -> sb.append(block.str("text"))
                "image" -> sb.append("[image]")
                "tool_result" -> sb.append(block.str("content"))
                "tool_use" -> sb.append(block.str("name")).append(' ').append(block.get("input"))
                else -> if (block.has("text")) sb.append(block.str("text"))
            }
        }
        return sb.toString()
    }

    private fun copyInt(src: JsonObject, dst: JsonObject, key: String) {
        val v = src.get(key)?.takeIf { it.isJsonPrimitive } ?: return
        dst.addProperty(key, runCatching { v.asInt }.getOrDefault(0))
    }

    private fun copyNumber(src: JsonObject, dst: JsonObject, key: String) {
        val v = src.get(key)?.takeIf { it.isJsonPrimitive } ?: return
        dst.addProperty(key, runCatching { v.asDouble }.getOrDefault(0.0))
    }

    // ------------------------------------------------------------------ 非流式响应

    /** OpenAI `chat.completion` → Anthropic `message`。 */
    fun completionFromOpenAi(raw: String, fallbackModel: String): String {
        val src = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return errorBody(502, "上游返回不是合法 JSON")
        val model = src.str("model").ifEmpty { fallbackModel }
        val choice = src.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
        val message = choice?.objOrNull("message")

        val content = JsonArray()
        val text = message?.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (text.isNotEmpty()) {
            content.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        }
        message?.arrayOrNull("tool_calls")?.forEach { item ->
            val call = item.asObjectOrNull() ?: return@forEach
            val fn = call.objOrNull("function") ?: return@forEach
            val args = fn.str("arguments")
            content.add(JsonObject().apply {
                addProperty("type", "tool_use")
                addProperty("id", call.str("id").ifEmpty { "toolu_" + shortId() })
                addProperty("name", fn.str("name"))
                add("input", parseArguments(args))
            })
        }
        if (content.size() == 0) {
            content.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "")
            })
        }

        val usage = src.objOrNull("usage")
        val out = JsonObject().apply {
            addProperty("id", "msg_" + shortId())
            addProperty("type", "message")
            addProperty("role", "assistant")
            addProperty("model", model)
            add("content", content)
            addProperty("stop_reason", stopReasonOf(choice?.str("finish_reason")))
            add("stop_sequence", JsonNull.INSTANCE)
            add("usage", JsonObject().apply {
                addProperty("input_tokens", usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                addProperty("output_tokens", usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
            })
        }
        return out.toString()
    }

    /** tool_calls 的 arguments 是**字符串形式的 JSON**，要解析成对象才能塞进 tool_use.input。 */
    private fun parseArguments(args: String): JsonObject =
        runCatching { JsonParser.parseString(args).asJsonObject }.getOrElse {
            JsonObject().apply { addProperty("raw", args) }
        }

    private fun stopReasonOf(finishReason: String?): String = when (finishReason) {
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter" -> "end_turn"
        else -> "end_turn"
    }

    // ------------------------------------------------------------------ 流式响应

    /**
     * OpenAI SSE → Anthropic SSE。
     *
     * 状态都收在闭包里，一个流一个实例；[onFinish] 负责补尾帧。
     */
    fun sseFromOpenAi(source: InputStream, fallbackModel: String): InputStream {
        val id = "msg_" + shortId()
        var model = fallbackModel
        var started = false
        var inputTokens = 0
        var outputTokens = 0
        var stopReason = "end_turn"
        var textBlockOpen = false
        // OpenAI 的 tool_calls 按 index 分片到达；Anthropic 要一个个 content_block
        var nextBlockIndex = 0
        val toolBlocks = LinkedHashMap<Int, Int>() // openai index -> anthropic block index

        fun startFrame(): String {
            started = true
            return buildString {
                append("event: message_start\n")
                append("data: ")
                append(JsonObject().apply {
                    addProperty("type", "message_start")
                    add("message", JsonObject().apply {
                        addProperty("id", id)
                        addProperty("type", "message")
                        addProperty("role", "assistant")
                        addProperty("model", model)
                        add("content", JsonArray())
                        add("stop_reason", JsonNull.INSTANCE)
                        add("stop_sequence", JsonNull.INSTANCE)
                        add("usage", JsonObject().apply {
                            addProperty("input_tokens", inputTokens)
                            addProperty("output_tokens", 0)
                        })
                    })
                })
                append("\n\n")
            }
        }

        fun openTextBlock(): String {
            if (textBlockOpen) return ""
            textBlockOpen = true
            return event("content_block_start", JsonObject().apply {
                addProperty("type", "content_block_start")
                addProperty("index", 0)
                add("content_block", JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "")
                })
            })
        }

        return LineTransformStream(
            source = source,
            transform = { line ->
                val frames = ArrayList<String>(2)
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) return@LineTransformStream frames
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") return@LineTransformStream frames
                val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                    ?: return@LineTransformStream frames

                // 首帧之前先把 message_start 补上（含 input_tokens）
                obj.objOrNull("usage")?.let { usage ->
                    inputTokens = usage.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: inputTokens
                    outputTokens = usage.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: outputTokens
                }
                obj.str("model").takeIf { it.isNotEmpty() }?.let { model = it }

                val choice = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
                    ?: return@LineTransformStream frames
                val delta = choice.objOrNull("delta")

                val text = delta?.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (text.isNotEmpty()) {
                    if (!started) frames += startFrame()
                    frames += openTextBlock()
                    frames += event("content_block_delta", JsonObject().apply {
                        addProperty("type", "content_block_delta")
                        addProperty("index", 0)
                        add("delta", JsonObject().apply {
                            addProperty("type", "text_delta")
                            addProperty("text", text)
                        })
                    })
                }

                // 工具调用分片
                delta?.arrayOrNull("tool_calls")?.forEach { item ->
                    val call = item.asObjectOrNull() ?: return@forEach
                    val openAiIndex = call.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val fn = call.objOrNull("function")
                    val name = fn?.str("name").orEmpty()
                    val args = fn?.str("arguments").orEmpty()
                    if (!toolBlocks.containsKey(openAiIndex)) {
                        if (!started) frames += startFrame()
                        if (textBlockOpen) {
                            frames += event("content_block_stop", JsonObject().apply {
                                addProperty("type", "content_block_stop")
                                addProperty("index", 0)
                            })
                            textBlockOpen = false
                        }
                        val blockIndex = nextBlockIndex++
                        toolBlocks[openAiIndex] = blockIndex
                        frames += event("content_block_start", JsonObject().apply {
                            addProperty("type", "content_block_start")
                            addProperty("index", blockIndex)
                            add("content_block", JsonObject().apply {
                                addProperty("type", "tool_use")
                                addProperty("id", call.str("id").ifEmpty { "toolu_" + shortId() })
                                addProperty("name", name)
                                add("input", JsonObject())
                            })
                        })
                    }
                    if (args.isNotEmpty()) {
                        frames += event("content_block_delta", JsonObject().apply {
                            addProperty("type", "content_block_delta")
                            addProperty("index", toolBlocks[openAiIndex] ?: 0)
                            add("delta", JsonObject().apply {
                                addProperty("type", "input_json_delta")
                                addProperty("partial_json", args)
                            })
                        })
                    }
                }

                choice.str("finish_reason").takeIf { it.isNotEmpty() }?.let { finish ->
                    stopReason = stopReasonOf(finish)
                }
                frames
            },
            onFinish = {
                val frames = ArrayList<String>(4)
                // 空流也要给客户端一个完整的 message（否则 SDK 报「无响应」）
                if (!started) frames += startFrame()
                if (textBlockOpen) {
                    frames += event("content_block_stop", JsonObject().apply {
                        addProperty("type", "content_block_stop")
                        addProperty("index", 0)
                    })
                }
                for (blockIndex in toolBlocks.values) {
                    frames += event("content_block_stop", JsonObject().apply {
                        addProperty("type", "content_block_stop")
                        addProperty("index", blockIndex)
                    })
                }
                frames += event("message_delta", JsonObject().apply {
                    addProperty("type", "message_delta")
                    add("delta", JsonObject().apply {
                        addProperty("stop_reason", stopReason)
                        add("stop_sequence", JsonNull.INSTANCE)
                    })
                    add("usage", JsonObject().apply {
                        addProperty("output_tokens", outputTokens)
                    })
                })
                frames += event("message_stop", JsonObject().apply {
                    addProperty("type", "message_stop")
                })
                frames
            },
        )
    }

    private fun event(name: String, data: JsonObject): String =
        "event: $name\ndata: $data\n\n"

    /** Anthropic 的错误体形状（客户端 SDK 会按这个解析出 message）。 */
    fun errorBody(status: Int, message: String): String = JsonObject().apply {
        addProperty("type", "error")
        add("error", JsonObject().apply {
            addProperty("type", errorTypeOf(status))
            addProperty("message", message)
        })
    }.toString()

    private fun errorTypeOf(status: Int): String = when (status) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        529 -> "overloaded_error"
        else -> "api_error"
    }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(24)
}
