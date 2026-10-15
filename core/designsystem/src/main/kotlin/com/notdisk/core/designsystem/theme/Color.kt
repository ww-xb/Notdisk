package com.notdisk.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The brand palette for the whole app.
 *
 * ### Why this exists
 * A cloud-drive browser is a *content* surface: the user stares at file names, sizes and
 * thumbnails for a long time. Chrome colours therefore have to stay quiet enough that the accent
 * can carry meaning. The palette is built around a calm indigo-blue primary (trust, storage,
 * "the cloud") with a warmer tertiary used sparingly for highlights, and a deliberately
 * desaturated surface ramp so that file rows never compete with the content they describe.
 *
 * The palette is expressed as plain [Color] constants so callers can reach for a brand colour
 * outside a composable (`CloudColors.PrimaryBlue`) without reading from the composition.
 */
object CloudColors {
    // ---- Primary: calm indigo-blue. Deep enough to read as white text in light mode. ----
    val PrimaryBlue = Color(0xFF2C5FD6)
    val OnPrimaryBlue = Color(0xFFFFFFFF)
    val PrimaryBlueContainer = Color(0xFFDCE4FF)
    val OnPrimaryBlueContainer = Color(0xFF00174B)

    // ---- Secondary: muted slate, used for supporting chrome (chips, secondary buttons). ----
    val SecondarySlate = Color(0xFF565E71)
    val OnSecondarySlate = Color(0xFFFFFFFF)
    val SecondarySlateContainer = Color(0xFFDAE2F9)
    val OnSecondarySlateContainer = Color(0xFF131B2C)

    // ---- Tertiary: warm amber accent, reserved for "attention" moments (pending auth, stars). ----
    val TertiaryAmber = Color(0xFF7A5900)
    val OnTertiaryAmber = Color(0xFFFFFFFF)
    val TertiaryAmberContainer = Color(0xFFFFDF9B)
    val OnTertiaryAmberContainer = Color(0xFF261A00)

    // ---- Error: a red that stays legible on both ramps. ----
    val ErrorRed = Color(0xFFBA1A1A)
    val OnErrorRed = Color(0xFFFFFFFF)
    val ErrorRedContainer = Color(0xFFFFDAD6)
    val OnErrorRedContainer = Color(0xFF410002)

    // ---- Surfaces (light). A very slightly blue-tinted white keeps long lists from glaring. ----
    val SurfaceLight = Color(0xFFFCFCFF)
    val OnSurfaceLight = Color(0xFF1A1B21)
    val SurfaceVariantLight = Color(0xFFE1E2EC)
    val OnSurfaceVariantLight = Color(0xFF44464F)
    val OutlineLight = Color(0xFF757780)
    val OutlineVariantLight = Color(0xFFC5C6D0)
    val SurfaceContainerLight = Color(0xFFF0F0F7)
    val SurfaceContainerHighLight = Color(0xFFEAEAF1)
    val InverseSurfaceLight = Color(0xFF2F3036)
    val InverseOnSurfaceLight = Color(0xFFF1F0F7)

    // ---- Surfaces (dark). Lifted off pure black so elevation reads without harsh banding. ----
    val SurfaceDark = Color(0xFF121318)
    val OnSurfaceDark = Color(0xFFE3E2E9)
    val SurfaceVariantDark = Color(0xFF44464F)
    val OnSurfaceVariantDark = Color(0xFFC5C6D0)
    val OutlineDark = Color(0xFF8F9099)
    val OutlineVariantDark = Color(0xFF44464F)
    val SurfaceContainerDark = Color(0xFF1E1F25)
    val SurfaceContainerHighDark = Color(0xFF282A2F)
    val InverseSurfaceDark = Color(0xFFE3E2E9)
    val InverseOnSurfaceDark = Color(0xFF2F3036)

    // ---- Semantic accents that are not part of the Material ramp ----
    /**
     * The "we could not verify this" colour. Used by capability badges and auth chips where the
     * state is neither a success nor a failure and must not be mistaken for either.
     */
    val CautionAmber = Color(0xFF9A6A00)
    val CautionAmberContainer = Color(0xFFFFE7B8)
    val OnCautionAmberContainer = Color(0xFF2E2000)

    /** The "this is confirmed broken / revoked" colour, darker than [ErrorRed] for badges. */
    val DangerRedContainer = Color(0xFFFFDAD6)

