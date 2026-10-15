package com.notdisk.core.designsystem.format

import java.util.Locale
import kotlin.math.abs

/**
 * Turns raw byte counts into the short labels a file browser shows next to every item.
 *
 * ### Why this exists
 * File sizes appear in a list row, a grid cell, a details pane and a transfer notification, and
 * every one of them must agree. Without a single formatter the app grows four subtly different
 * implementations that disagree at the boundaries — one shows `1023 B`, another `1.0 KB`, a third
 * crashes on a negative size reported by a buggy vendor.
 *
 * ### Contract
 *  - `null` means "the provider did not report a size" and renders as an em dash, never `0 B`.
 *    Showing `0 B` for an unknown size is a lie the user acts on.
 *  - Negative values (a corrupted or placeholder vendor response) never throw and never produce
 *    `-0 B`; the sign is preserved and the magnitude formatted.
 *  - Units are binary (1024), matching what every desktop file manager reports, so a file the user
 *    sees as "1.5 GB" elsewhere is not "1.6 GB" here.
 *
 * Pure object: no Android types, so it is directly unit-testable on the JVM.
 */
object FileSizeFormatter {

    /** Shown when a size is genuinely unknown. An em dash, not a zero. */
    const val UNKNOWN: String = "—"

    private const val UNIT = 1024.0
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /**
     * Formats [bytes] for display, e.g. `0 B`, `512 B`, `1.5 KB`, `2 MB`, `3.4 GB`.
     *
     * Bytes are shown as whole numbers (a fractional byte is meaningless); larger units get one
     * decimal place, dropped when it would be a trailing `.0` so a round `2 GB` does not read as
     * false precision.
     *
     * @param bytes size in bytes, or `null` when unknown.
     * @return a display-ready label, or [UNKNOWN] for `null`.
     */
    fun formatBytes(bytes: Long?): String {
        if (bytes == null) return UNKNOWN
        if (bytes == 0L) return "0 B"

        val negative = bytes < 0
        // Convert before abs(): Long.MIN_VALUE has no positive counterpart, so negating it as a
        // Long would silently wrap. Doing the maths in Double sidesteps that entirely.
        var value = abs(bytes.toDouble())
        var unitIndex = 0

        while (value >= UNIT && unitIndex < UNITS.lastIndex) {
            value /= UNIT
            unitIndex++
        }

        val rendered = if (unitIndex == 0) {
            // Whole bytes only.
            formatDecimal(value, decimals = 0)
        } else {
            formatDecimal(value, decimals = 1)
        }

        val sign = if (negative) "-" else ""
        return "$sign$rendered ${UNITS[unitIndex]}"
    }

    /**
     * Formats a *count* of things (files, folders, items selected).
     *
     * Counts are grouped with thousands separators because a folder with 12,480 children is
     * otherwise unreadable at a glance. Same `null` and negative rules as [formatBytes].
     *
     * @param count how many items, or `null` when unknown.
     */
    fun formatCount(count: Long?): String {
        if (count == null) return UNKNOWN
        return String.format(Locale.US, "%,d", count)
    }

    /**
     * Formats a single decimal-place number, trimming a trailing `.0`.
     *
     * [Locale.US] is deliberate rather than the device locale: this is a *numeric* label sitting
     * beside a unit symbol, and using a locale that renders Arabic-Indic digits or a comma decimal
     * separator here would make sizes disagree with the same file shown elsewhere in the OS.
     */
    private fun formatDecimal(value: Double, decimals: Int): String {
        val formatted = String.format(Locale.US, "%.${decimals}f", value)
        return if (decimals > 0 && formatted.endsWith(".0")) {
            formatted.dropLast(2)
        } else {
            formatted
        }
    }
}
