package dev.aigw.core.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.startOfDay

/**
 * 单个账号在某一天的消耗聚合。
 *
 * 与 [UsageStats] 的分工：那个是**全局**口径（所有账号混在一起），本类是**账号维度**，
 * 回答「这个号今天花了多少」。两者并存，互不替代。
 */
data class AccountDayUsage(
    /** 供应商 id（如 `trae`、`custom:mysite`）。 */
    val providerId: String,
    val uid: String,
    /** 账号昵称；空则界面回落显示 uid 尾部。 */
    val nickname: String = "",
    val requests: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedTokens: Long = 0,
    /**
     * 今日消耗的积分/额度。
     *
     * 三个来源，按可信度从高到低：
     * 1. **余额差值（实测，最准）**——Trae 只回报「还剩多少」（`ide_credits`），
     *    前后两次余额做差就是真实消耗。见 [AccountUsageStore.recordBalance]。
     * 2. 上游在 usage / chunk 里直接回报的消耗（`points_consumed` 等，
     *    见 GatewayHttpServer.pointsConsumedOf）。
     * 3. **token × 倍率估算（兜底）**——上面两条都拿不到时用，误差可达数倍，
     *    见 [AccountUsageStore.estimateCredits]。
     */
    val creditsUsed: Long = 0,
    /** 上面 [creditsUsed] 里来自第 2、3 种来源的部分（界面标注「估算」，与实测值区别）。 */
    val creditsEstimated: Long = 0,
    /** 其中来自余额差值（实测）的部分，供界面区分「实测 vs 估算」的占比。 */
    val creditsMeasured: Long = 0,
)

/**
 * 按「天 × 账号」记录用量。
 *
 * 为什么要独立一份而不是复用 [CallLogStore] 的每日聚合：
 * - [CallLogStore] 的记录有容量上限（默认 100 条），超出的会被淘汰，账号维度的日累计会因此少算；
 * - 每日聚合 [UsageStats] 是全局的，加账号维度意味着改所有既有读写路径（上游每次更新都冲突）。
 *
 * 这里只写自己的键（`usage/account/<day>/<providerId>/<uid>`），不碰任何既有键，
 * 因此对上游更新的兼容性最好。
 */
