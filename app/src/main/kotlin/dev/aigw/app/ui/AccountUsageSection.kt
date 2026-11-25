package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.core.usage.ACCOUNT_USAGE_CREDITS_VERSION
import dev.aigw.core.usage.AccountDayUsage
import java.util.Locale

/**
 * 今日各账号消耗。
 *
 * 单独成文件（而不是塞进 MeScreen）是为了让上游更新 MeScreen 时少冲突：
 * 这里只有我们自己的代码，上游改用量统计页的布局不会波及。
 *
 * 数据口径见 [AccountDayUsage]：余额差值与上游直报都是真实值，同一快照区间内取较大值
 * 去重（避免同一笔消耗被算两次）；旧的 token × 倍率估算已停用，只可能出现在历史数据里。
 *
 * [compact] 供首页「今日用量」卡片使用：不显示空态与口径说明、账号超过 [COMPACT_LIMIT]
 * 个后折叠成一行「还有 N 个账号」，保证首页卡片不会被撑高。
 */
@Composable
fun AccountUsageSection(
    usage: List<AccountDayUsage>,
    compact: Boolean = false,
) {
    // 空 uid 是「所有账号都试过仍失败」的归属桶，进了账号列表只会变成一行「未绑定账号」；
    // 顶部失败计数已经涵盖它，这里只统计能归属到具体账号的调用。
    val bound = usage.filter { it.uid.isNotEmpty() }
    if (bound.isEmpty()) {
        if (compact) return
        Text(
            text = "今天还没有调用。发起对话后，这里会按账号列出各自的消耗。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val gold = LocalAppColors.current.goldStart
    val totalCredits = bound.sumOf { it.creditsUsed }
    val totalRequests = bound.sumOf { it.requests }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "合计 ${formatCount(totalCredits)} 积分",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (totalCredits > 0) gold else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "· ${bound.size} 个账号 · ${formatCount(totalRequests)} 次请求",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val shown = if (compact) bound.take(COMPACT_LIMIT) else bound
    for (item in shown) {
        AccountUsageRow(item, gold, compact)
    }
    if (compact && bound.size > shown.size) {
        Text(
            text = "还有 ${bound.size - shown.size} 个账号，点上方「用量统计」查看全部",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    // 口径说明：用户必须知道这个数字是怎么来的、准不准，否则「积分」二字会被当成账单
    if (!compact) {
        Text(
            text = "「实测」= 上游直报积分或刷新额度时的余额差值（同一区间不重复计）；" +
                "「旧口径」= 修复前的统计（可能重复计），次日自动消失；" +
                "「估算」= 已停用的 token × 倍率旧数据。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 首页紧凑模式下最多列几个账号。 */
private const val COMPACT_LIMIT = 3

@Composable
private fun AccountUsageRow(item: AccountDayUsage, gold: Color, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 供应商色点：多供应商混用时一眼分得清谁是谁
            Box(Modifier.size(6.dp).clip(CircleShape).background(providerDotColor(item.providerId, gold)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = displayNameOf(item),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(item.providerId)
                        if (item.requests > 0) append(" · ${formatCount(item.requests)} 次")
                        if (item.totalTokens > 0) append(" · ${formatTokens(item.totalTokens)} tokens")
                        if (item.cachedTokens > 0 && item.promptTokens > 0) {
                            val hit = String.format(Locale.US, "%.0f", item.cachedTokens * 100.0 / item.promptTokens)
                            append(" · 命中 $hit%")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = if (item.creditsUsed > 0) formatCount(item.creditsUsed) else "—",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.creditsUsed > 0) gold else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 口径标签：余额差值与上游直报都是真实值（同区间已去重）→ 实测；
                // creditsEstimated 只可能来自已停用的 token×倍率旧数据 → 估算/部分估算
                when {
                    item.creditsUsed <= 0 -> Unit
                    item.creditsVersion < ACCOUNT_USAGE_CREDITS_VERSION -> Text(
                        text = "旧口径",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.creditsEstimated <= 0 -> Text(
                        text = "实测",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    item.creditsEstimated >= item.creditsUsed -> Text(
                        text = "估算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        text = "部分估算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 账号显示名：优先昵称，其次 uid 末 6 位（uid 常是长数字，全显示会挤爆）。 */
private fun displayNameOf(item: AccountDayUsage): String = when {
    item.nickname.isNotEmpty() -> item.nickname
    item.uid.isEmpty() -> "未绑定账号"
    item.uid.length <= 8 -> item.uid
    else -> "…${item.uid.takeLast(6)}"
}

/** 供应商配色：同一供应商恒定同色，不同供应商尽量区分。 */
private fun providerDotColor(providerId: String, gold: Color): Color = when {
    providerId.startsWith("trae") -> gold
    providerId.startsWith("codebuddy") -> Color(0xFF4C8DFF)
    providerId.startsWith("antigravity") -> Color(0xFF34C77B)
    providerId.startsWith("loomy") -> Color(0xFFB06BFF)
    providerId.startsWith("raccoon") -> Color(0xFFFF8A3D)
    else -> Color(0xFF9E9E9E)
}