package com.tera.down

import com.tera.down.ui.components.Formatters
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", Formatters.formatBytes(0L))
        assertEquals("500 B", Formatters.formatBytes(500L))
        assertEquals("1.0 KB", Formatters.formatBytes(1024L))
        assertEquals("1.5 MB", Formatters.formatBytes(1572864L))
        assertEquals("2.0 GB", Formatters.formatBytes(2147483648L))
    }

    @Test
    fun testFormatSpeed() {
        assertEquals("0 B/s", Formatters.formatSpeed(0L))
        assertEquals("1.0 MB/s", Formatters.formatSpeed(1048576L))
    }

    @Test
    fun testFormatDuration() {
        assertEquals("--:--", Formatters.formatDuration(null))
        assertEquals("00:45", Formatters.formatDuration(45000L))
        assertEquals("01:30", Formatters.formatDuration(90000L))
        assertEquals("1:05:00", Formatters.formatDuration(3900000L))
    }
}
