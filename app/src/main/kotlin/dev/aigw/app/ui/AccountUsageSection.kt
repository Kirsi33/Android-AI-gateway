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
import dev.aigw.core.usage.AccountDayUsage
import java.util.Locale

/**
 * 今日各账号消耗。
 *
 * 单独成文件（而不是塞进 MeScreen）是为了让上游更新 MeScreen 时少冲突：
 * 这里只有我们自己的代码，上游改用量统计页的布局不会波及。
 *
 * 数据口径见 [AccountDayUsage]：积分消耗可能是上游直报的精确值，也可能是
 * 靠余额差值推算的估算值（Trae SOLO 通道只能这么来），估算部分单独标注。
 */
@Composable
fun AccountUsageSection(usage: List<AccountDayUsage>) {
    if (usage.isEmpty()) {
        Text(
            text = "今天还没有调用。发起对话后，这里会按账号列出各自的消耗。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val gold = LocalAppColors.current.goldStart
    val totalCredits = usage.sumOf { it.creditsUsed }
    val totalRequests = usage.sumOf { it.requests }
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
            text = "· ${usage.size} 个账号 · ${formatCount(totalRequests)} 次请求",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (item in usage) {
        AccountUsageRow(item, gold)
    }
    // 口径说明：用户必须知道这个数字是怎么来的、准不准，否则「积分」二字会被当成账单
    Text(
        text = "积分按「token ÷ 1000 × 模型倍率」估算；倍率随会员档位与活动浮动，仅作参考。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun AccountUsageRow(item: AccountDayUsage, gold: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
                // 全部由余额差值推算时标「估算」——Trae 只回报剩余、不回报消耗
                if (item.creditsEstimated > 0) {
                    Text(
                        text = if (item.creditsEstimated >= item.creditsUsed) "倍率估算" else "含估算",
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