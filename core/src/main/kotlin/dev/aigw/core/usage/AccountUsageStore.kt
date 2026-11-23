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
        val merged = prev.copy(
            // 昵称可能这轮才有（登录前是空），有值就刷新
            nickname = record.accountNickname.ifEmpty { prev.nickname },
            requests = prev.requests + 1,
            promptTokens = prev.promptTokens + record.promptTokens,
            completionTokens = prev.completionTokens + record.completionTokens,
            totalTokens = prev.totalTokens + record.totalTokens,
            cachedTokens = prev.cachedTokens + record.cachedTokens,
            creditsUsed = prev.creditsUsed + record.pointsConsumed,
        )
        map[key] = merged
        persist(day, merged)
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
