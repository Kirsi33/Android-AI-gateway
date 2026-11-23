package dev.aigw.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.aigw.app.data.KeepAlive
import dev.aigw.app.service.FloatingKeepAlive
import kotlin.math.roundToInt

/**
 * 保活与权限：把网关在后台持续运行所需的系统条件逐项检测出来，并给出一键跳转。
 *
 * 锁屏后端口还能被客户端调通，需要几件事同时成立：通知权限、应用不在电池优化名单里、
 * 系统没有限制它后台运行。这些都没有 API 能直接代改，只能检测 + 引导。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeepAliveScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val status = state.keepAlive

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    // 从系统授权页返回时（ON_RESUME）重检权限并补显示悬浮窗：
    // 用户去设置里授权后回到本页，开关无需重新点一遍。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reapplyOverlayIfEnabled()
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PageScaffold(
        title = "保活与权限",
        subtitle = "让网关在后台持续运行",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保活状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    text = if (status?.allGood == true) "已就绪" else "有待处理",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (status?.allGood == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "通知权限 + 电池优化白名单 + 不被限制后台，这几项都满足后才能锁屏稳定可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.running) {
                Text(
                    text = "当前服务未启动：先去首页启动，否则锁屏后没有进程监听端口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(enterIndex = 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开机自动恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "手机重启或应用更新后自动拉起网关，按当前端口继续监听；关闭时开机不启动任何服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.startOnBoot,
                    onCheckedChange = { viewModel.setStartOnBoot(it) },
                )
            }
            Text(
                text = "说明：Android 15 起 specialUse 类型的前台服务允许直接从开机广播启动；该开关不影响本次运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("悬浮窗保活", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "在屏幕上留一个小悬浮标签，让系统把本应用视为可见进程，降低后台被回收的概率；" +
                            "网关服务启动后出现、停止时移除，可拖动、点按回到应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.overlayKeepAlive,
                    onCheckedChange = { viewModel.setOverlayKeepAlive(it) },
                )
            }
            if (state.overlayKeepAlive && !state.running) {
                Text(
                    text = "网关未启动：悬浮窗将在服务启动后显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.overlayKeepAlive && status?.overlayGranted != true) {
                Text(
                    text = "缺少「显示在其他应用上层」权限，悬浮窗尚未显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status?.overlayGranted != true) {
                ChoiceChip("授予悬浮窗权限", false) {
                    launch(context, FloatingKeepAlive.permissionIntent(context), "无法打开悬浮窗权限设置")
                }
            }
            Text(
                text = "说明：悬浮窗只影响系统的后台回收策略，不改变请求链路；关闭开关会立即移除悬浮窗。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.overlayKeepAlive) {
            OverlayAppearanceCard(context)
        }

        SectionCard(enterIndex = 3) {
            StatusRow(
                title = "通知权限",
                detail = if (status?.notificationsGranted == true) "已允许，常驻通知可见" else "未允许，后台通知不会显示",
                ok = status?.notificationsGranted == true,
                actionLabel = "去开启",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launch(context, KeepAlive.notificationSettingsIntent(context), "无法打开通知设置")
                }
            }
            StatusRow(
                title = "电池优化",
                detail = if (status?.batteryUnrestricted == true) {
                    "已加入白名单（不限制耗电）"
                } else {
                    "受系统电池优化限制，锁屏后可能被冻结、端口调不通"
                },
                ok = status?.batteryUnrestricted == true,
                actionLabel = "不限制",
            ) {
                // 部分 ROM 不支持直接请求，失败就退回到电池优化列表页
                if (!launch(context, KeepAlive.batteryOptimizationIntent(context), null)) {
                    launch(context, KeepAlive.batterySettingsIntent(), "无法打开电池优化设置")
                }
            }
            StatusRow(
                title = "后台运行限制",
                detail = if (status?.backgroundRestricted == true) "系统已限制本应用后台运行" else "未被系统限制后台运行",
                ok = status?.backgroundRestricted != true,
                actionLabel = "去设置",
            ) {
                launch(context, KeepAlive.appDetailsIntent(context), "无法打开应用详情")
            }
            StatusRow(
                title = "流量节省",
                detail = if (status?.dataSaverRestricted == true) "已开启流量节省，后台联网可能受限" else "未开启流量节省",
                ok = status?.dataSaverRestricted != true,
                actionLabel = "去设置",
            ) {
                launch(context, KeepAlive.appDetailsIntent(context), "无法打开应用详情")
            }
        }

        SectionCard(enterIndex = 4) {
            Text("厂商后台管理页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "国产系统默认会限制第三方应用后台。下面的入口会尝试直接打开厂商的后台管理页；" +
                    "打不开就按提示手动设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceChip("打开厂商后台设置", false) {
                val opened = KeepAlive.autoStartIntents().any { launch(context, it, null) }
                if (!opened) {
                    launch(context, KeepAlive.appDetailsIntent(context), "无法打开系统页面")
                }
                Toast.makeText(context, KeepAlive.manualHint(Build.MANUFACTURER), Toast.LENGTH_LONG).show()
            }
            Text(
                text = KeepAlive.manualHint(Build.MANUFACTURER),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "说明：本应用用「常驻前台服务 + 通知」保持进程存活，并持有唤醒锁与 Wi-Fi 锁" +
                "（锁屏后 CPU/Wi-Fi 不被省电挂起，已有 TCP 连接不会断），也可开启「悬浮窗保活」进一步降低后台被回收的概率。" +
                "如果系统把本应用放进电池优化名单或限制后台，锁屏一段时间后进程仍可能被冻结，客户端就会连接失败——" +
                "按上面的检测项逐条处理即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 悬浮窗外观：字号（连续滑块）+ 自动换位间隔。
 *
 * 设置即写盘并且立刻应用到正在显示的悬浮窗（[FloatingKeepAlive] 内部负责重排），
 * 不需要重启网关服务，所以这里不经过 ViewModel。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayAppearanceCard(context: Context) {
    var scale by remember { mutableFloatStateOf(FloatingKeepAlive.scaleSp(context)) }
    var interval by remember { mutableIntStateOf(FloatingKeepAlive.autoMoveIntervalMin(context)) }

    SectionCard(enterIndex = 3) {
        Text("悬浮窗外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "字号 ${scale.roundToInt()} sp",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = scale,
            onValueChange = { scale = it },
            onValueChangeFinished = { FloatingKeepAlive.setScaleSp(context, scale) },
            valueRange = FloatingKeepAlive.MIN_SCALE..FloatingKeepAlive.MAX_SCALE,
            steps = 0,
        )
        Text(
            text = "${FloatingKeepAlive.MIN_SCALE.roundToInt()} ~ ${FloatingKeepAlive.MAX_SCALE.roundToInt()} sp，拖动即可实时看到悬浮窗变化。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 间隔滑块换成「分钟」刻度：0 = 关闭，最高 60 分钟
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (interval == 0) "自动换位：已关闭" else "自动换位：每 $interval 分钟",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ChoiceChip(if (interval == 0) "开启" else "关闭", selected = false) {
                interval = if (interval == 0) FloatingKeepAlive.DEFAULT_INTERVAL_MIN else 0
                FloatingKeepAlive.setAutoMoveIntervalMin(context, interval)
            }
        }
        Slider(
            value = interval.toFloat(),
            onValueChange = { interval = it.roundToInt() },
            onValueChangeFinished = { FloatingKeepAlive.setAutoMoveIntervalMin(context, interval) },
            valueRange = 0f..FloatingKeepAlive.MAX_INTERVAL_MIN.toFloat(),
            steps = (FloatingKeepAlive.MAX_INTERVAL_MIN / 5) - 1,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 30, 0).forEach { m ->
                ChoiceChip(if (m == 0) "关闭" else "$m 分", selected = interval == m) {
                    interval = m
                    FloatingKeepAlive.setAutoMoveIntervalMin(context, m)
                }
            }
        }
        Text(
            text = "到点后悬浮窗会瞬间跳到屏幕另一处的角落或边中点（跳得足够远才有效防烧屏），" +
                "不会沿着路径滑动；手动拖动过的位置会被记住。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    detail: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        ChoiceChip(actionLabel, selected = false, onClick = onAction)
    }
}

private fun launch(context: Context, intent: Intent, failureMessage: String?): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        if (failureMessage != null) {
            Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        }
        false
    }
}
