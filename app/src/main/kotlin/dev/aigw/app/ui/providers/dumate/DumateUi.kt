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
                    // **必须用桌面 UA**：Android WebView 的 UA 带 `; wv` 标记，
                    // 百度 passport 对它走另一条分支——BDUSS 能种上、bce 系会话
                    // cookie 却永远不种（实测三轮：2.8.6/2.8.8/2.8.9 全败于此）。
                    // Minis 浏览器（桌面 Linux Chrome UA）登录一次成功，佐证 UA 是分水岭。
                    actions.onDesktopCookieLogin(
                        id,
                        // **从 bce 登录页进**（照 dumate2api/src/login-browser.js:19-21）：
                        // 登录完直接落在 console.bce.baidu.com，会话 cookie 自然种下。
                        // 直接在 dumate 域登录永远拿不到 bce 系——那是在访问 console 域
                        // 时才由服务端下发的，缺了它调 /api/dumate/* 一律 401。
                        "https://login.bce.baidu.com/?redirect=" +
                            "https%3A%2F%2Fconsole.bce.baidu.com%2F",
                        listOf(
                            "console.bce.baidu.com",
                            "bce.baidu.com",
                            "baidu.com",
                            ".baidu.com",
                            "passport.baidu.com",
                            "dumate.baidu.com",
                            "www.dumate.cn",
                            "dumate.cn",
                        ),
                        "登录百度搭子",
                        // 换票模式拿 token 判定成功，这个 cookie 名只用于手动「完成」时的兜底判断
                        "bce-long-term-sessionid",
                        // 兜底：若登录后没自动落 console 域，主动访问这两页激活会话
                        listOf(
                            "https://console.bce.baidu.com/",
                            "https://console.bce.baidu.com/ai/#/app/dumate/overview",
                            "https://dumate.baidu.com/app",
                        ),
                        // **页内换票**：百度 passport 的会话 cookie 是 HttpOnly，
                        // CookieManager 导不出来，导出的串服务端一律判「登录态已失效」。
                        // 改成在搭子页面里发一条同源 GET，cookie 由 WebView 自动带上。
                        "/api/dumate/app/token/get",
                        "https://dumate.baidu.com/app",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("在 App 内登录（取 Cookie）")
            }
            Text(
                text = "页面是百度云登录页，登录后会自动跳 console 域授权，再到搭子页面取 token，" +
                    "全程不用点「完成」。之所以要绕这一圈：百度的会话 cookie 是 HttpOnly，" +
                    "从外面读不到，只能在页面里换票。",
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
