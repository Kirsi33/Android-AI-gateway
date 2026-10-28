package dev.aigw.app.ui.providers.qwenwork

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.AccountCardShell
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PagedAccountList
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.qwenwork.QwenConstants

/**
 * 千问办公界面组件。
 *
 * 只有国内一个区，没有签到（免费额度每天 00:00 自动重置）。
 */
object QwenUi : ProviderUi {

    override val id = QwenConstants.ID

    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "设备授权登录，免费额度每日自动重置"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        SectionCard(enterIndex = 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                SectionLabel("登录 · 千问办公")
            }
            Button(
                onClick = { actions.onDeviceLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开授权页登录")
            }
            Text(
                text = "浏览器里完成登录后 App 自动取回凭证（最多等 5 分钟）。" +
                    "没有每日签到：免费额度每天 00:00 自动重置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlineActionButton(
                text = if (expanded) "收起粘贴导入" else "粘贴 access token 导入",
            ) { expanded = !expanded }
            if (expanded) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    label = { Text("凭证 JSON 或 access token") },
                )
                Button(
                    onClick = {
                        actions.onImport(id, raw.trim())
                        raw = ""
                    },
                    enabled = raw.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入")
                }
            }
        }

        if (accounts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionCard(enterIndex = 1) {
                    SectionLabel("账号 · ${accounts.size} 个")
                    PagedAccountList(accounts, keyOf = { it.uid }) { _, account ->
                        AccountBlock(account, actions)
                    }
                    OutlineActionButton("刷新积分") { actions.onRefreshCredits(id, null) }
                }
            }
        }
    }

    @Composable
    private fun AccountBlock(status: AccountStatus, actions: ProviderUiActions) {
        AccountCardShell(
            account = status,
            providerId = id,
            actions = actions,
            extra = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("积分", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        text = if (status.creditsKnown) status.credits.toString() else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (status.detail.isNotEmpty()) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
        )
    }
}
