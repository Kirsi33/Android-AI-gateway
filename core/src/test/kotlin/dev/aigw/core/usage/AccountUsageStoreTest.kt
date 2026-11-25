package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 账号维度日用量：累加、余额差值推算、跨天不误算。
 *
 * 这几条是账号维度统计的正确性底线——尤其是「余额变大不算消耗」和「跨天不推算」，
 * 少了任一条，界面上就会出现「昨天充值被记成今天没花」或「日切回血被记成负消耗」。
 */
class AccountUsageStoreTest {

    private fun storeWith(inner: InMemoryKeyValueStore) = AccountUsageStore(inner)

    private fun record(
        uid: String,
        at: Long,
        prompt: Long = 100,
        completion: Long = 10,
        cached: Long = 0,
        points: Long = 0,
        nickname: String = "",
        providerId: String = "trae",
    ) = CallRecord(
        id = "$uid-$at",
        startedAtMillis = at,
        providerId = providerId,
        model = "trae-cn/glm-5.3-flash",
        accountUid = uid,
        accountNickname = nickname,
        streaming = true,
        status = CallStatus.SUCCESS,
        httpStatus = 200,
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = prompt + completion,
        cachedTokens = cached,
        pointsConsumed = points,
        durationMillis = 1000,
        error = "",
        requestBody = "",
        responseBody = "",
    )

    @Test
    fun `同一账号多次调用累加到当天`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.addCall(record("u1", now, prompt = 100, completion = 10))
        store.addCall(record("u1", now + 1000, prompt = 200, completion = 20))

