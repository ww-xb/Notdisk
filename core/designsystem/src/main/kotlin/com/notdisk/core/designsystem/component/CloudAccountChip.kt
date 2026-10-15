package com.notdisk.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.notdisk.core.designsystem.theme.CloudSemanticColors
import com.notdisk.core.designsystem.theme.CloudShapeTokens
import com.notdisk.core.designsystem.theme.CloudTheme
import com.notdisk.core.designsystem.theme.Dimens
import java.util.Locale

/**
 * How an account's stored credential currently stands.
 *
 * ### Why this is an enum on the UI side
 * The domain layer's auth state is richer than what a chip can show, but the *presentation* only
 * ever needs five buckets, and the component must be usable from a preview or a test without
 * constructing domain objects. The enum keeps the branch exhaustive: adding a state to the domain
 * forces a decision here rather than silently rendering an empty badge.
 *
 * [UNKNOWN] is a first-class value rather than a null, because "we have not checked yet" is a real
 * and common state in an aggregator (the app has not reached the provider since launch) and must
 * not be displayed as either success or failure.
 */
enum class AccountAuthStatus {
    /** Credential is valid; operations should work. */
    AUTHORIZED,

    /** Credential existed but the provider rejected it; a re-login is required. */
    EXPIRED,

    /** The user (or the provider) revoked access; the account must be reconnected. */
    REVOKED,

    /** A login flow is in flight, or the account was just added and not yet verified. */
    PENDING,

    /** No check has been performed; deliberately neutral. */
    UNKNOWN,
    ;

    companion object {
        /**
         * Parses a status that arrived as a plain string.
         *
         * ### Why this exists
         * Auth state is persisted (DataStore, a Room column, an intent extra) and crosses module
         * boundaries where an enum would create a dependency. Reading it back as a string and
         * mapping it here — instead of at every call site — keeps the fallback policy in one
         * place: anything unrecognised, blank or `null` is [UNKNOWN], never a guess at
         * [AUTHORIZED] and never a crash.
         *
         * Matching is case-insensitive and tolerates surrounding whitespace so a value written by
         * an older build (`"authorized"`) keeps working.
         */
        fun fromRaw(raw: String?): AccountAuthStatus {
            val normalised = raw?.trim()?.uppercase(Locale.US).orEmpty()
            return when (normalised) {
                "AUTHORIZED", "AUTHORISED", "OK", "VALID", "CONNECTED" -> AUTHORIZED
                "EXPIRED", "TOKEN_EXPIRED", "STALE" -> EXPIRED
                "REVOKED", "DISCONNECTED", "UNAUTHORIZED", "UNAUTHORISED", "INVALID" -> REVOKED
                "PENDING", "AUTHORIZING", "AUTHORISING", "LOGGING_IN", "IN_PROGRESS" -> PENDING
                else -> UNKNOWN
            }
        }
    }

    /** The short badge word. Kept next to the enum so it cannot drift between screens. */
    val label: String
        get() = when (this) {
            AUTHORIZED -> "已授权"
            EXPIRED -> "已过期"
            REVOKED -> "已失效"
            PENDING -> "授权中"
            UNKNOWN -> "未知"
        }

    /** The longer explanation used as the badge's accessibility description. */
    val description: String
        get() = when (this) {
            AUTHORIZED -> "账号已授权，可正常访问"
            EXPIRED -> "登录状态已过期，需要重新登录"
            REVOKED -> "授权已被撤销，需要重新连接账号"
            PENDING -> "正在授权，请稍候"
            UNKNOWN -> "授权状态未知，尚未检查"
        }

    internal val icon: ImageVector
        get() = when (this) {
            AUTHORIZED -> Icons.Filled.CheckCircle
            EXPIRED -> Icons.Filled.HourglassEmpty
            REVOKED -> Icons.Filled.Block
            PENDING -> Icons.AutoMirrored.Filled.HelpOutline
            UNKNOWN -> Icons.Filled.Error
        }
}

