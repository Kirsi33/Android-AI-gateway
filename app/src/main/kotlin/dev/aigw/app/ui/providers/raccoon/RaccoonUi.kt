package dev.aigw.app.ui.providers.raccoon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PointsBillsPanel
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN

object RaccoonUi : ProviderUi {

    override val id: String = "raccoon"

    override fun description(): String = "小浣熊（商汤）网页登录，用插件账号额度"

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
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Button(
                    onClick = { actions.onWebLogin(id, "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("在浏览器中打开小浣熊登录页")
                }
                OutlineActionButton(
                    text = if (expanded) "收起粘贴导入" else "粘贴回调链接导入",
                ) { expanded = !expanded }
                if (expanded) {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        label = { Text("回调链接（含 authorization_code）") },
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
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("签到领积分", false) {
                    actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                }
            }
            // 收入/消耗分开查：type=all 的 100 条窗口会被重度账号的消费行挤满，
            // 每日发放与有效期只能靠 type=income 才稳定看得到
            PointsBillsPanel(
                providerId = id,
                uid = status.uid,
                viewOf = { filter -> actions.pointsBillsOf(id, status.uid, filter) },
                onLoad = { filter -> actions.onLoadPointsBills(id, status.uid, filter) },
            )
        }
    }
}
