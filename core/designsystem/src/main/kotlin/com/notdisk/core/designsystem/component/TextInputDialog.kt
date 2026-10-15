package com.notdisk.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.notdisk.core.designsystem.theme.Dimens

/**
 * A single-field prompt: rename, create folder, enter a safe key.
 *
 * ### Why this exists
 * These three flows look identical to the user but each has a trap that a bare `TextField` does not
 * solve:
 *
 *  - **Rename** must pre-select the current name so typing replaces it. Without pre-selection the
 *    user's first keystroke appends, and "holiday.jpg" silently becomes "holiday.jpg2".
 *  - **Create folder** must refuse an empty name *before* the round trip, because a provider that
 *    accepts "" creates an invisible, unopenable folder.
 *  - **Safe key** input is a secret: it needs a different keyboard and the confirm button must stay
 *    disabled until something is typed.
 *
 * Handling all three here means the rules are stated once instead of being re-derived (and
 * re-forgotten) at each call site.
 *
 * ### Validation model
 * Two complementary mechanisms, because they answer different questions:
 *  - The confirm button is disabled while the field is blank, which prevents the obvious mistake
 *    with no network round trip at all.
 *  - [validationError] is *supplied by the caller*, typically as the result of asynchronous
 *    server-side validation ("该名称已存在" / "密钥格式不正确"). Passing it in rather than
 *    computing it here is what lets the message come from the provider that actually knows.
 *
 * The dialog never validates against a hardcoded rule set: filename legality differs per vendor,
 * so a shared regex here would reject names that a real backend accepts.
 *
 * @param title the dialog heading, e.g. "重命名".
 * @param visible whether the dialog is shown; state resets each time it becomes visible.
 * @param onDismiss invoked on cancel, back-press or outside tap.
 * @param onConfirm invoked with the current text when the user confirms; never called while blank.
 * @param modifier applied to the dialog.
 * @param initialValue prefilled text; pre-selected so a rename can be typed over.
 * @param label the field's floating label, e.g. "文件名称".
 * @param validationError message shown under the field in the error colour; `null` when valid.
 * @param helperText non-error hint shown under the field while [validationError] is `null`.
 * @param confirmLabel confirm button text.
 * @param dismissLabel cancel button text.
 * @param keyboardType input type; use [KeyboardType.Password] for safe keys.
 * @param enabled when false the field is read-only and confirm is disabled (e.g. while submitting).
 * @param onValueChange optional live echo of every edit, so a caller can re-run validation as the
 *   user types rather than only on submit.
 */
@Composable
fun TextInputDialog(
    title: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialValue: String = "",
    label: String = "名称",
    validationError: String? = null,
    helperText: String? = null,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit = {},
) {
    // Keyed on `visible` so reopening the dialog always starts from a clean initial value rather
    // than whatever the previous, cancelled edit left behind.
    var fieldValue by remember(initialValue, visible) {
        mutableStateOf(
            TextFieldValue(
                text = initialValue,
                // Pre-select the whole value: the user's first keystroke replaces it.
                selection = TextRange(0, initialValue.length),
            ),
        )
    }

    if (!visible) return

    val focusRequester = remember { FocusRequester() }

    // Focus on open so the keyboard is already up and the user can type immediately. Declared
    // after the early return because requesting focus on a node that is not in the tree throws.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val trimmed = fieldValue.text.trim()
    val isError = !validationError.isNullOrBlank()
    val canConfirm = enabled && trimmed.isNotEmpty() && !isError

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { updated ->
                        fieldValue = updated
                        onValueChange(updated.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    enabled = enabled,
                    singleLine = true,
                    isError = isError,
                    label = { Text(text = label) },
                    supportingText = buildSupportingText(isError, validationError, helperText),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = canConfirm,
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissLabel)
            }
        },
    )
}

/**
 * Builds the field's supporting slot, preferring the error over the hint.
 *
 * Returns `null` when there is nothing to say so the field collapses that slot instead of
 * reserving empty space — which would otherwise make the dialog jump in height the first time an
 * error appears.
 */
private fun buildSupportingText(
    isError: Boolean,
    validationError: String?,
    helperText: String?,
): (@Composable () -> Unit)? {
    val message = if (isError) validationError else helperText
    if (message.isNullOrBlank()) return null
    return {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