    /** File-type tints. Thumbnail-less tiles pick their leading icon tint from this ramp. */
    val FolderAmber = Color(0xFFE8A33D)
    val ImageViolet = Color(0xFF7C5CD6)
    val VideoRose = Color(0xFFD9536F)
    val AudioTeal = Color(0xFF1F8A8A)
    val PdfCrimson = Color(0xFFC0392B)
    val DocumentBlue = Color(0xFF3B76D6)
    val ArchiveBrown = Color(0xFF8A6A4F)
    val TextSlate = Color(0xFF5C6B7A)
    val GenericGrey = Color(0xFF7A7F87)
}

/**
 * Static light scheme.
 *
 * Kept separate from [NotDiskTheme] so previews and screenshot tests can render components
 * without entering the composition, and so dynamic colour has an explicit fallback.
 */
val LightColorScheme: ColorScheme = lightColorScheme(
    primary = CloudColors.PrimaryBlue,
    onPrimary = CloudColors.OnPrimaryBlue,
    primaryContainer = CloudColors.PrimaryBlueContainer,
    onPrimaryContainer = CloudColors.OnPrimaryBlueContainer,
    inversePrimary = Color(0xFFB3C5FF),
    secondary = CloudColors.SecondarySlate,
    onSecondary = CloudColors.OnSecondarySlate,
    secondaryContainer = CloudColors.SecondarySlateContainer,
    onSecondaryContainer = CloudColors.OnSecondarySlateContainer,
    tertiary = CloudColors.TertiaryAmber,
    onTertiary = CloudColors.OnTertiaryAmber,
    tertiaryContainer = CloudColors.TertiaryAmberContainer,
    onTertiaryContainer = CloudColors.OnTertiaryAmberContainer,
    error = CloudColors.ErrorRed,
    onError = CloudColors.OnErrorRed,
    errorContainer = CloudColors.ErrorRedContainer,
    onErrorContainer = CloudColors.OnErrorRedContainer,
    background = CloudColors.SurfaceLight,
    onBackground = CloudColors.OnSurfaceLight,
    surface = CloudColors.SurfaceLight,
    onSurface = CloudColors.OnSurfaceLight,
    surfaceVariant = CloudColors.SurfaceVariantLight,
    onSurfaceVariant = CloudColors.OnSurfaceVariantLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6FC),
    surfaceContainer = CloudColors.SurfaceContainerLight,
    surfaceContainerHigh = CloudColors.SurfaceContainerHighLight,
    surfaceContainerHighest = Color(0xFFE4E4EB),
    surfaceTint = CloudColors.PrimaryBlue,
    inverseSurface = CloudColors.InverseSurfaceLight,
    inverseOnSurface = CloudColors.InverseOnSurfaceLight,
    outline = CloudColors.OutlineLight,
    outlineVariant = CloudColors.OutlineVariantLight,
    scrim = Color(0xFF000000),
)

/** Static dark scheme; the counterpart to [LightColorScheme] and the API < 31 fallback. */
val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF0B3EA8),
    onPrimaryContainer = Color(0xFFDCE4FF),
    inversePrimary = CloudColors.PrimaryBlue,
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = CloudColors.SecondarySlateContainer,
    tertiary = Color(0xFFF1C048),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = CloudColors.TertiaryAmberContainer,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = CloudColors.ErrorRedContainer,
    background = CloudColors.SurfaceDark,
    onBackground = CloudColors.OnSurfaceDark,
    surface = CloudColors.SurfaceDark,
    onSurface = CloudColors.OnSurfaceDark,
    surfaceVariant = CloudColors.SurfaceVariantDark,
    onSurfaceVariant = CloudColors.OnSurfaceVariantDark,
    surfaceContainerLowest = Color(0xFF0C0D12),
    surfaceContainerLow = Color(0xFF191A20),
    surfaceContainer = CloudColors.SurfaceContainerDark,
    surfaceContainerHigh = CloudColors.SurfaceContainerHighDark,
    surfaceContainerHighest = Color(0xFF33353A),
    surfaceTint = Color(0xFFB3C5FF),
    inverseSurface = CloudColors.InverseSurfaceDark,
    inverseOnSurface = CloudColors.InverseOnSurfaceDark,
    outline = CloudColors.OutlineDark,
    outlineVariant = CloudColors.OutlineVariantDark,
    scrim = Color(0xFF000000),
)
