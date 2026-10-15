package com.notdisk.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdisk.core.designsystem.theme.CloudShapeTokens
import com.notdisk.core.designsystem.theme.Dimens

/**
 * The grid-cell equivalent of [CloudFileListTile], for the thumbnail-first browsing mode.
 *
 * ### Why this exists
 * Listing a folder of photos or videos as text rows wastes the one thing that identifies them: the
 * picture. Grid mode trades metadata for recognition, and it is a *different* component, not a
 * flag on the list tile — the layout, the truncation rules and the touch target all differ, and
 * fusing them into one branching composable would make both harder to change.
 *
 * ### Layout decisions
 *  - The thumbnail is a fixed-aspect square, so cells align in a grid regardless of the source
 *    image's aspect ratio. A non-square image is cropped, never letterboxed: letterboxing produces
 *    visible gutters that make the grid look broken.
 *  - The caption is a single ellipsised line. Two lines would give ragged cell heights, and at
 *    grid width the user is scanning images, not reading names.
 *  - Grids are sparse for folders (no thumbnail exists), so a folder falls back to its tinted
 *    folder glyph at full cell size rather than a small icon in the corner.
 *
 * @param name the display name exactly as the provider reported it.
 * @param isFolder whether this cell is a folder.
 * @param onClick invoked on tap.
 * @param modifier applied to the cell, so the caller's grid owns sizing and spacing.
 * @param sizeText pre-formatted size shown beside the name; `null` hides it.
 * @param thumbnailUrl remote preview URL, or `null` for the type-icon fallback.
 * @param mimeType best-effort MIME type, used for icon choice when [name] has no extension.
 * @param thumbnailBaseUrl CDN origin for providers that return root-relative thumbnail paths.
 * @param selected draws the multi-select highlight.
 * @param enabled when false the cell is dimmed and non-interactive.
 * @param onLongClick invoked on long-press; `null` disables the gesture.
 * @param overlayContent slot drawn on top of the thumbnail — badges, a selection tick, or a
 *   duration chip for videos. Overlaid rather than placed below so it costs no vertical space.
 */
@Composable
fun CloudFileGridTile(
    name: String,
    isFolder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeText: String? = null,
    thumbnailUrl: String? = null,
    mimeType: String? = null,
    thumbnailBaseUrl: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    overlayContent: @Composable () -> Unit = {},
) {
    val kind = remember(name, mimeType, isFolder) { CloudFileKind.resolve(name, mimeType, isFolder) }
    val resolvedThumbnail = rememberThumbnailUrl(thumbnailUrl, thumbnailBaseUrl)

    val nameColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val sizeColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    val selectionRing = MaterialTheme.colorScheme.primary
    val selectionFill = MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CloudShapeTokens.Thumbnail)
                .background(if (selected) selectionFill else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            FileLeadingVisual(
                kind = kind,
                thumbnailUrl = resolvedThumbnail,
                containerSize = null,
                iconSize = Dimens.IconHero,
                cornerRadius = 0.dp,
                modifier = Modifier.fillMaxSize(),
            )
            overlayContent()
        }

        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!sizeText.isNullOrBlank()) {
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodySmall,
                color = sizeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // A hairline underline is the cheapest way to signal selection in a grid where a ring
        // around the cell would collide with the neighbouring cell's padding. It is always
        // composed, so selecting a cell never changes the cell's height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.Hairline)
                .background(if (selected) selectionRing else Color.Transparent),
        )
    }
}
