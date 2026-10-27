package dev.aigw.core.provider.today

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.SmsLoginSupport
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Today（美团系 Today AI）供应商。
 *
 * ## 为什么这个 provider 长得和别家不一样
 *
 * 上游没有流式接口，也没有 `model` 字段：
 *
 * ```
 * POST /v1/messages {message, clientMessageId}   → threadId
 * GET  /v1/conversations/messages?isMainChat=true → 整段回复的**当前快照**
 * ```
 *
 * 回复是「轮询出来的完整文本」，每次返回都从头开始。所以流式必须自己做增量差分：
 * 记住上一次的文本长度，只把新增部分转成 `delta.content`。这也是为什么
 * [openChat] 会**先同步等到第一段内容**再返回流 —— 上游 401 / 没配额 / 连不上时
 * 网关还有机会换号，而不是半路吐了一个 `message_start` 才发现没内容。
 */
class TodayProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, SmsLoginSupport {

    private val authClient = TodayAuthClient()

    override val id: String = ID
    override val displayName: String = "Today"
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    /** 换来的 JWT 只有 ~115 秒，按账号缓存，避免每次对话都多打一次鉴权。 */
    private class CachedJwt(val token: String, val expiresAtMillis: Long, val userId: String)

    private val jwts = ConcurrentHashMap<String, CachedJwt>()

    /** 账号当前选中的上游模型选项；切模型要 PUT 一次，能缓存就别重复发。 */
    private val selectedOption = ConcurrentHashMap<String, String>()

    /** 从上游发现到的模型选项（进程内缓存，[DISCOVER_TTL_MS] 内不重复打网络）。 */
    @Volatile
    private var discoveredOptions: List<String> = emptyList()

    @Volatile
    private var discoveredAtMillis: Long = 0L

    // ------------------------------------------------------------------ 短信登录

    /**
     * 发验证码。[ccode] 是国家码（不带 `+`），上游按它路由短信通道。
     */
    override fun sendSmsCode(phone: String, ccode: String): String =
        authClient.sendOtp(normalizePhone(phone, ccode))

