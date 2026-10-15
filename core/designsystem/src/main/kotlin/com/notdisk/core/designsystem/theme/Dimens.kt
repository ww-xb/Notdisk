package com.notdisk.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale, as constants.
 *
 * ### Why this exists
 * Padding is where a UI most visibly drifts: one screen uses 12dp gutters, the next 16dp, and the
 * two look "wrong" side by side without anyone being able to say why. A fixed 4dp-based ladder
 * removes the decision. Components in this module never inline a raw `.dp` for spacing — they
 * read a step from here, so changing the density of the whole app is a one-file edit.
 *
 * The scale is intentionally short. Six steps cover every gap this product needs; adding a
 * seventh is a signal that a layout is being over-tuned.
 */
object Dimens {
    /** 0dp. Named so call sites read `Dimens.None` rather than `0.dp` when the zero is a choice. */
    val None: Dp = 0.dp

    /** 2dp. Hairline separation: between a badge's border and its text, icon optical nudges. */
    val Hairline: Dp = 2.dp

    /** 4dp. Tightest gap: icon-to-label inside a chip, stacked lines inside one row. */
    val XSmall: Dp = 4.dp

    /** 8dp. Default gap between sibling controls, and the inner padding of compact badges. */
    val Small: Dp = 8.dp

    /** 12dp. Gap between a leading icon and its text column; grid gutter. */
    val Medium: Dp = 12.dp

    /** 16dp. Standard screen gutter and list-row horizontal padding. */
    val Large: Dp = 16.dp

    /** 24dp. Section separation, dialog content padding, empty-state inner padding. */
    val XLarge: Dp = 24.dp

    /** 32dp. Large vertical separation: above an empty state, between major blocks. */
    val XXLarge: Dp = 32.dp

    /** 48dp. Reserved vertical space for hero/empty-state artwork. */
    val Huge: Dp = 48.dp

    // ---- Fixed component sizes. Named because they are *touch target* decisions, not styling. ----

    /**
     * 48dp minimum interactive size. Matches the Android accessibility guidance; every
     * icon-only control in this module is at least this large even when its glyph is 24dp.
     */
    val MinTouchTarget: Dp = 48.dp

    /** 40dp square thumbnail/avatar in a list row. */
    val ThumbnailSmall: Dp = 40.dp

    /** 56dp leading icon container in a list row. */
    val ThumbnailMedium: Dp = 56.dp

    /** 24dp stock icon size; the Material default for inline glyphs. */
    val IconSmall: Dp = 20.dp

    /** 24dp standard icon. */
    val IconMedium: Dp = 24.dp

    /** 32dp hero icon, used by empty and error states. */
    val IconLarge: Dp = 32.dp

    /** 64dp empty/error-state illustration glyph. */
    val IconHero: Dp = 64.dp

    /** 1dp hairline divider. */
    val Divider: Dp = 1.dp

    /** 2dp progress-track thickness used by [com.notdisk.core.designsystem.component.StatRow]. */
    val ProgressTrack: Dp = 6.dp
}
