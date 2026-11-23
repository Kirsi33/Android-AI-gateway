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
     * 两种来源合并到这一个字段（谁有值用谁，都有则相加以防漏记）：
     * 1. 上游在 usage / chunk 里直接回报的消耗（`points_consumed` 等，见 GatewayHttpServer.pointsConsumedOf）
     * 2. 余额差值推算——Trae 的 SOLO 通道只回报「还剩多少积分」（`notify_usage.ide_credits`），
     *    不回报消耗量，只能靠前后两次余额做差。见 [AccountUsageStore.recordBalance]
     */
    val creditsUsed: Long = 0,
    /** 余额差值推算的那部分（用于界面标注「估算」，与上游直报的精确值区分）。 */
    val creditsEstimated: Long = 0,
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
        // 积分有三个来源，优先级从高到低：
        // 1. 上游直报的消耗字段（精确，但 Trae 不回报）
        // 2. token × 模型倍率估算（Trae 的唯一可行路径，见 ModelRates）
        // 前两者都不成立时留 0，界面显示「—」而不是编一个数字。
        val reported = record.pointsConsumed
        val estimated = if (reported > 0) 0L else estimateCredits(record)
        val merged = prev.copy(
            // 昵称可能这轮才有（登录前是空），有值就刷新
            nickname = record.accountNickname.ifEmpty { prev.nickname },
            requests = prev.requests + 1,
            promptTokens = prev.promptTokens + record.promptTokens,
            completionTokens = prev.completionTokens + record.completionTokens,
            totalTokens = prev.totalTokens + record.totalTokens,
            cachedTokens = prev.cachedTokens + record.cachedTokens,
            creditsUsed = prev.creditsUsed + reported + estimated,
            creditsEstimated = prev.creditsEstimated + estimated,
        )
        map[key] = merged
        persist(day, merged)
    }

    /**
     * 按「token × 模型倍率」估算本次消耗。
     *
     * 公式：积分 = 总 token ÷ 1000 × 倍率（与 Trae 官方扣费口径一致：glm-5.3-flash
     * 倍率 0.08 时，445 万 token 约合 356 积分）。
     *
     * 只对能在 [dev.aigw.core.provider.trae.ModelRates] 里查到倍率的模型生效——
     * 查不到就返回 0（界面显示「—」），绝不瞎猜一个倍率出来。
     *
     * 注意这是**估算**：倍率会随会员档位、限时活动、忙闲时段浮动，官方文档也明确说
     * 「同一模型在不同用户身份和时段看到的消耗速度不同」，所以界面必须标注估算。
     */
    private fun estimateCredits(record: CallRecord): Long {
        // 只有 Trae 的模型倍率有据可查；别家用各自的上游计费口径，不套这张表
        if (!record.providerId.startsWith("trae")) return 0L
        val tokens = record.totalTokens.takeIf { it > 0 } ?: return 0L
        // 网关对外模型名形如 `trae-cn/glm-5.3-flash`，取最后一段查倍率
        val modelId = record.model.substringAfterLast('/').substringBefore(':').trim()
        if (modelId.isEmpty()) return 0L
        val rate = dev.aigw.core.provider.trae.ModelRates.rateOf(modelId) ?: return 0L
        if (rate.isFree) return 0L
        // 优先用官方公告的折扣后倍率，没有折扣则用基准倍率
        val multiplier = rate.discounted ?: rate.base
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

    /**
     * 用一次「当前余额」快照推算本轮消耗。
     *
     * 只在**同一天内、同一账号的两次快照之间**做差；跨天、首次见到该账号、
     * 或余额变大（充值/刷新/日切回血）时都不算消耗——宁可少记也不虚报。
     *
     * 注意：调用方必须只在**对话计费回报**时调用（Trae 的 `notify_usage`），
     * 不要在定期刷新额度时调用，否则手动刷新引起的余额变化会被误算成消耗。
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
        val map = dayMap(day)
        val current = map[key] ?: AccountDayUsage(providerId, uid)
        val merged = current.copy(
            creditsUsed = current.creditsUsed + delta,
            creditsEstimated = current.creditsEstimated + delta,
        )
        map[key] = merged
        persist(day, merged)
    }

    /** [nowMillis] 所在天的全部账号用量，按消耗从高到低（无消耗的排后面，再按请求数）。 */
    @Synchronized
    fun today(nowMillis: Long): List<AccountDayUsage> {
        val map = dayMap(startOfDay(nowMillis))
        return map.values.sortedWith(
            compareByDescending<AccountDayUsage> { it.creditsUsed }
                .thenByDescending { it.totalTokens }
                .thenByDescending { it.requests },
        )
    }

    /** 任意一天的账号用量。 */
    @Synchronized
    fun day(dayMillis: Long): List<AccountDayUsage> {
        val map = dayMap(startOfDay(dayMillis))
        return map.values.sortedWith(
            compareByDescending<AccountDayUsage> { it.creditsUsed }
                .thenByDescending { it.totalTokens }
                .thenByDescending { it.requests },
        )
    }

    /** 清空全部账号维度用量（与「清空调用记录」一起使用）。 */
    @Synchronized
    fun clear() {
        for (key in store.keys(DAY_PREFIX)) store.delete(key)
        for (key in store.keys(BALANCE_PREFIX)) store.delete(key)
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

    // ---------------------------------------------------------------- 内部

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
        )
    }

    private companion object {
        /** 独立命名空间：不占用 `usage/` 前缀，避免与上游的日聚合键混在一起。 */
        const val DAY_PREFIX = "usage/account/"
        /** 上次余额快照的键前缀。 */
        const val BALANCE_PREFIX = "usage/balance/"
    }
}
