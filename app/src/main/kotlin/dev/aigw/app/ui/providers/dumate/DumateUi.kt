package dev.aigw.app.ui.providers.dumate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
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
import com.google.gson.JsonObject
import dev.aigw.app.ui.AccountCardShell
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PagedAccountList
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.dumate.DumateConstants

/**
 * 百度搭子 DuMate 界面组件。
 *
 * 凭证是网页 cookie：可以走内嵌 WebView 登录，也可以直接粘贴。
 * 导入时会立刻换一次对话 token 验证 cookie，无效当场拒绝。
 */
object DumateUi : ProviderUi {

    override val id = DumateConstants.ID

    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "网页登录，每日 500 积分"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Extension,
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
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                SectionLabel("登录 · 百度搭子 dumate.cn")
            }
            Button(
                onClick = {
                    actions.onCookieLogin(
                        id,
                        // 实际站点是 dumate.baidu.com：打开 www.dumate.cn/app 会 302 过去，
                        // 而 cookie 落在 .baidu.com 域。直接开目标域名，少一次跳转。
                        "https://dumate.baidu.com/app",
                        // 会话 cookie 在 dumate.baidu.com / .baidu.com；只收 dumate.cn 必然拿到空串
                        listOf(
                            "dumate.baidu.com",
                            "baidu.com",
                            ".baidu.com",
                            "passport.baidu.com",
                            "www.dumate.cn",
                            "dumate.cn",
                        ),
                        "登录百度搭子",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("在 App 内登录（取 Cookie）")
            }
            Text(
                text = "打开页面后要在里面**登录百度账号**（点页面里的登录入口，跳到 " +
                    "passport 登录完成并跳回来），只是看到页面打开不算登录。" +
                    "登录完成点右上角「完成」。站内若弹出 console.bce.baidu.com 的" +
                    "「网页无法打开」是嵌套控制台被拦，关掉即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlineActionButton(
                text = if (expanded) "收起粘贴导入" else "粘贴 Cookie 导入",
            ) { expanded = !expanded }
            if (expanded) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    label = { Text("Cookie 串或 {\"cookie\":\"...\"}") },
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
                    OutlineActionButton("刷新额度") { actions.onRefreshCredits(id, null) }
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
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlineActionButton("签到") {
                        actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                    }
                }
            },
        )
    }
}
