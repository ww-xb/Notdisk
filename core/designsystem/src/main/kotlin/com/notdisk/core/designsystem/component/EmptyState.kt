package com.notdisk.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.notdisk.core.designsystem.theme.Dimens

/**
 * The screen a user sees when a list is legitimately empty.
 *
 * ### Why this exists
 * "Empty" is not an error and must not look like one. An empty folder, a search with no matches
 * and an account with no files yet are all *normal* states, and the difference between a good and
 * a bad cloud-drive UI is whether the user understands, in one glance, that nothing is wrong and
 * what to do next. A bare blank screen or a stray "No data" leaves them wondering whether the app
 * failed to load.
 *
 * This composable therefore always pairs an icon (why is it empty?) with a title (what happened?)
 * and a description (what can I do?), plus an optional primary action that turns the dead end into
 * a next step — typically "upload a file" or "clear the search".
 *
 * It is centred and fills the width so it can be dropped into an empty `LazyColumn` area or a
 * `Box` without extra layout scaffolding at the call site.
 *
 * @param title the one-line summary, e.g. "这个文件夹是空的".
 * @param icon the leading glyph; pick one that matches the *reason* for emptiness.
 * @param description optional second line explaining how the state came about or what to do.
 * @param actionLabel label for the optional action button; ignored when [onAction] is `null`.
 * @param onAction action invoked by the button; `null` renders no button at all.
 */
@Composable
fun EmptyState(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.XLarge, vertical = Dimens.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the title immediately below says the same thing in words, and reading
            // "folder off, 这个文件夹是空的" would be pure noise.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(Dimens.IconHero),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onAction != null && !actionLabel.isNullOrBlank()) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = Dimens.Small),
            ) {
                Text(text = actionLabel)
            }
        }
    }
}
