package dev.aigw.core.util

import com.google.gson.JsonObject

/**
 * 从各家模型条目里解析能力位，统一成 `extra` 里的约定键。
 *
 * 为什么需要它：`/v1/models` 会把能力输出给客户端（如 Minis），客户端据此自动勾选
 * 「图片输入 / 思考」。各家上游对能力的表达五花八门，如果每个 provider 各写一遍，
 * 迟早有人漏（小浣熊就漏了整整一版）。
 *
 * 约定键：`vision` / `reasoning` / `tools` / `search` / `maxOutput`，值都是字符串。
 *
 * **只在明确为真时才写入**——「上游没说」不等于「不支持」，别替上游下结论，
 * 否则客户端会把本来能用的能力关掉。
 */
fun jsonCapabilitiesOf(item: JsonObject): Map<String, String> {
    val out = LinkedHashMap<String, String>()

    fun flag(vararg keys: String): Boolean = keys.any { key ->
        val el = item.get(key) ?: return@any false
        when {
            el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
            el.isJsonPrimitive -> {
                val v = runCatching { el.asString }.getOrDefault("").trim().lowercase()
                v == "true" || v == "1" || v == "yes"
            }
            else -> false
        }
    }

    fun tokens(vararg keys: String): Set<String> {
        val set = HashSet<String>()
        for (key in keys) {
            val el = item.get(key) ?: continue
            if (el.isJsonArray) {
                for (e in el.asJsonArray) {
                    runCatching { e.asString }.getOrNull()?.trim()?.lowercase()?.let { set += it }
                }
            } else if (el.isJsonPrimitive) {
                runCatching { el.asString }.getOrNull()
                    ?.split(',', ' ')?.map { it.trim().lowercase() }?.let { set += it }
            }
        }
        return set
    }

    val caps = tokens("capabilities", "features", "tags")
    val inputs = tokens("input_modalities", "inputModalities", "modalities")

    if (flag("supports_vision", "supportsVision", "supports_images", "supportsImages", "vision") ||
        "vision" in caps || "image" in caps || "image" in inputs
    ) {
        out["vision"] = "true"
    }
    if (flag("supports_reasoning", "supportsReasoning", "reasoning", "thinking", "supportsThinking") ||
        "reasoning" in caps || "thinking" in caps
    ) {
        out["reasoning"] = "true"
    }
    if (flag("supports_tool_call", "supportsToolCall", "supports_tools", "supportsTools", "tools", "function_calling") ||
        "tools" in caps || "tool_call" in caps || "function_calling" in caps
    ) {
        out["tools"] = "true"
    }
    if (flag("supports_search", "supportsSearch", "web_search", "webSearch") || "search" in caps) {
        out["search"] = "true"
    }
    item.firstLong("max_output_tokens", "maxOutputTokens", "max_tokens", "maxOutput")
        .takeIf { it > 0 }?.let { out["maxOutput"] = it.toString() }
    item.firstString("vendor", "provider", "owner")
        .takeIf { it.isNotBlank() && it.length <= 24 }?.let { out["vendor"] = it }
    return out
}
