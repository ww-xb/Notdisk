package com.notdisk.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.notdisk.core.designsystem.theme.Dimens

/**
 * A titled divider introducing a group of related rows.
 *
 * ### Why this exists
 * A settings or account screen is a long column, and without explicit grouping the user cannot
 * tell where one concern ends and the next begins. This header is the cheapest possible grouping
 * signal: a label, an optional count, an optional trailing action, and a hairline rule.
 *
 * It is deliberately not a `ListItem` with a `headlineContent` — that would give every header a
 * full-height touch target it does not need, and would make the header's own action button compete
 * with the row's click target.
 *
 * ### Why the count is a separate string
 * Callers almost always have a raw number, but formatting it is a presentation decision (thousands
 * separators, "3 项"). Taking `countText: String?` keeps the formatting policy with the caller —
 * usually `FileSizeFormatter.formatCount` — while still reserving the right-hand slot.
 *
 * @param title the group's name, e.g. "已连接的账号".
 * @param modifier applied to the header block.
 * @param countText optional right-aligned count or summary text beside the title.
 * @param actionLabel label for a trailing text action; ignored when [onAction] is `null`.
 * @param onAction invoked by the trailing action; `null` renders no action.
 * @param showDivider when false the hairline under the header is omitted — useful for the first
 *   header on a screen, where a rule above the content would look like a stray line.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    countText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.Large,
                    end = Dimens.Small,
                    top = Dimens.Medium,
                    bottom = Dimens.XSmall,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (!countText.isNullOrBlank()) {
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Spacer that pushes an optional action to the trailing edge without a nested Row.
            if (onAction != null && !actionLabel.isNullOrBlank()) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onAction,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Dimens.Small,
                            vertical = Dimens.XSmall,
                        ),
                    ) {
                        Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                thickness = Dimens.Divider,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** A leading icon variant of [SectionHeader], for screens that group by entity rather than by name. */
@Composable
fun SectionHeaderWithIcon(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    countText: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!countText.isNullOrBlank()) {
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
