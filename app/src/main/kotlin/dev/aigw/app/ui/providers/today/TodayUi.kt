package dev.aigw.app.ui.providers.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
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
 * Today（api.todayai.cn）界面组件。
 *
 * 凭证只有一种：**session token**。两条添加路径——
 *  - **网页登录**：App 内打开 todayai.cn，登录完由宿主读 Cookie 并从中抠出 token；
 *  - **粘贴 token**：从网页端开发者工具 / 抓包里拿到的 32 位串，直接粘贴。
 *
 * 额度是「套餐用量百分比」，不是 token 余额，所以刷新后显示的是「剩余 N%」而不是数字。
 */
object TodayUi : ProviderUi {

    override val id: String = "today"

    override fun description() = "Today 的 DeepSeek V4.1 / GLM-5.3，凭证为 session token"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Today,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        AddAccountSection(actions)
        for (account in accounts) {
            AccountCard(account, actions)
        }
    }

    @Composable
    private fun AddAccountSection(actions: ProviderUiActions) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var token by remember { mutableStateOf("") }

        SectionCard {
            if (!expanded) {
                Button(
                    onClick = {
                        // 内嵌网页登录：打开 todayai.cn，登录完从 Cookie 里抠 token
                        actions.onCookieLogin(
                            id,
                            "https://todayai.cn/",
                            listOf("https://todayai.cn/", "https://www.todayai.cn/"),
                            "登录 Today",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("网页登录") }
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("手动输入 token") }
                Text(
                    "网页登录失败时，可在浏览器登录后从开发者工具（Application → Cookies）" +
                        "复制 32 位的 session token，或用抓包工具取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@SectionCard
            }

            SectionLabel("Session Token")
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("32 位 token") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        actions.onImport(id, """{"token":${quote(token.trim())}}""")
                        token = ""
                        expanded = false
                    },
                    enabled = token.isNotBlank(),
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
                status.nickname.ifEmpty { status.uid },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                accountStatusText(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { actions.onRefreshCredits(id, status.uid) },
                    modifier = Modifier.weight(1f),
                ) { Text("刷新额度") }
                OutlinedButton(
                    onClick = { actions.onRemoveAccount(id, status.uid) },
                    modifier = Modifier.weight(1f),
                ) { Text("删除") }
            }
        }
    }

    /** 极简 JSON 字符串转义（token 里一般不会有特殊字符，但粘贴的可能是整段文本）。 */
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
