package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.boolOrNull

/**
 * 模型开放名单：决定外部客户端（agent）从 `/v1/models` 能拉到哪些模型。
 *
 * 模型页里能看到全量目录并逐个勾选；只有勾选的模型才对外开放。
 * - [filterEnabled] = false：不过滤，`/v1/models` 返回全量（默认，兼容旧行为）。
 * - [filterEnabled] = true：只返回 [exposed] 里出现过的 `fullId`（`provider/模型名`）。
 *
 * 独立存储、独立保存入口：不挂在 [GatewaySettings] 上，
 * 避免每次勾选都触发引擎的 provider 重载（updateSettings 会 reconfigureProviders）。
 */
data class ModelExposureSettings(
    /** 是否启用「只开放勾选的模型」。 */
    val filterEnabled: Boolean = false,
    /** 对外开放的模型 fullId 集合（`provider/模型名`，与模型页展示一致）。 */
    val exposed: Set<String> = emptySet(),
) {
    fun allows(fullId: String): Boolean = exposed.contains(fullId)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("filterEnabled", filterEnabled)
        val arr = com.google.gson.JsonArray()
        for (id in exposed) arr.add(id)
        add("exposed", arr)
    }

    companion object {
        const val STORE_KEY = "settings/model_exposure.json"

        fun fromJson(raw: String): ModelExposureSettings {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ModelExposureSettings()
            val ids = LinkedHashSet<String>()
            for (e in obj.arrayOrNull("exposed").orEmpty()) {
                val s = (e as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString
                if (!s.isNullOrBlank()) ids.add(s)
            }
            return ModelExposureSettings(
                filterEnabled = obj.boolOrNull("filterEnabled") ?: false,
                exposed = ids,
            )
        }

        fun load(store: KeyValueStore): ModelExposureSettings =
            runCatching { fromJson(store.read(STORE_KEY) ?: "") }.getOrDefault(ModelExposureSettings())
    }
}
