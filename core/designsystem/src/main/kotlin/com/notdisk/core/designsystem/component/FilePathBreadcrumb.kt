package com.notdisk.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.notdisk.core.designsystem.theme.Dimens

/**
 * A horizontally scrollable folder path, e.g. `根目录 › 我的资源 › 电影 › 2024`.
 *
 * ### Why this exists
 * Cloud paths get deep, and the current folder's *ancestors* are how a user orients themselves
 * ("am I in the backed-up folder or the shared one?"). Rendering the full path as a single
 * ellipsised string destroys exactly the part that answers that question, and letting it wrap
 * consumes two or three lines of a phone screen.
 *
 * A single-line horizontal strip solves both: it never wraps, it scrolls when the path is long,
 * and every ancestor stays individually tappable so the user can jump up several levels in one
 * tap instead of pressing Back repeatedly.
 *
 * ### Data shape
 * Takes `List<Pair<String, String>>` of `id to label` rather than a list of ids or a joined string.
 * The *id* is what the caller navigates by and the *label* is what the user reads, and the two are
 * genuinely different for a root folder whose id is empty — hence both, rather than making the
 * caller re-derive a label from an id it already has.
 *
 * ### Why the last item is inert
 * The trailing crumb is the folder currently being displayed. Making it clickable would reload the
 * screen the user is already looking at, so it is rendered as plain, emphasised text — the
 * conventional breadcrumb affordance.
 *
 * Auto-scroll: the strip scrolls to the end whenever [path] changes, because after navigating into
 * a folder the interesting crumb is always the new last one.
 *
 * @param path the `id to label` chain from the root down to the current folder.
 * @param onCrumbClick invoked with the **id** of a clicked ancestor; never called for the last one.
 * @param modifier applied to the scrolling strip.
 */
@Composable
fun FilePathBreadcrumb(
    path: List<Pair<String, String>>,
    onCrumbClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Jump to the end when the path changes: the newly entered folder is the crumb that matters,
    // and a strip left scrolled to its start would hide it.
    LaunchedEffect(path) {
        if (path.isNotEmpty()) {
            listState.scrollToItem(path.lastIndex)
        }
    }

    val fullPathDescription = path.joinToString(separator = " / ") { it.second }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            // One merged announcement for the whole strip: a screen-reader user wants the path,
            // not a stop per crumb plus a stop per separator.
            .semantics(mergeDescendants = true) {
                contentDescription = "当前路径：$fullPathDescription"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        itemsIndexed(
            items = path,
            key = { index, crumb -> "${crumb.first}#$index" },
        ) { index, crumb ->
            val isLast = index == path.lastIndex

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.XSmall),
            ) {
                Text(
                    text = crumb.second,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isLast) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .then(
                            if (isLast) {
                                Modifier
                            } else {
                                Modifier
                                    .clip(RoundedCornerShape(Dimens.Small))
                                    .clickable { onCrumbClick(crumb.first) }
                            },
                        )
                        .padding(horizontal = Dimens.XSmall, vertical = Dimens.Small),
                )

                if (!isLast) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        // The separator carries no information a screen reader needs; the merged
                        // content description above already reads the path in order.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                }
            }
        }
    }
}