/**
 * A compact chip identifying one connected account and whether it still works.
 *
 * ### Why this exists
 * The accounts screen (and any "which drive am I looking at?" header) has to answer two questions
 * at once: *which* account is this, and *can I use it right now?* Splitting those into a name
 * label plus a separate status banner forces the user to correlate two pieces of UI. Fusing them
 * into one chip — provider name, account name, status badge — means an expired account is
 * impossible to miss while scrolling a list of five drives.
 *
 * ### Why the status is a plain string parameter
 * Auth state is persisted and passed between modules as text. Accepting `authStatus: String` and
 * mapping it through [AccountAuthStatus.fromRaw] means a caller never has to convert, and an
 * unrecognised value degrades to [AccountAuthStatus.UNKNOWN] instead of crashing or, worse,
 * rendering as healthy.
 *
 * @param providerName the vendor's display name, e.g. "115 网盘".
 * @param accountDisplayName the account's human label (nickname, phone, email); may be blank.
 * @param authStatus raw status text; mapped via [AccountAuthStatus.fromRaw].
 * @param modifier applied to the chip row.
 * @param accountDetail optional secondary line — quota, region, or a masked account id.
 * @param onClick when provided, makes the whole chip a single tap target (typically to re-auth).
 */
@Composable
fun CloudAccountChip(
    providerName: String,
    accountDisplayName: String,
    authStatus: String?,
    modifier: Modifier = Modifier,
    accountDetail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val status = remember(authStatus) { AccountAuthStatus.fromRaw(authStatus) }
    val palette = CloudTheme.semanticColors
    val (badgeContainer, badgeContent) = status.colors(palette)

    val chipShape = RoundedCornerShape(Dimens.Medium)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(chipShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.Medium, vertical = Dimens.Small)
            // One announcement per account, including its status: "115 网盘, 张三, 已过期".
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(providerName)
                    if (accountDisplayName.isNotBlank()) {
                        append(", ")
                        append(accountDisplayName)
                    }
                    append(", ")
                    append(status.description)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.Hairline),
        ) {
            Text(
                text = providerName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (accountDisplayName.isNotBlank()) {
                Text(
                    text = accountDisplayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!accountDetail.isNullOrBlank()) {
                Text(
                    text = accountDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AuthenticationBadge(
            status = status,
            containerColor = badgeContainer,
            contentColor = badgeContent,
        )
    }
}

/**
 * The pill at the end of [CloudAccountChip].
 *
 * Extracted so the chip's layout stays readable, and so a future "status only" surface (an account
 * switcher row, a settings list) can reuse the exact same badge.
 */
@Composable
internal fun AuthenticationBadge(
    status: AccountAuthStatus,
    containerColor: Color,
    contentColor: Color,
) {
    Row(
        modifier = Modifier
            .clip(CloudShapeTokens.Pill)
            .background(containerColor)
            .padding(horizontal = Dimens.Small, vertical = Dimens.XSmall)
            // The chip above already announced provider, account and status, so this badge is
            // removed from the accessibility tree entirely rather than announced a second time.
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Dimens.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = status.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

/** Resolves the container/content colour pair for a status from the semantic palette. */
@Composable
internal fun AccountAuthStatus.colors(
    palette: CloudSemanticColors,
): Pair<Color, Color> = when (this) {
    AccountAuthStatus.AUTHORIZED -> palette.successContainer to palette.onSuccessContainer
    AccountAuthStatus.EXPIRED -> palette.cautionContainer to palette.onCautionContainer
    AccountAuthStatus.REVOKED -> palette.dangerContainer to palette.onDangerContainer
    AccountAuthStatus.PENDING -> MaterialTheme.colorScheme.secondaryContainer to
        MaterialTheme.colorScheme.onSecondaryContainer
    AccountAuthStatus.UNKNOWN -> palette.unknownContainer to palette.onUnknownContainer
}
