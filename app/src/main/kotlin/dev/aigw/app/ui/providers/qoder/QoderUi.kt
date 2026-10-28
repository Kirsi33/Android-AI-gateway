package dev.aigw.app.ui.providers.qoder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.AccountCardShell
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PagedAccountList
import dev.aigw.app.ui.RegionSelector
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.qoder.QoderProvider

/**
 * Qoder 界面组件。
 *
 * 登录走设备授权（PKCE）：打开浏览器 → 用户登录 → 后台轮询换 token。
 * 两个区域是独立部署，滑块决定 client_id 与授权页域名。
 */
object QoderUi : ProviderUi {

    override val id = QoderProvider.ID

    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "浏览器设备授权登录，每日签到领 credits"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var global by rememberSaveable {
            mutableStateOf(actions.providerOptionOf(id, "region", REGION_CN) == REGION_GLOBAL)
        }
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }
        val region = if (global) REGION_GLOBAL else REGION_CN

        SectionCard(enterIndex = 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    SectionLabel("服务区域")
                }
                RegionSelector(
                    isGlobal = global,
                    onSelectGlobal = { isGlobal ->
                        global = isGlobal
                        actions.onUpdateProviderOption(id, "region", if (isGlobal) REGION_GLOBAL else REGION_CN)
                    },
                )
            }
            SectionLabel(if (global) "登录 · 国际版 qoder.com" else "登录 · 国内版 qoder.com.cn")
            Button(
                onClick = { actions.onDeviceLogin(id, region) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (global) "打开国际版授权页" else "打开国内版授权页")
            }
            Text(
                text = "浏览器里完成登录后，App 会自动取回凭证（最多等 10 分钟）。" +
                    "零额度时对话会挂起，先签到再用。",
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
            AccountRegionSection(global, accounts, actions)
        }
    }

    @Composable
    private fun AccountRegionSection(global: Boolean, accounts: List<AccountStatus>, actions: ProviderUiActions) {
        val current = if (global) REGION_GLOBAL else REGION_CN
        val scoped = remember(accounts, current) { accounts.filter { actions.regionOf(id, it.uid) == current } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scoped.isEmpty()) {
                SectionCard(enterIndex = 1) {
                    SectionLabel("账号")
                    Text(
                        text = if (global) "还没有国际版账号，先登录" else "还没有国内版账号，先登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            SectionCard(enterIndex = 1) {
                SectionLabel("账号 · ${scoped.size} 个")
                PagedAccountList(scoped, keyOf = { it.uid }) { _, account ->
                    AccountBlock(account, actions)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(id, null) }
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
                    Text("额度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                // 国际版的 /sash 签到接口实测 404（活动改走 campaign 平台），只给国内账号渲染
                if (actions.regionOf(id, status.uid) != REGION_GLOBAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineActionButton("签到") {
                            actions.onAction(id, status.uid, ACTION_CHECKIN, com.google.gson.JsonObject())
                        }
                    }
                } else {
                    Text(
                        text = "国际版没有每日签到，额度靠活动页领取",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
        )
    }

    private const val REGION_CN = "cn"
    private const val REGION_GLOBAL = "global"
}
