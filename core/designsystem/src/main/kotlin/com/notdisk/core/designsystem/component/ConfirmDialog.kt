package com.notdisk.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A yes/no confirmation for a destructive action.
 *
 * ### Why this exists as its own component
 * Deleting, overwriting and disconnecting an account all need a confirmation, and each one is a
 * chance to get the *dangerous* styling wrong: a confirm button that looks identical to cancel is
 * how users delete the wrong folder. This component fixes the convention once — the confirming
 * action is the prominent one, and [destructive] switches it to the error colour — so no screen has
 * to re-decide it.
 *
 * ### Why the callbacks are nullable
 * AlertDialog must be dismissible by back-press and by tapping outside; both paths land in
 * [onDismiss], so it is required. [onConfirm] is the only path that performs the action, which
 * keeps "dismissed" and "confirmed" unambiguous — a distinction that matters enormously when the
 * action is irreversible.
 *
 * This composable renders nothing when [visible] is false, so callers can keep it in the tree and
 * drive it with a boolean instead of wrapping it in an `if` at every site.
 *
 * @param title the dialog's heading, phrased as the question being asked ("删除这个文件？").
 * @param message the consequence, in plain language. Avoid "are you sure?" — say what will happen.
 * @param visible whether the dialog is shown.
 * @param onDismiss invoked on cancel, back-press, or outside tap.
 * @param onConfirm invoked only by the confirm button.
 * @param confirmLabel confirm button text. Callers performing an irreversible action should pass
 *   the verb itself ("删除", "断开连接") rather than "确定", so the button states the consequence.
 * @param dismissLabel cancel button text.
 * @param destructive when true the confirm button takes the error colour, marking the action as
 *   one that cannot be undone.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    destructive: Boolean = false,
) {
    if (!visible) return

    val confirmColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissLabel)
            }
        },
    )
}
