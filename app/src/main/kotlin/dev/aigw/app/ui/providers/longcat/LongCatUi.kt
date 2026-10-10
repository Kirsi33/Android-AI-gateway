package dev.aigw.app.ui.providers.longcat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.accountStatusText
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * LongCat（美团龙猫）界面组件。
 *
 * 两种账号形态：
 *  - **免登录**：一键添加，走 `chat-completion-oversea-V2`，无需任何凭证（但上游有 IP 级限流）；
 *  - **Cookie 登录**：从浏览器复制的 Cookie 串（至少含 `passport_token_key`），
 *    走 `session-create` + `chat-completion-V2`，更稳但需要自己提供 Cookie。
 */
object LongCatUi : ProviderUi {

    override val id: String = "longcat"

    override fun description() = "美团 LongCat 龙猫，支持免登录通道"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Pets,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        AddAccountSection(accounts, actions)
        for (account in accounts) {
            AccountCard(account, actions)
        }
    }

    @Composable
    private fun AddAccountSection(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var cookie by remember { mutableStateOf("") }

        SectionCard {
            Button(
                onClick = {
                    // 免登录通道：直接塞一个无 Cookie 的账号
                    actions.onImport(id, """{"cookie":"","nickname":"免登录通道","uid":"anonymous"}""")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加免登录账号") }

            if (!expanded) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("用 Cookie 登录（更稳定）") }
                return@SectionCard
            }

            SectionLabel("Cookie 登录")
            Text(
                "浏览器打开 longcat.chat 登录后，从开发者工具里复制整段 Cookie " +
                    "（至少要有 passport_token_key）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = cookie,
                onValueChange = { cookie = it },
                label = { Text("Cookie") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        actions.onImport(id, """{"cookie":${quote(cookie.trim())}}""")
                        cookie = ""
                        expanded = false
                    },
                    enabled = cookie.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("导入") }
                OutlinedButton(
                    onClick = { expanded = false },
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
            }
        }
    }

    @Composable
    private fun AccountCard(status: AccountStatus, actions: ProviderUiActions) {
        SectionCard {
            Text(
                status.account.nickname.ifEmpty { status.account.uid },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                accountStatusText(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val region = actions.regionOf(id, status.account.uid)
            if (region.isNotEmpty()) {
                Text(
                    if (region == "cn") "通道：Cookie 登录" else "通道：免登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { actions.onRefreshCredits(id, status.account.uid) },
                    modifier = Modifier.weight(1f),
                ) { Text("刷新") }
                OutlinedButton(
                    onClick = { actions.onRemoveAccount(id, status.account.uid) },
                    modifier = Modifier.weight(1f),
                ) { Text("删除") }
            }
        }
    }

    /** 极简 JSON 字符串转义（Cookie 里可能含引号/反斜杠）。 */
    private fun quote(value: String): String {
        val builder = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') builder.append(String.format("\\u%04x", ch.code)) else builder.append(ch)
            }
        }
        return builder.append("\"").toString()
    }
}
