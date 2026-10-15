package com.notdisk.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notdisk.core.designsystem.theme.Dimens

/**
 * A centred spinner with an optional label, for the first load of a screen.
 *
 * ### Why this exists
 * Loading is the state where an app most often feels broken. A spinner with no words tells the
 * user "something is happening" but not *what* — and in an aggregator that has just fanned out to
 * three clouds, "正在读取 115 网盘…" is the difference between patience and a force-quit.
 *
 * The label is optional so the same component covers both the anonymous inline case and the
 * narrated first-load case, but callers are encouraged to pass one whenever the wait can exceed a
 * second.
 *
 * @param label optional text under the spinner; omitted when `null` or blank.
 * @param modifier applied to the wrapping column, so callers can size the region it centres in.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.IconLarge),
            color = MaterialTheme.colorScheme.primary,
        )
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * An inline spinner-and-text row for appending to the bottom of a list that is loading page N+1.
 *
 * ### Why this exists (and why it is not just [LoadingState])
 * Paginated lists need a *different* loading treatment from first loads. The full-screen centred
 * spinner would jump the scroll position when it appears and disappear, whereas this row simply
 * extends the list by one item — which is exactly what infinite scroll should look like. Keeping
 * them as two components stops callers from wrapping the centred version in padding hacks to fake
 * the inline one.
 *
 * @param label text beside the spinner, e.g. "正在加载更多…".
 * @param modifier applied to the row.
 */
@Composable
fun LoadingIndicatorRow(
    modifier: Modifier = Modifier,
    label: String = "正在加载更多…",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Medium),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.IconSmall),
            strokeWidth = Dimens.Hairline,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.Medium),
        )
    }
}
