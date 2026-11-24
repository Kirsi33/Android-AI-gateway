package dev.aigw.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 不可恢复操作的二次确认。
 *
 * 工具型 App 最伤信任的一件事，就是手滑一下就丢凭证。删除账号、清空调用记录、清空日志
 * 这三类操作都走这一个组件，保证文案结构一致（做了什么 → 后果 → 不可撤销）。
 *
 * 确认按钮用错误色而不是主色：主色在这个 App 里是金色，太像「正常操作」。
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    body: String,
    confirmText: String = "删除",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}
