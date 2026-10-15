package com.notdisk.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.notdisk.core.designsystem.theme.Dimens

/**
 * The screen shown when an operation genuinely failed and the user can do something about it.
 *
 * ### Why this exists (and why it is separate from [EmptyState])
 * An aggregator app sits on top of several third-party clouds, so failures are the *normal* path,
 * not an exception: tokens expire, rate limits trip, a vendor changes its API. When that happens
 * the user needs three things and nothing else — a plain-language statement of what went wrong, a
 * way to recover, and the technical detail available *if they want it*.
 *
 * The two-string design encodes that directly. [userMessage] is what everyone reads and must never
 * contain an HTTP status or a stack trace; [detail] is the raw truth, hidden behind a disclosure
 * so it does not scare people who cannot act on it but is still copyable for a bug report. That
 * split is the whole point of this component: without it, apps either show nothing useful or show
 * `java.io.IOException: 403` to a user who just wanted their photos.
 *
 * ### Recovery affordance
 * [onRetry] is optional because not every failure is retryable (a revoked token needs a re-login,
 * not a retry). When it is `null` no button is drawn rather than a disabled one, so the user is
 * never invited to press something that cannot work.
 *
 * @param userMessage short, human, non-technical explanation. Safe to show to anyone.
 * @param detail raw technical detail (status code, vendor message); `null` hides the disclosure.
 * @param onRetry invoked by the retry button; `null` renders no retry affordance.
 * @param retryLabel overrides the retry button text when the action is more specific than "retry"
 *   — for example "重新登录" for an expired credential.
 */
@Composable
fun ErrorState(
    userMessage: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "重试",
) {
    // rememberSaveable: rotating the device should not re-hide detail the user just expanded.
    var detailExpanded by rememberSaveable(detail) { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = Dimens.XLarge, vertical = Dimens.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            // Decorative: the user message below is the accessible content.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Dimens.IconHero),
        )

        Text(
            text = userMessage,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        if (!detail.isNullOrBlank()) {
            // The row itself is the tap target, so the whole "技术详情 ⌄" strip is tappable rather
            // than only the chevron — the chevron alone would be a 24dp target on a phone.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.XSmall),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { detailExpanded = !detailExpanded }) {
                    Text(text = if (detailExpanded) "收起详情" else "技术详情")
                    Icon(
                        imageVector = if (detailExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (detailExpanded) "收起技术详情" else "展开技术详情",
                        modifier = Modifier
                            .padding(start = Dimens.XSmall)
                            .size(Dimens.IconSmall),
                    )
                }
            }

            if (detailExpanded) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.Small),
                )
            }
        }

        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(text = retryLabel)
            }
        }
    }
}
