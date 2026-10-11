package com.tera.down

import com.tera.down.feature.downloader.FilenameSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameSanitizerTest {

    @Test
    fun pathTraversal_isNeutralized() {
        assertEquals("passwd", FilenameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("cmd.exe", FilenameSanitizer.sanitize("..\\..\\windows\\system32\\cmd.exe"))
        assertEquals("vacation.mp4", FilenameSanitizer.sanitize("/storage/emulated/0/Download/vacation.mp4"))
        assertEquals("file.jpg", FilenameSanitizer.sanitize("../sub/file.jpg"))
    }

    @Test
    fun illegalCharacters_replacedWithUnderscores() {
        assertEquals("my_movie_clip_.mp4", FilenameSanitizer.sanitize("my:movie*clip?.mp4"))
        assertEquals("report_v1_test_.pdf", FilenameSanitizer.sanitize("report<v1>|\"test\".pdf"))
        assertEquals("clean_name_.bin", FilenameSanitizer.sanitize("clean\u0000name\u001F.bin"))
    }

    @Test
    fun emptyOrBlank_fallsBackToDefault() {
        val def = "fallback.bin"
        assertEquals(def, FilenameSanitizer.sanitize(null, def))
        assertEquals(def, FilenameSanitizer.sanitize("", def))
        assertEquals(def, FilenameSanitizer.sanitize("   ", def))
        assertEquals(def, FilenameSanitizer.sanitize(".", def))
        assertEquals(def, FilenameSanitizer.sanitize("..", def))
        assertEquals(def, FilenameSanitizer.sanitize("../..", def))
    }

    @Test
    fun excessiveLength_isTruncatedWhilePreservingExtension() {
        val longBase = "a".repeat(250)
        val longName = "$longBase.mp4"
        val sanitized = FilenameSanitizer.sanitize(longName)

        assertTrue(sanitized.length <= 180)
        assertTrue(sanitized.endsWith(".mp4"))
    }

    @Test
    fun contentDisposition_extractionAndSanitization() {
        val standard = FilenameSanitizer.extractFromContentDisposition("""attachment; filename="my_holiday.mp4"""")
        assertEquals("my_holiday.mp4", standard)

        val unquoted = FilenameSanitizer.extractFromContentDisposition("attachment; filename=clip.mov")
        assertEquals("clip.mov", unquoted)

        val traversalAttempt = FilenameSanitizer.extractFromContentDisposition("""attachment; filename="../../../bad.sh"""")
        assertEquals("bad.sh", traversalAttempt)

        val nullHeader = FilenameSanitizer.extractFromContentDisposition(null)
        assertEquals(null, nullHeader)

        val blankHeader = FilenameSanitizer.extractFromContentDisposition("   ")
        assertEquals(null, blankHeader)
    }
}
