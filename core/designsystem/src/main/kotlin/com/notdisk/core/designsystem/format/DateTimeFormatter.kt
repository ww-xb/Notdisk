package com.notdisk.core.designsystem.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Renders epoch-millisecond timestamps the way a file list needs them: short, unambiguous, and
 * relative when "recently" is more useful than an exact date.
 *
 * ### Why this exists
 * A cloud drive shows modification times in a narrow column beside file names. There is no room
 * for `2024-01-02T14:30:00+08:00`, and there is no point showing it either: the user's question is
 * almost always "is this the version I just uploaded?" or "is this the old one?". The ladder in
 * [format] answers that question with the least possible text.
 *
 * The object is named with a `Cloud` prefix — and not just `DateTimeFormatter` — because
 * `java.time.format.DateTimeFormatter` is used internally and a same-named object would shadow it
 * at every call site in this file, which is a reliable source of confusing errors.
 *
 * ### Contract
 *  - `null` means "not reported" and renders as an em dash, never as 1970.
 *  - Future timestamps are treated as "just now". Server clocks drift and a file that appears to
 *    have been modified in three minutes' time should not read as `-3 分钟前`.
 *  - Every function takes `now` and `zone` explicitly (with sane defaults) so the relative ladder
 *    is fully deterministic under test rather than depending on the wall clock.
 *
 * Pure object: uses `java.time` only (available from API 26, this app's `minSdk`), so it is
 * directly unit-testable on the JVM.
 */
object CloudDateTimeFormatter {

    /** Shown when a timestamp is genuinely unknown. */
    const val UNKNOWN: String = "—"

    /** "just now" — also the graceful answer for a slightly-future timestamp. */
    const val JUST_NOW: String = "刚刚"

    private const val ONE_MINUTE_MILLIS = 60_000L
    private const val ONE_HOUR_MILLIS = 60 * ONE_MINUTE_MILLIS

    /** Days after which a timestamp stops being relative and becomes an absolute date. */
    private const val RELATIVE_DAY_LIMIT = 7L

    private val ABSOLUTE_PATTERN: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private val DATE_PATTERN: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val TIME_PATTERN: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * Formats [millis] as the most informative label that still fits a list row.
     *
     * The ladder, in order:
     *  - `null` → [UNKNOWN]
     *  - under a minute ago, or in the future → [JUST_NOW]
     *  - under an hour ago → `12 分钟前`
     *  - earlier today → `今天 14:30`
     *  - yesterday → `昨天 14:30`
     *  - two to six days ago → `3 天前`
     *  - anything older → `2024-01-02`
     *
     * The hour boundary is checked *before* the calendar-day rules on purpose: something 30 minutes
     * ago at 00:15 is "30 分钟前", not "昨天 23:45", because the elapsed time is what the user is
     * actually reasoning about.
     *
     * @param millis epoch milliseconds (UTC), or `null` when not reported.
     * @param now the reference instant; injectable for deterministic tests.
     * @param zone the zone used to decide "today"/"yesterday"; defaults to the device zone.
     */
    fun format(
        millis: Long?,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (millis == null) return UNKNOWN

        val elapsed = now - millis
        // Future (negative elapsed) or sub-minute: "just now" is the honest answer.
        if (elapsed < ONE_MINUTE_MILLIS) return JUST_NOW
        if (elapsed < ONE_HOUR_MILLIS) {
            val minutes = elapsed / ONE_MINUTE_MILLIS
            return "$minutes 分钟前"
        }

        val date = toLocalDate(millis, zone)
        val today = toLocalDate(now, zone)

        if (date == today) return "今天 ${formatTime(millis, zone)}"
        if (date == today.minusDays(1)) return "昨天 ${formatTime(millis, zone)}"

        val daysAgo = ChronoUnit.DAYS.between(date, today)
        if (daysAgo in 2 until RELATIVE_DAY_LIMIT) return "$daysAgo 天前"

        return formatDate(millis, zone)
    }

    /**
     * Formats [millis] as an exact, unambiguous timestamp (`2024-01-02 14:30`).
     *
     * Used by details panes and tooltips, where the user has explicitly asked for the precise
     * value and a relative label would be a downgrade.
     */
    fun formatAbsolute(
        millis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (millis == null) return UNKNOWN
        return ABSOLUTE_PATTERN.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    /** Formats [millis] as a calendar date only (`2024-01-02`); [UNKNOWN] when `null`. */
    fun formatDate(
        millis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (millis == null) return UNKNOWN
        return DATE_PATTERN.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    /** Formats [millis] as a wall-clock time only (`14:30`); [UNKNOWN] when `null`. */
    fun formatTime(
        millis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (millis == null) return UNKNOWN
        return TIME_PATTERN.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    private fun toLocalDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
