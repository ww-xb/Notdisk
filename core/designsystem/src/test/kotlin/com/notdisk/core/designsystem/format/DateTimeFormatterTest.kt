package com.notdisk.core.designsystem.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Behavioural tests for [CloudDateTimeFormatter].
 *
 * Every case injects both `now` and a fixed [ZoneId] so the relative ladder is deterministic: a
 * test that reads the wall clock would fail in a different timezone or one minute later, which is
 * exactly the kind of flakiness that gets a suite ignored.
 */
class CloudDateTimeFormatterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** Builds an epoch-millis value from a local wall-clock time in [zone]. */
    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone)
        .toInstant()
        .toEpochMilli()

    // ---- null handling ----

    @Test
    fun `null timestamp renders as an em dash rather than the epoch`() {
        assertEquals("—", CloudDateTimeFormatter.format(null))
        assertEquals("—", CloudDateTimeFormatter.formatAbsolute(null))
        assertEquals("—", CloudDateTimeFormatter.formatDate(null))
        assertEquals("—", CloudDateTimeFormatter.formatTime(null))
    }

    // ---- the relative ladder ----

    @Test
    fun `just-now covers anything under a minute`() {
        val now = at(2024, 1, 2, 14, 30)
        assertEquals("刚刚", CloudDateTimeFormatter.format(now, now = now, zone = zone))
        assertEquals("刚刚", CloudDateTimeFormatter.format(now - 59_000L, now = now, zone = zone))
    }

    @Test
    fun `minutes are shown between one minute and one hour`() {
        val now = at(2024, 1, 2, 14, 30)
        assertEquals("1 分钟前", CloudDateTimeFormatter.format(now - 60_000L, now = now, zone = zone))
        assertEquals("5 分钟前", CloudDateTimeFormatter.format(now - 5 * 60_000L, now = now, zone = zone))
        assertEquals("59 分钟前", CloudDateTimeFormatter.format(now - 59 * 60_000L, now = now, zone = zone))
    }

    @Test
    fun `future timestamps degrade to just-now instead of a negative relative label`() {
        // Server clocks drift; "-3 分钟前" would be nonsense.
        val now = at(2024, 1, 2, 14, 30)
        assertEquals("刚刚", CloudDateTimeFormatter.format(now + 5 * 60_000L, now = now, zone = zone))
        assertEquals("刚刚", CloudDateTimeFormatter.format(now + 86_400_000L, now = now, zone = zone))
    }

    @Test
    fun `earlier today shows the clock time`() {
        val now = at(2024, 1, 2, 14, 30)
        val earlier = at(2024, 1, 2, 9, 5)
        assertEquals("今天 09:05", CloudDateTimeFormatter.format(earlier, now = now, zone = zone))
    }

    @Test
    fun `elapsed time wins over the calendar boundary just after midnight`() {
        // 23:45 yesterday viewed at 00:15 is "30 分钟前", not "昨天 23:45": the user is reasoning
        // about elapsed time, not about which date it was.
        val now = at(2024, 1, 2, 0, 15)
        val earlier = at(2024, 1, 1, 23, 45)
        assertEquals("30 分钟前", CloudDateTimeFormatter.format(earlier, now = now, zone = zone))
    }

    @Test
    fun `yesterday shows the clock time`() {
        val now = at(2024, 1, 2, 10, 0)
        val yesterday = at(2024, 1, 1, 14, 30)
        assertEquals("昨天 14:30", CloudDateTimeFormatter.format(yesterday, now = now, zone = zone))
    }

    @Test
    fun `two to six days ago uses a day count`() {
        val now = at(2024, 1, 10, 10, 0)
        assertEquals("2 天前", CloudDateTimeFormatter.format(at(2024, 1, 8, 9, 0), now = now, zone = zone))
        assertEquals("3 天前", CloudDateTimeFormatter.format(at(2024, 1, 7, 9, 0), now = now, zone = zone))
        assertEquals("6 天前", CloudDateTimeFormatter.format(at(2024, 1, 4, 9, 0), now = now, zone = zone))
    }

    @Test
    fun `a week or more falls back to an absolute date`() {
        val now = at(2024, 1, 10, 10, 0)
        assertEquals("2024-01-03", CloudDateTimeFormatter.format(at(2024, 1, 3, 9, 0), now = now, zone = zone))
        assertEquals("2024-01-02", CloudDateTimeFormatter.format(at(2024, 1, 2, 9, 0), now = now, zone = zone))
    }

    @Test
    fun `an old timestamp in a previous year renders as a plain date`() {
        val now = at(2024, 1, 15, 10, 0)
        assertEquals("2023-12-31", CloudDateTimeFormatter.format(at(2023, 12, 31, 23, 0), now = now, zone = zone))
    }

    @Test
    fun `recency is decided by elapsed calendar days, not by the calendar year`() {
        // New Year's Eve is still "2 天前" on 2 January: the ladder is driven by elapsed days, and
        // a year boundary is not by itself a reason to switch to an absolute date.
        val now = at(2024, 1, 2, 10, 0)
        assertEquals("2 天前", CloudDateTimeFormatter.format(at(2023, 12, 31, 23, 0), now = now, zone = zone))
    }

    // ---- absolute helpers ----

    @Test
    fun `formatAbsolute always produces a full timestamp`() {
        val millis = at(2024, 1, 2, 14, 30)
        assertEquals("2024-01-02 14:30", CloudDateTimeFormatter.formatAbsolute(millis, zone))
    }

    @Test
    fun `formatDate and formatTime split the timestamp`() {
        val millis = at(2024, 1, 2, 14, 30)
        assertEquals("2024-01-02", CloudDateTimeFormatter.formatDate(millis, zone))
        assertEquals("14:30", CloudDateTimeFormatter.formatTime(millis, zone))
    }

    @Test
    fun `midnight is formatted as 00 00 rather than 24 00`() {
        val millis = at(2024, 1, 2, 0, 0)
        assertEquals("00:00", CloudDateTimeFormatter.formatTime(millis, zone))
    }

    @Test
    fun `the same instant renders differently in another timezone`() {
        val millis = at(2024, 1, 2, 14, 30)
        // 14:30 in Shanghai is 06:30 in London in January (UTC+0).
        assertEquals("2024-01-02 06:30", CloudDateTimeFormatter.formatAbsolute(millis, ZoneId.of("Europe/London")))
    }
}