    /**
     * 验证码换账号。
     *
     * [msgid] 上游不需要（Today 的 verify 只认手机号 + 验证码），忽略即可。
     */
    override fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String,
    ): ProviderAccount {
        val account = authClient.verify(normalizePhone(phone, ccode), code.trim())
        return ProviderAccount(
            providerId = ID,
            uid = account.effectiveUid(),
            nickname = account.effectiveNickname(),
            secret = account.toJsonString(),
        )
    }

    /**
     * 把用户输入拼成上游要的 E.164（带 `+`）。
     *
     * 实测 Today 只对**大陆手机号（+86）**下码，所以 [ccode] 基本恒为 86；
     * 这里仍按国家码拼接，是为了不在 provider 层写死一个业务假设。
     *
     * 用户可能填 `138...`、`+86138...`、`86138...` 三种；已经带 `+` 的原样用，
     * 其余按「去掉前导 0 再拼国家码」处理——`0138...` 这种写法也能救回来。
     */
    private fun normalizePhone(phone: String, ccode: String): String {
        val trimmed = phone.trim()
        if (trimmed.startsWith("+")) return trimmed
        val digits = trimmed.filter(Char::isDigit).trimStart('0')
        return if (digits.isEmpty()) trimmed else "+$ccode$digits"
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 上游会随时上新模型（比如限时免费的 MiniMax），所以先试着从账号的套餐状态里
        // 把真实存在的 `model.*` 选项捞出来，再和内置兜底表合并。
        val discovered = account
            ?.let { TodayAccount.parse(it.secret) }
            ?.let { runCatching { discoverOptions(it) }.getOrDefault(emptyList()) }
            .orEmpty()

        // 上游返回的 model.xxx 与内置常量可能只差一个分隔符（minimax-m3.1 对 minimax-m3-1），
        // 字符串去重挡不住，会渲染成两行。这里按「归一化后的模型名」去重，并优先保留上游的写法
        // ——它才是真实存在、下游请求能打通的 id。
        val ids = LinkedHashMap<String, String>()
        discovered.forEach { ids.putIfAbsent(normalizeModelName(it), TodayConstants.displayNameOf(it)) }
        TodayConstants.MODEL_IDS.forEach { ids.putIfAbsent(normalizeModelName(it), it) }

        val models = ids.values.map { name ->
            ProviderModel(
                id = name,
                name = name,
                contextWindow = 128_000,
                extra = buildMap {
                    TodayConstants.noteOf(name).takeIf { it.isNotEmpty() }?.let { put("note", it) }
                    TodayConstants.rateOf(name).takeIf { it.isNotEmpty() }?.let { put("rate", it) }
                },
            )
        }
        return ProviderModelCatalogView(
            models = models,
            // 没账号或上游没返回选项时，用的就是内置兜底表
            fromFallback = discovered.isEmpty(),
            error = "",
        )
    }

    /**
     * 模型名归一化：去掉 `model.` 前缀，把 `.` `_` 统一成 `-`，其余小写。
     *
     * 只用于**去重**，不用作发给上游的 id——上游对分隔符是敏感的。
     */
    private fun normalizeModelName(name: String): String =
        name.removePrefix("model.").lowercase().replace('.', '-').replace('_', '-')

    /**
     * 从 `/v1/pricing/status` 里发现可用模型选项。
     *
     * 不去猜 `modelOptions` 的具体结构（上游字段名改过、也可能再改）：
     * 递归遍历整份响应，凡是形如 `model.xxx` 的字符串都当作候选 optionId。
     * 这样上游新增模型时不需要改代码。
     */
    private fun discoverOptions(credential: TodayAccount): List<String> {
        val now = nowMillis()
        if (now - discoveredAtMillis < DISCOVER_TTL_MS && discoveredOptions.isNotEmpty()) {
            return discoveredOptions
        }
        val raw = runCatching {
            apiCall(credential, "GET", "/v1/pricing/status", null, null, 30_000)
        }.getOrNull() ?: return discoveredOptions
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return discoveredOptions

        val found = LinkedHashSet<String>()
        fun walk(element: com.google.gson.JsonElement?) {
            when {
                element == null || element.isJsonNull -> Unit
                element.isJsonPrimitive -> {
                    val text = runCatching { element.asString }.getOrDefault("")
                    if (text.startsWith("model.") && text.length in 8..80) found += text
                }
                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { walk(it.value) }
            }
        }
        walk(root)

        if (found.isNotEmpty()) {
            discoveredOptions = found.toList()
            discoveredAtMillis = now
        }
        return discoveredOptions
    }

    override fun resolveModel(requested: String): String {
        val option = TodayConstants.optionIdOf(requested) ?: return requested
        return TodayConstants.displayNameOf(option)
    }

    override fun hosts(): List<String> = listOf("todayai.cn")

    override fun importCredentials(raw: String): ProviderAccount? {
        val credential = TodayAccount.parse(raw) ?: return null
        return ProviderAccount(
            providerId = ID,
            uid = credential.effectiveUid(),
            nickname = credential.effectiveNickname(),
            secret = credential.toJsonString(),
        )
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = TodayAccount.parse(account.secret)
            ?: return FailedChatCall(401, "凭证无法解析，重新粘贴 session token")
        val option = TodayConstants.optionIdOf(requestedModelOf(openAiBody))
            ?: return FailedChatCall(
                400,
                "Today 不支持的模型：${requestedModelOf(openAiBody)}；可用：${TodayConstants.MODEL_IDS.joinToString(", ")}",
            )
        val prompt = buildPrompt(openAiBody)
        if (prompt.isEmpty()) return FailedChatCall(400, "请求里没有可发送的文本内容")
        val streaming = isStreamingBody(openAiBody)
        val modelName = TodayConstants.displayNameOf(option)

        return try {
            // 1) 换 JWT，并把账号切到目标模型（两个都可能失败，失败时还没开始吐流，能换号）
            val jwt = ensureJwt(credential)
            ensureModel(credential, jwt, option)

            // 2) 发消息拿 threadId
            val threadId = send(credential, jwt, prompt)

            // 3) 等到第一段内容（最长 90 秒）。空手就返回失败，让网关换号。
            val first = waitFirstText(credential, jwt, threadId, FIRST_TEXT_TIMEOUT_MS)
            if (first == null) {
                return StreamFailureChatCall(
                    504,
                    UpstreamError(ErrorKind.SERVER, "Today 90 秒内没有回复内容", 504),
                )
            }

            if (streaming) {
                streamCall(credential, threadId, modelName, first)
            } else {
                val full = waitCompleteText(credential, jwt, threadId, first)
                AggregatedChatCall(200, completionJson(modelName, full))
            }
        } catch (e: UpstreamException) {
            return FailedChatCall(e.status, e.message.orEmpty())
        } catch (e: Exception) {
            return FailedChatCall(0, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** provider 内部的上游错误：带上 HTTP 状态码，交给 [classify] 归一。 */
    private class UpstreamException(val status: Int, message: String) : Exception(message)

    // ------------------------------------------------------------------ 提示词

    /**
     * 把 OpenAI messages 拍平成单段文本。
     *
     * Today 的 `/v1/messages` 只接受一句 `message`，没有多轮结构；角色用标签表达，
     * 与 LongCat 的处理保持一致（同一套客户端行为，换供应商不该突然丢 system prompt）。
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

    /** 兼容三种 content 形态：字符串、分块数组（text 段）、以及不带 content 的 tool 消息。 */
    private fun contentText(message: JsonObject): String {
        val content = message.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        val array = content as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (element in array) {
            val part = element.asObjectOrNull() ?: continue
            when (part.str("type")) {
                "text" -> sb.append(part.str("text"))
                "image_url" -> sb.append("[image]")
                else -> if (part.has("text")) sb.append(part.str("text"))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 鉴权

    /**
     * 拿一个可用 JWT：缓存未过期就用缓存，否则换一次。
     *
     * 上游 JWT 只有约 115 秒，所以余量留得比较保守（提前 20 秒换）。
     */
    private fun ensureJwt(credential: TodayAccount): String {
        val key = credential.effectiveUid()
        val cached = jwts[key]
        val now = nowMillis()
        if (cached != null && now < cached.expiresAtMillis - 20_000) return cached.token

        val conn = openConnection("${TodayConstants.AUTH_BASE}/api/auth/token", "GET", credential, jwt = null)
        val (status, body) = readAll(conn, 30_000)
        if (status != 200) {
            throw UpstreamException(status, "今日登录态换取失败：${trimBody(body)}")
        }
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw UpstreamException(0, "登录态返回不是 JSON")
        val token = obj.str("token")
        if (token.isEmpty()) throw UpstreamException(0, "登录态返回缺少 token 字段")

        val exp = jwtExpiryMillis(token) ?: (now + 115_000)
        val userId = jwtSubject(token)
        jwts[key] = CachedJwt(token, exp, userId)
        return token
    }

    /** 解 JWT 的 payload 段；解析失败返回 null（core 是纯 JVM 模块，只能用 java.util.Base64）。 */
    private fun jwtPayload(jwt: String): JsonObject? {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        return runCatching {
            val decoded = java.util.Base64.getUrlDecoder().decode(payload)
            JsonParser.parseString(String(decoded, StandardCharsets.UTF_8)).asJsonObject
        }.getOrNull()
    }

    /** JWT 的 exp 是秒；解析失败返回 null（调用方按 115 秒算）。 */
    private fun jwtExpiryMillis(jwt: String): Long? {
        val json = jwtPayload(jwt) ?: return null
        val exp = json.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        return exp * 1000
    }

    private fun jwtSubject(jwt: String): String = jwtPayload(jwt)?.str("sub").orEmpty()

    // ------------------------------------------------------------------ HTTP

    /**
     * 统一构造请求。写死的这几个头是上游风控的一部分（缺 Origin 会被当跨站脚本拒绝），
     * 不要为了「干净」删掉。
     */
    private fun openConnection(
        url: String,
        method: String,
        credential: TodayAccount,
        jwt: String?,
        body: String? = null,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", TodayConstants.userAgent())
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Language", "zh-CN")
        conn.setRequestProperty("Origin", "https://todayai.cn")
        conn.setRequestProperty("Referer", "https://todayai.cn/")
        conn.setRequestProperty("X-Client-Platform", "android")
        conn.setRequestProperty("X-App-Version", TodayConstants.APP_VERSION)
        if (jwt != null) {
            conn.setRequestProperty("Authorization", "Bearer $jwt")
            conn.setRequestProperty("X-Client-Capabilities", TodayConstants.capabilities())
            conn.setRequestProperty("X-Timezone", "Asia/Shanghai")
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${credential.token}")
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        return conn
    }

    private fun readAll(conn: HttpURLConnection, timeoutMillis: Int): Pair<Int, String> {
        conn.readTimeout = timeoutMillis
        return try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            status to (stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun trimBody(body: String): String = body.replace(Regex("<[^>]+>"), " ").trim().take(200)

    // ------------------------------------------------------------------ 上游调用

    /**
     * 带一次 401 重试的业务调用。
     *
     * JWT 只有约 115 秒，长回复 / 批量刷新很容易在途中过期；遇到 401 先丢掉缓存再试一次，
     * 第二次还 401 才是真的登录态失效（token 本身被吊销）。
     */
    private fun apiCall(
        credential: TodayAccount,
        method: String,
        path: String,
        jwt: String?,
        body: String?,
        timeoutMillis: Int,
    ): String {
        fun once(useJwt: String?): Pair<Int, String> {
            val conn = openConnection(TodayConstants.API_BASE + path, method, credential, useJwt, body)
            return readAll(conn, timeoutMillis)
        }

        val token = jwt ?: ensureJwt(credential)
        var (status, raw) = once(token)
        if (status == 401) {
            jwts.remove(credential.effectiveUid())
            val fresh = ensureJwt(credential)
            val retry = once(fresh)
            status = retry.first
            raw = retry.second
        }
        if (status !in 200..299) {
            throw UpstreamException(status, "Today $status：${trimBody(raw)}")
        }
        return raw
    }

    /** 上游的模型是「账号级选项」，切一次要 PUT 一次；同账号同模型走缓存。 */
    private fun ensureModel(credential: TodayAccount, jwt: String, option: String) {
        val key = credential.effectiveUid()
        if (selectedOption[key] == option) return
        val body = JsonObject().apply { addProperty("optionId", option) }.toString()
        apiCall(credential, "PUT", "/v1/pricing/model-option", jwt, body, 30_000)
        selectedOption[key] = option
    }

    /** 发一句话，拿 threadId。 */
    private fun send(credential: TodayAccount, jwt: String, prompt: String): String {
        val body = JsonObject().apply {
            addProperty("message", prompt)
            addProperty("clientMessageId", "msg_" + UUID.randomUUID().toString().replace("-", ""))
        }.toString()
        val raw = apiCall(credential, "POST", "/v1/messages", jwt, body, 60_000)
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        val threadId = obj?.str("threadId").orEmpty()
        if (threadId.isEmpty()) throw UpstreamException(0, "Today 发送成功但没有 threadId：${trimBody(raw)}")
        return threadId
    }

    /** 一次回复快照：整段文本 + 是否已完成。 */
    private class Snapshot(val text: String, val completed: Boolean)

    /**
     * 拉一次会话消息，取出该 threadId 的 assistant 文本。
     *
     * 注意取的是**最后一条非空文本**而不是拼接：上游每轮会把整段回复重写一遍，
     * 拼接会得到重复内容。
     */
    private fun snapshot(credential: TodayAccount, jwt: String, threadId: String): Snapshot {
        val raw = apiCall(
            credential,
            "GET",
            "/v1/conversations/messages?isMainChat=true&limit=50",
            jwt,
            null,
            30_000,
        )
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return Snapshot("", false)
        val items = root.objOrNull("data")?.arrayOrNull("items") ?: return Snapshot("", false)
        var text = ""
        var completed = false
        for (element in items) {
            val message = element.asObjectOrNull() ?: continue
            if (message.str("threadId") != threadId) continue
            if (message.str("role") != "assistant") continue
            val content = textOf(message)
            if (content.isNotEmpty()) text = content
            if (message.str("state") == "completed" && text.isNotEmpty()) completed = true
        }
        return Snapshot(text, completed)
    }

    /** 从 body.parts 里取 messageType=text 的内容。 */
    private fun textOf(message: JsonObject): String {
        val parts = message.objOrNull("body")?.arrayOrNull("parts") ?: return ""
        val sb = StringBuilder()
        for (element in parts) {
            val part = element.asObjectOrNull() ?: continue
            if (part.str("messageType") != "text") continue
            sb.append(part.objOrNull("content")?.str("text").orEmpty())
        }
        return sb.toString()
    }

    /**
     * 等到第一段内容出现。
     *
     * 这一步是「能不能换号」的分水岭：没有内容之前一切都还能回退，
     * 所以最长等 [timeoutMillis]，而不是傻等到 completed。
     */
    private fun waitFirstText(
        credential: TodayAccount,
        jwt: String,
        threadId: String,
        timeoutMillis: Long,
    ): String? {
        val deadline = nowMillis() + timeoutMillis
        while (nowMillis() < deadline) {
            val snap = snapshot(credential, jwt, threadId)
            if (snap.text.isNotEmpty()) return snap.text
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /** 非流式：继续轮询到 completed（或总超时），返回最后一段完整文本。 */
    private fun waitCompleteText(
        credential: TodayAccount,
        jwt: String,
        threadId: String,
        first: String,
    ): String {
        val deadline = nowMillis() + TOTAL_TIMEOUT_MS
        var text = first
        while (nowMillis() < deadline) {
            val snap = runCatching { snapshot(credential, jwt, threadId) }.getOrNull()
                ?: return text
            if (snap.text.length > text.length) text = snap.text
            if (snap.completed) return text
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return text
    }

    // ------------------------------------------------------------------ 流式

    /**
     * 把轮询出来的整段文本差分成本地 OpenAI SSE。
     *
     * 首个 chunk 在调用线程里就已经准备好（[first]），所以客户端不会看到「连上了但很久没字」；
     * 后续增量由一个守护线程边轮询边写，[ChatCall.close] 会中断它。
     */
    private fun streamCall(
        credential: TodayAccount,
        threadId: String,
        model: String,
        first: String,
    ): ChatCall {
        val pipeIn = PipedInputStream(PIPE_BUFFER)
        val pipeOut = PipedOutputStream(pipeIn)
        val closed = AtomicBoolean(false)
        val cid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = nowMillis() / 1000

        val worker = Thread {
            var sent = ""
            try {
                pipeOut.write(roleChunk(cid, created, model))
                sent = first
                pipeOut.write(deltaChunk(cid, created, model, first))
                pipeOut.flush()

                val deadline = nowMillis() + TOTAL_TIMEOUT_MS
                var jwt = ensureJwt(credential)
                var idleTicks = 0
                while (!closed.get() && nowMillis() < deadline) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    if (closed.get()) break
                    val snap = runCatching { snapshot(credential, jwt, threadId) }.getOrNull()
                    if (snap == null) {
                        idleTicks++
                        // 连续失败时换一次 JWT（多半是 115 秒过期），仍失败就等下一轮
                        if (idleTicks >= 3) {
                            jwts.remove(credential.effectiveUid())
                            jwt = runCatching { ensureJwt(credential) }.getOrDefault(jwt)
                            idleTicks = 0
                        }
                        continue
                    }
                    idleTicks = 0
                    if (snap.text.length > sent.length && snap.text.startsWith(sent)) {
                        val delta = snap.text.substring(sent.length)
                        sent = snap.text
                        pipeOut.write(deltaChunk(cid, created, model, delta))
                        pipeOut.flush()
                    } else if (snap.text.isNotEmpty() && snap.text != sent) {
                        // 上游把整段重写了（极少见）：把完整文本当新内容推一次
                        sent = snap.text
                        pipeOut.write(deltaChunk(cid, created, model, snap.text))
                        pipeOut.flush()
                    }
                    if (snap.completed) break
                }
                // 上游没给 usage，这里按「约 3 字符 1 token」估一个，至少让客户端有数
                pipeOut.write(finishChunk(cid, created, model, sent.length / 3))
                pipeOut.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                pipeOut.flush()
            } catch (_: Exception) {
                // 客户端断开（PipedInputStream 关闭）是正常路径，不当作错误
            } finally {
                runCatching { pipeOut.close() }
            }
        }
        worker.isDaemon = true
        worker.name = "today-stream"
        worker.start()

        return StreamingChatCall(200, pipeIn) {
            closed.set(true)
            worker.interrupt()
        }
    }

    private fun sse(obj: JsonObject): ByteArray =
        ("data: $obj\n\n").toByteArray(StandardCharsets.UTF_8)

    private fun baseChunk(cid: String, created: Long, model: String): JsonObject = JsonObject().apply {
        addProperty("id", cid)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", model)
    }

    private fun roleChunk(cid: String, created: Long, model: String): ByteArray {
        val obj = baseChunk(cid, created, model)
        val delta = JsonObject().apply { addProperty("role", "assistant") }
        obj.add("choices", choices(delta, null))
        return sse(obj)
    }

    private fun deltaChunk(cid: String, created: Long, model: String, delta: String): ByteArray {
        if (delta.isEmpty()) return ByteArray(0)
        val obj = baseChunk(cid, created, model)
        val payload = JsonObject().apply { addProperty("content", delta) }
        obj.add("choices", choices(payload, null))
        return sse(obj)
    }

    private fun finishChunk(cid: String, created: Long, model: String, completionTokens: Int): ByteArray {
        val obj = baseChunk(cid, created, model)
        obj.add("choices", choices(JsonObject(), "stop"))
        obj.add("usage", JsonObject().apply {
            addProperty("prompt_tokens", 0)
            addProperty("completion_tokens", completionTokens)
            addProperty("total_tokens", completionTokens)
        })
        return sse(obj)
    }

    private fun choices(delta: JsonObject, finish: String?): JsonArray = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finish == null) add("finish_reason", JsonNull.INSTANCE) else addProperty("finish_reason", finish)
        })
    }

    // ------------------------------------------------------------------ 非流式

    private fun completionJson(model: String, text: String): String {
        val obj = JsonObject().apply {
            addProperty("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""))
            addProperty("object", "chat.completion")
            addProperty("created", nowMillis() / 1000)
            addProperty("model", model)
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    add("message", JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", text)
                    })
                    addProperty("finish_reason", "stop")
                })
            })
        }
        return obj.toString()
    }

    // ------------------------------------------------------------------ 额度

    /**
     * 套餐用量：`GET /v1/pricing/status` → `data.status.usage`。
     *
     * 上游只给百分比（`progress`）或「剩余 62%」这类文案，没有绝对额度，
     * 所以 [CreditInfo.balance] 放「剩余百分比」而不是 token 数，detail 里写清套餐名。
     */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = TodayAccount.parse(account.secret) ?: return null
        val raw = runCatching { apiCall(credential, "GET", "/v1/pricing/status", null, null, 30_000) }
            .getOrNull() ?: return null
        val status = runCatching { JsonParser.parseString(raw).asJsonObject }
            .getOrNull()?.objOrNull("data")?.objOrNull("status") ?: return null

        val plan = status.str("plan")
        val usage = status.objOrNull("usage")
        val progress = usage?.get("progress")?.takeIf { it.isJsonPrimitive }?.asDouble
        val percentUsed = progress ?: usage?.str("progressText")?.let { text ->
            val num = text.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
            if (text.contains("剩余") && num != null) 100.0 - num else num
        }
        if (percentUsed == null) {
            return CreditInfo(balance = 0, known = false, detail = plan.ifEmpty { "套餐信息不可用" })
        }
        val remain = (100.0 - percentUsed).coerceIn(0.0, 100.0)
        val detail = buildString {
            if (plan.isNotEmpty()) append("套餐 ").append(plan)
            if (isNotEmpty()) append(" · ")
            append("已用 ").append(String.format("%.0f", percentUsed)).append("%")
        }
        return CreditInfo(balance = remain.toLong(), known = true, detail = detail)
    }

    // ------------------------------------------------------------------ 错误

    override fun classify(status: Int, body: String): UpstreamError {
        val short = trimBody(body)
        return when {
            status == 401 || status == 403 ->
                UpstreamError(ErrorKind.SESSION_DEAD, "Today 登录态失效，请重新粘贴 session token", status.toLong())
            status == 429 ->
                UpstreamError(ErrorKind.SOFT_RATE, "Today 触发限流，稍后重试", 429)
            status == 404 ->
                UpstreamError(ErrorKind.NOT_FOUND, "Today 接口不存在（$short）", 404)
            status in 400..499 ->
                UpstreamError(ErrorKind.CLIENT, "Today 拒绝了请求（$status）：$short", status.toLong())
            status >= 500 ->
                UpstreamError(ErrorKind.SERVER, "Today 上游错误 $status", status.toLong())
            else ->
                UpstreamError(ErrorKind.NETWORK, "Today 连接失败：$short", status.toLong())
        }
    }

    companion object {
        const val ID = "today"

        /** 轮询间隔：上游写入是流式的，700ms 足够跟上又不会把手机网络打满。 */
        private const val POLL_INTERVAL_MS = 700L

        /** 等第一段内容的上限；超时就认为这个号不行，让网关换号。 */
        private const val FIRST_TEXT_TIMEOUT_MS = 90_000L

        /** 整轮回复的总上限（含思考模型）。 */
        private const val TOTAL_TIMEOUT_MS = 300_000L

        private const val PIPE_BUFFER = 64 * 1024

        /** 模型选项的发现结果缓存时长：上游上新不频繁，10 分钟足够。 */
        private const val DISCOVER_TTL_MS = 10 * 60 * 1000L
    }
}
