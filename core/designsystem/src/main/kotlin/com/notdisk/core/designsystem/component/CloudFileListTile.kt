package com.notdisk.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdisk.core.designsystem.theme.Dimens

/**
 * The primary row for a file or folder in a vertical list.
 *
 * ### Why this exists
 * The files list is the screen users spend nearly all their time in, and it is rendered thousands
 * of times per scroll. Re-implementing it per screen is how a product ends up with a folder that
 * looks one way in "My files" and another in "Search results" — which reads to users as a bug.
 * This tile is the single definition of "a file row": leading type icon (or thumbnail), name,
 * one metadata line, and a caller-owned trailing slot for per-row actions.
 *
 * ### Why the metadata is a single joined line
 * Size, timestamp and provider subtitles vary in availability — a folder has no size, a shared
 * item has no timestamp. Rather than stacking three optional `Text`s that leave a ragged gap when
 * one is missing, the present fragments are joined with `·` and the line is omitted entirely when
 * nothing is known. Rows therefore keep a uniform height.
 *
 * ### Interaction model
 * The whole row is one click target ([combinedClickable]), so a tap anywhere — including on the
 * padding — opens the item; long-press is the entry point for multi-select and context menus.
 * Semantics are merged so TalkBack announces the row as a single item ("图片, 假期照片.jpg, 4.2 MB")
 * instead of reading the icon, the name and the metadata as three separate stops.
 *
 * @param name the display name exactly as the provider reported it.
 * @param isFolder whether this row is a folder; drives navigation affordance and icon.
 * @param onClick invoked on tap.
 * @param sizeText pre-formatted size (see
 *   [com.notdisk.core.designsystem.format.FileSizeFormatter]); `null` hides it.
 * @param subtitleText free-form secondary text — usually a formatted timestamp. Callers pass a
 *   finished string so this module stays independent of any particular date policy.
 * @param thumbnailUrl remote preview URL, or `null` to always show the type icon.
 * @param mimeType best-effort MIME type; only used to pick an icon when [name] has no extension.
 * @param thumbnailBaseUrl CDN origin for providers that return a root-relative thumbnail path.
 * @param selected draws the multi-select highlight; purely visual, the caller owns selection state.
 * @param enabled when false the row is dimmed and non-interactive (e.g. a file being deleted).
 * @param onLongClick invoked on long-press; `null` disables the gesture.
 * @param trailingContent slot for row actions (overflow button, download progress, checkbox). Kept
 *   a slot rather than a set of boolean flags because the actions legitimately differ per screen
 *   and a flag per action would make this signature grow without bound.
 */
@Composable
fun CloudFileListTile(
    name: String,
    isFolder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeText: String? = null,
    subtitleText: String? = null,
    thumbnailUrl: String? = null,
    mimeType: String? = null,
    thumbnailBaseUrl: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailingContent: @Composable () -> Unit = {},
) {
    val kind = remember(name, mimeType, isFolder) { CloudFileKind.resolve(name, mimeType, isFolder) }
    val resolvedThumbnail = rememberThumbnailUrl(thumbnailUrl, thumbnailBaseUrl)
    val metadata = remember(sizeText, subtitleText) { joinMetadata(sizeText, subtitleText) }

    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val subtitleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(background)
            // mergeDescendants: the row is one item to a screen reader, not four.
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        FileLeadingVisual(
            kind = kind,
            thumbnailUrl = resolvedThumbnail,
            containerSize = Dimens.ThumbnailSmall,
            iconSize = Dimens.IconMedium,
            cornerRadius = 6.dp,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.Hairline),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        trailingContent()
    }
}
