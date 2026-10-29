package dev.aigw.app.ui.providers.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
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
import dev.aigw.app.ui.ConfirmDestructiveDialog
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

    /** 账号卡由本组件自己渲染，页面骨架不要再包一层。 */
    override val managesOwnAccounts: Boolean get() = true

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
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }
        var manual by rememberSaveable { mutableStateOf(false) }
        var token by remember { mutableStateOf("") }

        // 发送后 60 秒内不允许重发：上游有频率限制，连点只会被拒
        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        SectionCard {
            SectionLabel("短信登录")
            Text(
                // 实测只有大陆号能收到码；这里写清楚，免得用户拿海外号反复试
                text = "用大陆手机号（+86）收验证码登录，与官方 App 同一个账号体系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { input -> phone = input.filter { it.isDigit() || it == '+' }.take(15) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("手机号") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { input -> code = input.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        // 先开倒计时，按钮立刻有反馈；发送失败再恢复可点
                        countdown = RESEND_SECONDS
                        actions.onSmsLogin(id, phone.trim(), "", CCODE) { error ->
                            if (error.isNotEmpty()) countdown = 0
                        }
                    },
                    enabled = phone.isNotBlank() && countdown == 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (countdown > 0) "${countdown} 秒后重发" else "发送验证码")
                }
                Button(
                    onClick = { actions.onSmsLogin(id, phone.trim(), code.trim(), CCODE) { } },
                    enabled = phone.isNotBlank() && code.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("登录") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedButton(
                onClick = { manual = !manual },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (manual) "收起手动输入" else "手动粘贴 token") }
            if (manual) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("32 位 session token") },
                )
                Button(
                    onClick = {
                        actions.onImport(id, """{"token":${quote(token.trim())}}""")
                        token = ""
                        manual = false
                    },
                    enabled = token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导入") }
            }

            OutlinedButton(
                onClick = {
                    // 网页登录作为兜底：短信通道不可用时才走这条路
                    actions.onCookieLogin(
                        id,
                        "https://todayai.cn/",
                        listOf("https://todayai.cn/", "https://www.todayai.cn/"),
                        "登录 Today",
                        "",
                        emptyList(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("网页登录（备用）") }
        }

        for (account in accounts) {
            AccountCard(account, actions)
        }
    }

    @Composable
    private fun AccountCard(status: AccountStatus, actions: ProviderUiActions) {
        var confirmRemove by remember { mutableStateOf(false) }
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
                    onClick = { confirmRemove = true },
                    modifier = Modifier.weight(1f),
                ) { Text("删除") }
            }
        }
        if (confirmRemove) {
            ConfirmDestructiveDialog(
                title = "删除账号「{status.nickname.ifEmpty { status.uid }}」？",
                body = "本地保存的凭证会被清除，之后要重新登录才能再用。此操作不可撤销。",
                onConfirm = { actions.onRemoveAccount(id, status.uid) },
                onDismiss = { confirmRemove = false },
            )
        }
    }

    /** 重发倒计时秒数：与上游短信频率限制对齐。 */
    private const val RESEND_SECONDS = 60

    /** Today 只对大陆号码下码，所以界面上不提供国家码选择。 */
    private const val CCODE = "86"

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