class AccountUsageStore(
    private val store: KeyValueStore,
    /** 保留天数；与 [CallLogStore.RETENTION_DAYS] 对齐即可。 */
    private val retentionDays: Int = CallLogStore.RETENTION_DAYS,
) {
    /** 当天的账号聚合，键为 `providerId/uid`。惰性载入，避免每次 add 都读存储。 */
    private val cache = mutableMapOf<Long, MutableMap<String, AccountDayUsage>>()

    /** 记录一次调用的 token / 请求数 / 上游直报的积分消耗。 */
    @Synchronized
    fun addCall(record: CallRecord) {
        val day = startOfDay(record.startedAtMillis)
        val key = accountKey(record.providerId, record.accountUid)
        val map = dayMap(day)
        val prev = map[key] ?: AccountDayUsage(record.providerId, record.accountUid, record.accountNickname)
        // 上游直报优先；没有就先只累 token，等余额差值到来时再补真实消耗。
        // 这一轮**不再**做倍率估算——估算误差可达 10 倍（实测 glm-5.3-flash 真实倍率
        // 约 0.0077，而本地参考表写的 0.08），把它和实测值混在一起会让界面数字不可信。
        val reported = record.pointsConsumed
        val merged = prev.copy(
            // 昵称可能这轮才有（登录前是空），有值就刷新
            nickname = record.accountNickname.ifEmpty { prev.nickname },
            requests = prev.requests + 1,
            promptTokens = prev.promptTokens + record.promptTokens,
            completionTokens = prev.completionTokens + record.completionTokens,
            totalTokens = prev.totalTokens + record.totalTokens,
            cachedTokens = prev.cachedTokens + record.cachedTokens,
            creditsUsed = prev.creditsUsed + reported,
            // 估算口径已停用：这一轮不再按 token×倍率记账，creditsEstimated 只兼容保留
            creditsEstimated = prev.creditsEstimated,
        )
        map[key] = merged
        persist(day, merged)
        // 记下这个账号最后一轮用的模型与 token，供余额差值到达时归因
        store.write(
            lastCallKey(record.providerId, record.accountUid),
            JsonObject().apply {
                addProperty("model", record.model)
                addProperty("tokens", record.totalTokens)
                addProperty("day", day)
            }.toString(),
        )
    }

    /**
     * 用一次「当前余额」快照推算本轮消耗。
     *
     * 这是**最准的口径**：Trae 的余额是服务端实扣后的真实值，两次快照相减就是真实消耗。
     * 只在同一天内、同一账号的两次快照之间做差；跨天、首次见到该账号、
     * 或余额变大（充值/日切回血）时都不算消耗——宁可少记也不虚报。
     *
     * 拿到真实消耗后顺带**校准倍率**（[learnRate]）：用「实测消耗 ÷ 本轮 token」反推
     * 该模型的真实倍率存起来，下次没有余额差值可用时，估算就是按实测校准过的值。
     */
    @Synchronized
    fun recordBalance(providerId: String, uid: String, credits: Long, nowMillis: Long) {
        val day = startOfDay(nowMillis)
        val key = accountKey(providerId, uid)
        val lastKey = "$BALANCE_PREFIX$key"
        val last = store.read(lastKey)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        store.write(lastKey, JsonObject().apply {
            addProperty("credits", credits)
            addProperty("day", day)
        }.toString())
        if (last == null) return
        // 跨天则不推算：昨天的余额和今天的消耗不是一回事
        if (last.get("day")?.asLong != day) return
        val prev = last.get("credits")?.asLong ?: return
        val delta = prev - credits
        if (delta <= 0) return

        // 归因到上一轮调用的模型，用来校准倍率
        val lastCall = store.read(lastCallKey(providerId, uid))
            ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        if (lastCall != null && lastCall.get("day")?.asLong == day) {
            val model = lastCall.get("model")?.asString.orEmpty()
            val tokens = lastCall.get("tokens")?.asLong ?: 0L
            if (model.isNotEmpty() && tokens > 0) learnRate(model, tokens, delta)
        }

        val map = dayMap(day)
        val current = map[key] ?: AccountDayUsage(providerId, uid)
        // 实测值优先：这轮如果之前记过估算，先用实测覆盖掉那部分（估算明显不可信）
        val merged = current.copy(
            creditsUsed = current.creditsUsed + delta,
            creditsMeasured = current.creditsMeasured + delta,
        )
        map[key] = merged
        persist(day, merged)
    }

    /**
     * 按「token × 倍率」估算消耗，作为拿不到余额差值时的兜底。
     *
     * 倍率查询顺序：
     * 1. **本机校准值**（[learnedRate]）——用真实余额差值反推出来的，最接近实际；
     * 2. [dev.aigw.core.provider.trae.ModelRates] 的本地参考表。
     *
     * 两者都没有就返回 0（界面显示「—」），绝不瞎猜。
     * 注意校准值只存在本机（跟着账号数据走），不写进代码，避免把某个账号的
     * 会员折扣当成所有用户的通用倍率。
     */
    fun estimateCredits(record: CallRecord): Long {
        // 只有 Trae 的模型倍率有据可查；别家用各自的上游计费口径，不套这张表
        if (!record.providerId.startsWith("trae")) return 0L
        val tokens = record.totalTokens.takeIf { it > 0 } ?: return 0L
        // 网关对外模型名形如 `trae-cn/glm-5.3-flash`，取最后一段查倍率
        val modelId = modelIdOf(record.model)
        if (modelId.isEmpty()) return 0L

        val learned = learnedRate(modelId)
        val multiplier = learned
            ?: dev.aigw.core.provider.trae.ModelRates.rateOf(modelId)?.takeIf { !it.isFree }?.let {
                // 优先用官方公告的折扣后倍率，没有折扣则用基准倍率
                it.discounted ?: it.base
            }
            ?: return 0L
        if (multiplier <= 0) return 0L
        val credits = tokens.toDouble() / 1000.0 * multiplier
        // 小额不足 1 积分时向上取整：Trae 按次扣费，一次对话最少扣 1 积分
        return if (credits <= 0) 0L else credits.toLong().coerceAtLeast(1L)
    }

    /**
     * 首次启用本功能时，把调用记录里当天的历史回填进来。
     *
     * 为什么需要：账号维度统计是后加的，升级前已经跑过的请求只存在 [CallLogStore] 里，
     * 不补的话界面上「今日请求 143」与「各账号 2 次」会自相矛盾。
     *
     * 幂等：只有当天的账号统计数据**完全为空**才回填；已有数据一律不动，
     * 避免把实时累加出的精确值（含余额差值）覆盖成重算值。
     * 局限：只能回填 [CallLogStore] 里还留着的记录（受其容量上限约束），更早的无从补起。
     */
    @Synchronized
    fun backfillFrom(records: List<CallRecord>, nowMillis: Long) {
        val day = startOfDay(nowMillis)
        if (dayMap(day).isNotEmpty()) return
        val todayRecords = records.filter { startOfDay(it.startedAtMillis) == day }
        if (todayRecords.isEmpty()) return
        todayRecords.forEach { addCall(it) }
    }

    /** [nowMillis] 所在天的全部账号用量，按消耗从高到低（无消耗的排后面，再按请求数）。 */
    @Synchronized
    fun today(nowMillis: Long): List<AccountDayUsage> {
        val map = dayMap(startOfDay(nowMillis))
        return sorted(map.values)
    }

    /** 任意一天的账号用量。 */
    @Synchronized
    fun day(dayMillis: Long): List<AccountDayUsage> {
        val map = dayMap(startOfDay(dayMillis))
        return sorted(map.values)
    }

    /** 清空全部账号维度用量（与「清空调用记录」一起使用）。 */
    @Synchronized
    fun clear() {
        for (key in store.keys(DAY_PREFIX)) store.delete(key)
        for (key in store.keys(BALANCE_PREFIX)) store.delete(key)
        for (key in store.keys(LAST_CALL_PREFIX)) store.delete(key)
        for (key in store.keys(RATE_PREFIX)) store.delete(key)
        cache.clear()
    }

    /** 删除保留窗口之外的日键。 */
    @Synchronized
    fun prune(nowMillis: Long) {
        val cutoff = startOfDay(nowMillis) - (retentionDays - 1) * 86_400_000L
        for (key in store.keys(DAY_PREFIX)) {
            val day = key.removePrefix(DAY_PREFIX).substringBefore('/').toLongOrNull() ?: continue
            if (day < cutoff) store.delete(key)
        }
        for (day in cache.keys.filter { it < cutoff }) cache.remove(day)
    }

    // ---------------------------------------------------------------- 倍率自校准

    /**
     * 用一次真实消耗校准模型倍率。
     *
     * 只采信「单轮消耗」样本：一次对话的 token 与它引起的余额差值是配对的，
     * 而多轮合并会让归因失真。用指数滑动平均（新样本权重 0.5）平滑，
     * 避免某次上游活动价把长期倍率带偏。
     */
    private fun learnRate(model: String, tokens: Long, credits: Long) {
        val modelId = modelIdOf(model)
        if (modelId.isEmpty() || tokens <= 0 || credits <= 0) return
        val observed = credits.toDouble() * 1000.0 / tokens
        if (observed <= 0 || observed > 10) return // 离谱样本丢弃（单位错/余额跳动）
        val key = "$RATE_PREFIX$modelId"
        val prev = store.read(key)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val prevRate = prev?.get("rate")?.asDouble
        val prevCount = prev?.get("samples")?.asLong ?: 0L
        // 首样本直接用；之后指数滑动平均，让长期值稳定
        val next = if (prevRate == null || prevCount == 0L) observed else prevRate * 0.5 + observed * 0.5
        store.write(key, JsonObject().apply {
            addProperty("rate", next)
            addProperty("samples", prevCount + 1)
        }.toString())
    }

    /** 读取本机校准倍率；没有校准过返回 null（调用方回落到参考表）。 */
    @Synchronized
    fun learnedRate(modelId: String): Double? {
        val id = modelIdOf(modelId)
        if (id.isEmpty()) return null
        val raw = store.read("$RATE_PREFIX$id") ?: return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        return obj.get("rate")?.asDouble?.takeIf { it > 0 }
    }

    /** 已校准的模型倍率快照，供界面/诊断查看。 */
    @Synchronized
    fun learnedRates(): Map<String, Double> = buildMap {
        for (key in store.keys(RATE_PREFIX)) {
            val modelId = key.removePrefix(RATE_PREFIX)
            learnedRate(modelId)?.let { put(modelId, it) }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun sorted(values: Collection<AccountDayUsage>): List<AccountDayUsage> = values.sortedWith(
        compareByDescending<AccountDayUsage> { it.creditsUsed }
            .thenByDescending { it.totalTokens }
            .thenByDescending { it.requests },
    )

    private fun dayMap(day: Long): MutableMap<String, AccountDayUsage> =
        cache.getOrPut(day) { loadDay(day).associateBy { accountKey(it.providerId, it.uid) }.toMutableMap() }

    private fun loadDay(day: Long): List<AccountDayUsage> =
        store.keys("$DAY_PREFIX$day/").mapNotNull { key ->
            val raw = store.read(key) ?: return@mapNotNull null
            runCatching { fromJson(raw) }.getOrNull()
        }

    private fun persist(day: Long, usage: AccountDayUsage) {
        store.write(keyOf(day, usage.providerId, usage.uid), usage.toJson().toString())
        prune(System.currentTimeMillis())
    }

    private fun keyOf(day: Long, providerId: String, uid: String) = "$DAY_PREFIX$day/$providerId/$uid"

    private fun accountKey(providerId: String, uid: String) = "$providerId/$uid"

    private fun lastCallKey(providerId: String, uid: String) = "$LAST_CALL_PREFIX$providerId/$uid"

    /** 从 `trae-cn/glm-5.3-flash` 之类的全名里取出模型 id。 */
    private fun modelIdOf(model: String): String =
        model.substringAfterLast('/').substringBefore(':').trim().lowercase()

    private fun AccountDayUsage.toJson(): JsonObject = JsonObject().apply {
        addProperty("providerId", providerId)
        addProperty("uid", uid)
        addProperty("nickname", nickname)
        addProperty("requests", requests)
        addProperty("promptTokens", promptTokens)
        addProperty("completionTokens", completionTokens)
        addProperty("totalTokens", totalTokens)
        addProperty("cachedTokens", cachedTokens)
        addProperty("creditsUsed", creditsUsed)
        addProperty("creditsEstimated", creditsEstimated)
        addProperty("creditsMeasured", creditsMeasured)
    }

    private fun fromJson(raw: String): AccountDayUsage {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return AccountDayUsage("", "")
        return AccountDayUsage(
            providerId = obj.get("providerId")?.asString.orEmpty(),
            uid = obj.get("uid")?.asString.orEmpty(),
            nickname = obj.get("nickname")?.asString.orEmpty(),
            requests = obj.get("requests")?.asLong ?: 0L,
            promptTokens = obj.get("promptTokens")?.asLong ?: 0L,
            completionTokens = obj.get("completionTokens")?.asLong ?: 0L,
            totalTokens = obj.get("totalTokens")?.asLong ?: 0L,
            cachedTokens = obj.get("cachedTokens")?.asLong ?: 0L,
            creditsUsed = obj.get("creditsUsed")?.asLong ?: 0L,
            creditsEstimated = obj.get("creditsEstimated")?.asLong ?: 0L,
            creditsMeasured = obj.get("creditsMeasured")?.asLong ?: 0L,
        )
    }

    private companion object {
        /** 独立命名空间：不占用 `usage/` 前缀，避免与上游的日聚合键混在一起。 */
        const val DAY_PREFIX = "usage/account/"
        /** 上次余额快照的键前缀。 */
        const val BALANCE_PREFIX = "usage/balance/"
        /** 每个账号最后一次调用的模型与 token，供余额差值归因。 */
        const val LAST_CALL_PREFIX = "usage/lastcall/"
        /** 本机校准出来的模型倍率。 */
        const val RATE_PREFIX = "usage/rate/"
    }
}