package com.notdisk.core.designsystem.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [FileSizeFormatter].
 *
 * These pin the parts of the contract the UI depends on and that a refactor could silently break:
 * binary units, the `null`/negative rules, and the trailing-`.0` trimming that keeps round values
 * from reading as false precision.
 */
class FileSizeFormatterTest {

    @Test
    fun `null size renders as an em dash rather than zero`() {
        // "0 B" would be a lie the user acts on: it claims the file is empty.
        assertEquals("—", FileSizeFormatter.formatBytes(null))
    }

    @Test
    fun `zero renders as whole bytes`() {
        assertEquals("0 B", FileSizeFormatter.formatBytes(0L))
    }

    @Test
    fun `values below one kilobyte stay in bytes`() {
        assertEquals("1 B", FileSizeFormatter.formatBytes(1L))
        assertEquals("512 B", FileSizeFormatter.formatBytes(512L))
        assertEquals("1023 B", FileSizeFormatter.formatBytes(1023L))
    }

    @Test
    fun `kilobyte boundary is binary and keeps one decimal`() {
        assertEquals("1 KB", FileSizeFormatter.formatBytes(1024L))
        assertEquals("1.5 KB", FileSizeFormatter.formatBytes(1536L))
    }

    @Test
    fun `units scale up to terabytes`() {
        assertEquals("1 MB", FileSizeFormatter.formatBytes(1024L * 1024))
        assertEquals("1 GB", FileSizeFormatter.formatBytes(1024L * 1024 * 1024))
        assertEquals("1 TB", FileSizeFormatter.formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `round values drop the trailing decimal`() {
        // "2.0 MB" reads as false precision; the value is exactly 2 MB.
        assertEquals("2 MB", FileSizeFormatter.formatBytes(2L * 1024 * 1024))
        assertEquals("3 GB", FileSizeFormatter.formatBytes(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun `fractional larger units keep one decimal place`() {
        // 4.2 GB, a typical video file.
        assertEquals("4.2 GB", FileSizeFormatter.formatBytes(4_509_715_660L))
    }

    @Test
    fun `negative sizes are formatted safely and keep their sign`() {
        // A corrupted vendor response must not throw and must not render "-0 B".
        assertEquals("-1 B", FileSizeFormatter.formatBytes(-1L))
        assertEquals("-1 KB", FileSizeFormatter.formatBytes(-1024L))
    }

    @Test
    fun `long minimum value does not overflow`() {
        // Negating Long.MIN_VALUE as a Long would wrap to itself; the formatter must not do that.
        val result = FileSizeFormatter.formatBytes(Long.MIN_VALUE)
        assertTrue(result.startsWith("-"))
        assertTrue(result.endsWith(" PB") || result.endsWith(" TB"))
    }

    @Test
    fun `very large values cap at the largest known unit`() {
        val result = FileSizeFormatter.formatBytes(Long.MAX_VALUE)
        assertTrue("expected the unit ladder to stop at PB: $result", result.endsWith(" PB"))
    }

    @Test
    fun `null count renders as an em dash`() {
        assertEquals("—", FileSizeFormatter.formatCount(null))
    }

    @Test
    fun `counts are grouped with thousands separators`() {
        assertEquals("0", FileSizeFormatter.formatCount(0L))
        assertEquals("999", FileSizeFormatter.formatCount(999L))
        assertEquals("1,000", FileSizeFormatter.formatCount(1_000L))
        assertEquals("12,480", FileSizeFormatter.formatCount(12_480L))
    }

    @Test
    fun `negative counts keep their sign`() {
        assertEquals("-1,500", FileSizeFormatter.formatCount(-1_500L))
    }
}
