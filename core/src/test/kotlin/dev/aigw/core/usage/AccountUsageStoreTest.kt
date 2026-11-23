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
        assertEquals(120L, today[0].creditsEstimated)
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
    fun `上游直报消耗与余额推算相加`() {
        val store = storeWith(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        store.recordBalance("trae", "u1", 1000, now)
        store.addCall(record("u1", now + 100, points = 30))
        store.recordBalance("trae", "u1", 900, now + 200)

        val today = store.today(now)
        assertEquals(1, today.size)
        assertEquals(130L, today[0].creditsUsed)
        assertEquals(100L, today[0].creditsEstimated)
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
}