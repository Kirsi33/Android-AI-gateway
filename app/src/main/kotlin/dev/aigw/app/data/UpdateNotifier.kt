package dev.aigw.app.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.MainActivity
import dev.aigw.app.R

/**
 * 版本更新提醒。
 *
 * 为什么要单独做一条通知：自动检查更新（[dev.aigw.app.ui.AppViewModel.maybeAutoCheckUpdate]）
 * 只在 App 启动时跑，用户不开 App 就永远不知道有新版本。发一条系统通知才能主动触达。
 *
 * 三条纪律：
 *  - **同一个版本只提醒一次**：记在本地，用户看过/划掉就不会被同一个版本骚扰；
 *  - **通知里不塞自动下载**：点进去只打开 App 的更新弹窗，装不装由用户决定；
 *  - **没有通知权限就静默**：Android 13+ 需要 POST_NOTIFICATIONS，没授权时不能抛异常。
 */
object UpdateNotifier {

    private const val NOTIFICATION_ID = 51001

    /**
     * 发现有新版本时调用。[version] 是远端版本号（如 `2.5.10`）。
     *
     * 不做「同一版本只提醒一次」的去重：本项目发版不频繁，每次检查到新版本都值得提醒一次。
     * 系统通知本身是幂等的（同一个 NOTIFICATION_ID 会覆盖，不会堆一串），
     * 所以每天自动检查一次只会刷新同一条通知，不会打扰。
     */
    fun notifyIfNeeded(context: Context, version: String) {
        if (version.isBlank()) return
        if (!canNotify(context)) return

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // 带一个标记，让 ViewModel 启动后直接弹更新窗，用户不用自己再点一次
                putExtra(EXTRA_SHOW_UPDATE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AiGatewayApp.UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("网关助手有新版本")
            .setContentText("$version 可以更新了，点这里查看更新内容")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$version 可以更新了。点这里打开 App 查看本次更新内容并安装。",
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** 已经升级到最新版本时把残留的通知撤掉。 */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** Android 13+ 需要显式授权；未授权时发通知会被系统静默丢弃。 */
    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 通知点击后带过来的标记：MainActivity 据此自动弹出更新窗。 */
    const val EXTRA_SHOW_UPDATE = "show_update"
}
