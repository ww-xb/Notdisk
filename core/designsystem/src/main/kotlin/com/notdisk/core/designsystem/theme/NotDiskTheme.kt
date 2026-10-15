package com.notdisk.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

/**
 * How the app should choose between the light and dark schemes.
 *
 * ### Why this is an enum and not a `Boolean`
 * "Dark mode on/off" cannot express "follow the system", which is what most users actually want
 * and what Android's own per-app language/theme settings expose. Modelling the third state
 * explicitly stops call sites from inventing a nullable boolean (`Boolean?` where `null` means
 * "system"), which is exactly the kind of ambiguity that produces "the setting did not stick"
 * bugs. A user preference stores this enum's [name]; [SYSTEM] is the default.
 */
enum class ThemeMode {
    /** Follow the device's dark-theme setting; the default. */
    SYSTEM,

    /** Always use [LightColorScheme], regardless of the system setting. */
    LIGHT,

    /** Always use [DarkColorScheme], regardless of the system setting. */
    DARK,
    ;

    /** Resolves this mode against the current system setting into "render dark?". */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }
}

/**
 * Semantic colours that Material's [androidx.compose.material3.ColorScheme] has no slot for.
 *
 * ### Why this exists
 * This product has to talk about states Material never modelled: an account whose token expired,
 * a capability that the vendor documents but nobody verified, a file type that should get a
 * recognisable tint. Squeezing those into `error`/`tertiary` makes them mean two things at once
 * and breaks the moment the brand palette changes. They live here instead, resolved per
 * light/dark so a badge stays legible on both ramps.
 *
 * Reach for these through [CloudTheme.semanticColors] rather than constructing them at a call
 * site; that is what keeps "unverified" the same amber in every screen.
 */
@Immutable
data class CloudSemanticColors(
    /** Positive, verified outcome: auth OK, capability confirmed supported. */
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** "Not confirmed either way": unverified capability, pending authorisation. */
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    /** "Confirmed absent or broken": unsupported capability, revoked token. */
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    /** "We do not know": unknown status. Deliberately neutral grey, never red or amber. */
    val unknown: Color,
    val unknownContainer: Color,
    val onUnknownContainer: Color,
    // ---- File-type tints. Chosen for recognisability, not decoration. ----
    val folderTint: Color,
    val imageTint: Color,
    val videoTint: Color,
    val audioTint: Color,
    val pdfTint: Color,
    val documentTint: Color,
    val archiveTint: Color,
    val textTint: Color,
    val genericTint: Color,
)

private val LightSemanticColors = CloudSemanticColors(
    success = Color(0xFF1B6E3C),
    successContainer = Color(0xFFC6EFD3),
    onSuccessContainer = Color(0xFF00210F),
    caution = CloudColors.CautionAmber,
    cautionContainer = CloudColors.CautionAmberContainer,
    onCautionContainer = CloudColors.OnCautionAmberContainer,
    danger = CloudColors.ErrorRed,
    dangerContainer = CloudColors.DangerRedContainer,
    onDangerContainer = CloudColors.OnErrorRedContainer,
    unknown = Color(0xFF5C636B),
    unknownContainer = Color(0xFFE2E5E9),
    onUnknownContainer = Color(0xFF1A1C1E),
    folderTint = CloudColors.FolderAmber,
    imageTint = CloudColors.ImageViolet,
    videoTint = CloudColors.VideoRose,
    audioTint = CloudColors.AudioTeal,
    pdfTint = CloudColors.PdfCrimson,
    documentTint = CloudColors.DocumentBlue,
    archiveTint = CloudColors.ArchiveBrown,
    textTint = CloudColors.TextSlate,
    genericTint = CloudColors.GenericGrey,
)

/**
 * Dark counterparts. The tints are lifted in luminance because a 40%-lightness glyph on a
 * near-black surface fails contrast at the 20dp sizes these icons are drawn at.
 */
private val DarkSemanticColors = CloudSemanticColors(
    success = Color(0xFF7BD79B),
    successContainer = Color(0xFF00522A),
    onSuccessContainer = Color(0xFFC6EFD3),
    caution = Color(0xFFF1C048),
    cautionContainer = Color(0xFF5C4300),
    onCautionContainer = CloudColors.CautionAmberContainer,
    danger = Color(0xFFFFB4AB),
    dangerContainer = Color(0xFF93000A),
    onDangerContainer = CloudColors.DangerRedContainer,
    unknown = Color(0xFFC2C7CE),
    unknownContainer = Color(0xFF3F4348),
    onUnknownContainer = Color(0xFFE2E5E9),
    folderTint = Color(0xFFF3C173),
    imageTint = Color(0xFFC3B0F5),
    videoTint = Color(0xFFF2A0B2),
    audioTint = Color(0xFF6FD3D3),
    pdfTint = Color(0xFFF09A8E),
    documentTint = Color(0xFFA8C4F5),
    archiveTint = Color(0xFFD3B79C),
    textTint = Color(0xFFB4C2CE),
    genericTint = Color(0xFFB6BBC2),
)

/**
 * Provides the semantic palette down the tree.
 *
 * `staticCompositionLocalOf` (not `compositionLocalOf`) on purpose: the palette only changes when
 * the light/dark mode flips, which already recomposes the whole tree. Using a static local avoids
 * tracking a read in every component that touches a file tint — of which a file grid has hundreds.
 */
val LocalCloudSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * Entry point for theme-aware values that are not on [androidx.compose.material3.MaterialTheme].
 *
 * Mirrors the shape of `MaterialTheme.colorScheme` so call sites do not have to remember two
 * different access idioms.
 */
object CloudTheme {
    /** The active semantic palette. */
    val semanticColors: CloudSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCloudSemanticColors.current
}

/**
 * The single theme wrapper every screen in the app is rendered inside.
 *
 * ### Why this exists
 * Without one wrapper, each feature module ends up constructing its own `MaterialTheme` call with
 * its own idea of the colour scheme, and the app develops a different blue per screen. This
 * function is the one place that decides: dynamic colour or brand colour, light or dark, which
 * type ramp, which shapes. Features call it once (in `MainActivity`) and then only consume tokens.
 *
 * ### Precedence
 * 1. If [themeMode] resolves to dark, the dark scheme is used — including when dynamic colour is
 *    on and the *system* is light but the user explicitly chose [ThemeMode.DARK]. The user's
 *    explicit choice always wins over the system setting.
 * 2. If [dynamicColor] is true **and** the device runs API 31+, the wallpaper-derived scheme is
 *    used. This is the Android 12+ "Material You" behaviour users expect from a modern app.
 * 3. Otherwise the static brand schemes ([LightColorScheme]/[DarkColorScheme]) apply.
 *
 * Dynamic colour is on by default because users who set a wallpaper palette expect the app to
 * honour it, but it is a parameter rather than a hardcoded behaviour: screenshots, brand-critical
 * surfaces (a provider's own onboarding page) and tests pass `dynamicColor = false` to get a
 * deterministic palette.
 *
 * @param themeMode which of the three modes to honour; see [ThemeMode].
 * @param dynamicColor whether to derive colours from the device wallpaper on API 31+.
 * @param content the themed subtree. A trailing lambda so `NotDiskTheme { ... }` reads as a
 *   block, matching `MaterialTheme`.
 */
@Composable
fun NotDiskTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.isDark()
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Dynamic colour replaces the brand ramp, so the semantic palette must follow it into the
    // right light/dark variant; the tints themselves stay brand-owned either way.
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalCloudSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CloudTypography,
            shapes = CloudShapes,
            content = content,
        )
    }
}
