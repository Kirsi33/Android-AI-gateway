package dev.aigw.core.provider.codebuddy

import dev.aigw.core.provider.ProviderModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 验证内置模型目录能被读到，且**带上了倍率等 extra 信息**。
 *
 * 这条用例是为了防「模型列表能显示、但倍率/能力位全是空」的回归：
 * 数据源缺失时 [CodeBuddyProvider.listModels] 会退回极简清单，界面看起来正常，
 * 但所有附加信息都没了，肉眼很难发现。
 */
class CodeBuddyCatalogTest {

    @Test
    fun `内置目录能加载到模型且带倍率`() {
        val models = CodeBuddyProvider.listModelsForTest()
        assertTrue(models.isNotEmpty(), "内置目录应能读到模型")

        val withRate = models.filter { !it.extra["rate"].isNullOrEmpty() }
        println("模型总数=${models.size}，带倍率=${withRate.size}")
        println("前 5 条：")
        models.take(5).forEach { m ->
            println("  ${m.id} -> extra=${m.extra}")
        }
        assertTrue(withRate.isNotEmpty(), "至少有模型应带 rate（倍率）")
    }

    @Test
    fun `默认模型是官方 Auto`() {
        val provider = CodeBuddyProvider()
        val resolved = provider.resolveModel("auto")
        println("resolveModel(auto) = $resolved")
        assertTrue(resolved == "default-model", "默认模型应为官方 default-model，实际=$resolved")
    }

    @Test
    fun `内置目录里的模型 id 都真实存在`() {
        val models = CodeBuddyProvider.listModelsForTest()
        val ids = models.map(ProviderModel::id).toSet()
        // 旧版曾用过这三个不存在的 id，回归防护
        listOf("deepseek-v4-flash", "deepseek-v4-pro", "minimax-m2.5").forEach { ghost ->
            assertTrue(ghost !in ids, "$ghost 在官方目录里不存在，不该出现")
        }
    }
}
