package com.notdisk.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdisk.core.designsystem.theme.Dimens

/**
 * A labelled statistic, optionally with a progress bar underneath.
 *
 * ### Why this exists
 * Storage usage, transfer progress and account quotas all share one shape: a *label*, a *value*,
 * and sometimes *how full* that value is. Rendering them ad hoc produces rows whose labels and
 * values do not line up across a screen, which makes a quota list look untrustworthy. This
 * component fixes the layout once — label left, value right, optional bar spanning both — so a
 * column of stats reads as a table rather than as loose text.
 *
 * ### Why the progress value is a `Float?`
 * `null` means "no bar", which is meaningfully different from `0f` ("a bar that is empty"). A
 * caller showing a file count must be able to omit the bar entirely rather than draw an empty one
 * that implies zero usage.
 *
 * ### Accessibility
 * When the bar is present the whole row carries a [ProgressBarRangeInfo], so a screen reader
 * announces "已用空间, 4.2 GB, 百分之 42" as one control; the visual bar is then hidden from the
 * tree so it is not announced a second time as a separate element.
 *
 * @param label the left-hand descriptor, e.g. "已用空间".
 * @param value the right-hand formatted value. Callers pass finished strings, keeping every
 *   formatting decision in the caller (usually
 *   [com.notdisk.core.designsystem.format.FileSizeFormatter]).
 * @param modifier applied to the row.
 * @param progress fill fraction in `0f..1f`, or `null` to omit the bar entirely.
 * @param supportingText optional third line under the bar for the denominator ("共 10 GB").
 * @param valueColor overrides the value colour — used to flag a nearly-full quota in error red.
 */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    supportingText: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // Coerce rather than trust the caller: a provider reporting used > total would otherwise push
    // LinearProgressIndicator past its track or, with a negative, throw.
    val safeProgress = progress?.coerceIn(0f, 1f)
    val accessibilityLabel = buildString {
        append(label)
        append(", ")
        append(value)
        if (safeProgress != null) {
            append(", 百分之 ")
            append((safeProgress * 100).toInt())
        }
        if (!supportingText.isNullOrBlank()) {
            append(", ")
            append(supportingText)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                if (safeProgress != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(safeProgress, 0f..1f)
                }
            },
        verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }

        if (safeProgress != null) {
            LinearProgressIndicator(
                progress = { safeProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ProgressTrack)
                    .clip(RoundedCornerShape(Dimens.XSmall))
                    // The column above already exposes this progress to accessibility services.
                    .clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }

        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