        val today = store.today(now)
        assertEquals(1, today.size)
        assertEquals(2L, today[0].requests)
        assertEquals(300L, today[0].promptTokens)
        assertEquals(30L, today[0].completionTokens)
    }

    @Test
    fun `不同账号分开统计且按消耗降序`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.addCall(record("u1", now, points = 5))
        store.addCall(record("u2", now, points = 50))

        val today = store.today(now)
        assertEquals(2, today.size)
        assertEquals("u2", today[0].uid)
        assertEquals(50L, today[0].creditsUsed)
    }

    @Test
    fun `余额下降推算为消耗`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        // 首次快照只记基线，不算消耗
        store.recordBalance("trae", "u1", 1000, now)
        store.recordBalance("trae", "u1", 880, now + 1000)

        val today = store.today(now)
        assertEquals(1, today.size)
        assertEquals(120L, today[0].creditsUsed)
        // 估算口径已停用（只信余额差值）；creditsEstimated 保留字段兼容旧数据，恒为 0
        assertEquals(0L, today[0].creditsEstimated)
    }

    @Test
    fun `余额变大不算消耗`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.recordBalance("trae", "u1", 100, now)
        store.recordBalance("trae", "u1", 5000, now + 1000)

        assertTrue(store.today(now).isEmpty())
    }

    @Test
    fun `跨天不推算消耗`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.recordBalance("trae", "u1", 1000, now)
        store.recordBalance("trae", "u1", 800, now + 86_400_000L)

        assertTrue(store.today(now + 86_400_000L).isEmpty())
    }

    @Test
    fun `同一区间的直报与余额差值取大不重复计`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.recordBalance("trae", "u1", 1000, now)
        store.addCall(record("u1", now + 100, points = 30))
        store.recordBalance("trae", "u1", 900, now + 200)

        val today = store.today(now)
        assertEquals(1, today.size)
        // 余额差值 100 已经覆盖了直报的 30（同一笔消耗），取大 = 100，不能加成 130
        assertEquals(100L, today[0].creditsUsed)
        assertEquals(100L, today[0].creditsMeasured)
        assertEquals(30L, today[0].creditsReported)
        assertEquals(2, today[0].creditsVersion)
    }

    @Test
    fun `修复前的历史行保持旧口径不会被新调用洗白`() {
        val inner = InMemoryKeyValueStore()
        val now = System.currentTimeMillis()
        val day = dev.aigw.core.util.startOfDay(now)
        // 模拟修复前落盘的行：没有 creditsVersion / creditsReported
        inner.write(
            "usage/account/$day/trae/u1",
            """{"providerId":"trae","uid":"u1","creditsUsed":196,"creditsMeasured":100,"creditsEstimated":0}""",
        )
        val store = storeWith(inner)
        store.addCall(record("u1", now + 100, points = 10))

        val row = store.today(now)[0]
        assertEquals(0, row.creditsVersion, "历史行不应被新调用升级成已修复口径")
        assertEquals(206L, row.creditsUsed)
    }

    @Test
    fun `直报大于余额差值时以直报为准`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.recordBalance("trae", "u1", 1000, now)
        store.addCall(record("u1", now + 100, points = 80))
        // 差值只有 50（例如部分扣费还没反映到该账本）；直报 80 更完整
        store.recordBalance("trae", "u1", 950, now + 200)

        val today = store.today(now)
        assertEquals(80L, today[0].creditsUsed)
        assertEquals(50L, today[0].creditsMeasured)
        assertEquals(80L, today[0].creditsReported)
    }

    @Test
    fun `快照区间的直报只抵扣一次`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.recordBalance("trae", "u1", 1000, now)
        store.addCall(record("u1", now + 100, points = 30))
        store.recordBalance("trae", "u1", 900, now + 200) // 区间一：max(100, 30) = 100
        store.addCall(record("u1", now + 300, points = 20))
        store.recordBalance("trae", "u1", 870, now + 400) // 区间二：max(30, 20) = 30

        val today = store.today(now)
        assertEquals(130L, today[0].creditsUsed)
        assertEquals(50L, today[0].creditsReported)
    }

    @Test
    fun `清空后账号用量归零`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.addCall(record("u1", now))
        store.clear()
        assertTrue(store.today(now).isEmpty())
    }

    @Test
    fun `新实例从存储重新读出数据`() {
        val inner = InMemoryKeyValueStore()
        val now = System.currentTimeMillis()
        storeWith(inner).addCall(record("u1", now, prompt = 500))

        // 新实例 = 下次启动：内存缓存是空的，必须从存储里读回来
        val reopened = storeWith(inner)
        val today = reopened.today(now)
        assertEquals(1, today.size)
        assertEquals(500L, today[0].promptTokens)
    }

    @Test
    fun `昵称空时不覆盖已有昵称`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.addCall(record("u1", now, nickname = "张三"))
        store.addCall(record("u1", now + 1000, nickname = ""))

        assertEquals("张三", store.today(now)[0].nickname)
    }

    @Test
    fun `addCall 只累 token 不估算积分`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.addCall(
            record("u1", now, prompt = 900_000, completion = 100_000).copy(model = "trae-cn/glm-5.3-flash"),
        )

        // 没有余额差值就没法知道真实消耗；绝不拿倍率表硬凑一个数字出来
        val row = store.today(now)[0]
        assertEquals(0L, row.creditsUsed)
        assertEquals(1_000_000L, row.totalTokens)
    }

    @Test
    fun `估算接口按参考倍率计算`() {
        val store = storeWith(InMemoryKeyValueStore())
        // glm-5.3-flash 参考表倍率 0.08：1,000,000 token -> 80 积分
        val credits = store.estimateCredits(
            record("u1", 0, prompt = 900_000, completion = 100_000).copy(model = "trae-cn/glm-5.3-flash"),
        )
        assertEquals(80L, credits)
    }

    @Test
    fun `余额差值会校准倍率并优先于参考表`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        // 实测：100 万 token 只扣了 10 积分（真实倍率约 0.01，远低于参考表的 0.08）
        store.addCall(
            record("u1", now, prompt = 900_000, completion = 100_000).copy(model = "trae-cn/glm-5.3-flash"),
        )
        store.recordBalance("trae", "u1", 1000, now)
        store.recordBalance("trae", "u1", 990, now + 1000)

        assertEquals(10L, store.today(now)[0].creditsUsed)
        assertEquals(10L, store.today(now)[0].creditsMeasured)

        // 校准后估算应贴近实测（约 0.01），而不是参考表的 0.08
        val learned = store.learnedRate("glm-5.3-flash")
        assertTrue(learned != null && learned < 0.02, "校准倍率应约为 0.01，实际 $learned")
        val estimated = store.estimateCredits(
            record("u2", 0, prompt = 900_000, completion = 100_000).copy(model = "trae-cn/glm-5.3-flash"),
        )
        assertEquals(10L, estimated)
    }

    @Test
    fun `倍率表里没有的模型不估值`() {
        val store = storeWith(InMemoryKeyValueStore())
        val credits = store.estimateCredits(
            record("u1", 0, prompt = 900_000, completion = 100_000).copy(model = "trae-cn/unknown-model-xyz"),
        )
        // 查不到倍率就不猜，留 0 让界面显示「—」
        assertEquals(0L, credits)
    }

    @Test
    fun `非 trae 供应商不套倍率表`() {
        val store = storeWith(InMemoryKeyValueStore())
        val credits = store.estimateCredits(
            record("u1", 0, prompt = 900_000, completion = 100_000, providerId = "codebuddy")
                .copy(model = "glm-5.3-flash"),
        )
        assertEquals(0L, credits)
    }

    @Test
    fun `上游直报的消耗直接计入`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.addCall(
            record("u1", now, prompt = 900_000, completion = 100_000, points = 42)
                .copy(model = "trae-cn/glm-5.3-flash"),
        )

        val row = store.today(now)[0]
        assertEquals(42L, row.creditsUsed)
        // 上游直报是精确值，不该被标成估算
        assertEquals(0L, row.creditsEstimated)
    }
}
