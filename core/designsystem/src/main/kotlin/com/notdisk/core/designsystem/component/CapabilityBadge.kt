package com.notdisk.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionMark
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.notdisk.core.designsystem.theme.CloudSemanticColors
import com.notdisk.core.designsystem.theme.CloudShapeTokens
import com.notdisk.core.designsystem.theme.CloudTheme
import com.notdisk.core.designsystem.theme.Dimens
import java.util.Locale

/**
 * How well a provider feature is actually verified.
 *
 * ### Why three states, and why the middle one matters most
 * A cloud-drive aggregator can only honestly claim a feature works when it has been exercised
 * against the live vendor API. The tempting binary — "supported / not supported" — is a trap: it
 * forces every unverified feature into one of two lies. Marking it supported ships a button that
 * fails for the user; marking it unsupported hides a feature that probably works.
 *
 * [UNVERIFIED] is therefore a first-class state with its own colour (amber, deliberately neither
 * the success green nor the failure red), so the UI can expose the feature while signalling "we
 * have not confirmed this". That is the whole reason this component exists rather than reusing a
 * `Boolean`.
 */
enum class CapabilityState {
    /** Exercised against the live API; safe to present as working. */
    SUPPORTED,

    /** Documented by the vendor but not confirmed by this build; present, but caveated. */
    UNVERIFIED,

    /** Known not to be offered (or known to fail); the entry point should be hidden or disabled. */
    UNSUPPORTED,
    ;

    companion object {
        /**
         * Parses a capability state that arrived as text.
         *
         * Mirrors [AccountAuthStatus.fromRaw]: values cross module and persistence boundaries as
         * strings, and the safe fallback is [UNVERIFIED] — never [SUPPORTED], which would promise
         * behaviour nobody has checked.
         */
        fun fromRaw(raw: String?): CapabilityState {
            val normalised = raw?.trim()?.uppercase(Locale.US).orEmpty()
            return when (normalised) {
                "SUPPORTED", "YES", "TRUE", "OK" -> SUPPORTED
                "UNSUPPORTED", "NO", "FALSE", "NONE" -> UNSUPPORTED
                else -> UNVERIFIED
            }
        }
    }

    /** The badge word. */
    val label: String
        get() = when (this) {
            SUPPORTED -> "支持"
            UNVERIFIED -> "未验证"
            UNSUPPORTED -> "不支持"
        }

    /** The accessible explanation, which is where the nuance actually lives. */
    val description: String
        get() = when (this) {
            SUPPORTED -> "已通过实际接口验证支持"
            UNVERIFIED -> "厂商文档声明支持，但尚未实际验证"
            UNSUPPORTED -> "该功能不被支持"
        }

    internal val icon: ImageVector
        get() = when (this) {
            SUPPORTED -> Icons.Filled.Check
            UNVERIFIED -> Icons.Filled.QuestionMark
            UNSUPPORTED -> Icons.Filled.Close
        }
}

/**
 * A small pill naming one capability and how far it can be trusted.
 *
 * ### Why this exists
 * This app's honesty depends on the user being able to see which features are real. A capability
 * matrix — "重命名: 支持", "跨盘复制: 未验证" — is only useful if the three states are visually
 * *distinct at a glance*; identical grey badges with different words are effectively unreadable
 * while scanning. The three-colour mapping (green / amber / red, from the theme's semantic
 * palette) is therefore the component's primary job, not decoration.
 *
 * The badge never truncates the capability name; callers should pass short names.
 *
 * @param capabilityName the feature's display name, e.g. "重命名".
 * @param state how well the capability is verified.
 * @param modifier applied to the pill.
 */
@Composable
fun CapabilityBadge(
    capabilityName: String,
    state: CapabilityState,
    modifier: Modifier = Modifier,
) {
    val palette = CloudTheme.semanticColors
    val (container, content) = state.colors(palette)

    Row(
        modifier = modifier
            .clip(CloudShapeTokens.Pill)
            .background(container)
            .padding(horizontal = Dimens.Small, vertical = Dimens.XSmall)
            // Read as one phrase: "重命名: 未验证 — 厂商文档声明支持，但尚未实际验证".
            .semantics(mergeDescendants = true) {
                contentDescription = "$capabilityName：${state.label}，${state.description}"
            },
        horizontalArrangement = Arrangement.spacedBy(Dimens.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = state.icon,
            // The colour and glyph are redundant with the label text beside them, which is the
            // accessible content; announcing the glyph too would just repeat the state.
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Text(
            text = capabilityName,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
        Text(
            text = state.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

/** Remembers nothing; kept as a named helper so the mapping is testable in one place. */
internal fun CapabilityState.colorsRaw(palette: CloudSemanticColors): Pair<Color, Color> =
    when (this) {
        CapabilityState.SUPPORTED -> palette.successContainer to palette.onSuccessContainer
        CapabilityState.UNVERIFIED -> palette.cautionContainer to palette.onCautionContainer
        CapabilityState.UNSUPPORTED -> palette.dangerContainer to palette.onDangerContainer
    }

/** Theme-aware colour pair for a capability state. */
@Composable
internal fun CapabilityState.colors(palette: CloudSemanticColors): Pair<Color, Color> =
    remember(this, palette) { colorsRaw(palette) }
