package com.tera.down

import com.tera.down.core.player.MediaSourceType
import com.tera.down.core.player.MediaValidationError
import com.tera.down.core.player.PlayerMediaValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayerMediaValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validateLocalFile_validMp4File_succeeds() {
        val testFile = tempFolder.newFile("sample_video.mp4")
        testFile.writeText("fake video binary header content")

        val result = PlayerMediaValidator.validateLocalFile(
            path = testFile.absolutePath,
            title = "Sample Video",
            libraryId = "lib_123"
        )

        assertTrue(result.isSuccess)
        val playable = result.getOrThrow()
        assertEquals(MediaSourceType.LOCAL_FILE, playable.sourceType)
        assertEquals("Sample Video", playable.title)
        assertEquals("lib_123", playable.libraryId)
        assertTrue(playable.fileSize!! > 0L)
    }

    @Test
    fun validateLocalFile_validMkvFile_succeeds() {
        val testFile = tempFolder.newFile("movie.mkv")
        testFile.writeText("sample mkv content")

        val result = PlayerMediaValidator.validateLocalFile(path = testFile.absolutePath)
        assertTrue(result.isSuccess)
        assertEquals(MediaSourceType.LOCAL_FILE, result.getOrThrow().sourceType)
        assertEquals("movie.mkv", result.getOrThrow().title)
    }

    @Test
    fun validateLocalFile_missingFile_failsWithMissingFile() {
        val missingPath = File(tempFolder.root, "non_existent.mp4").absolutePath
        val result = PlayerMediaValidator.validateLocalFile(missingPath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.MissingFile)
    }

    @Test
    fun validateLocalFile_emptyZeroByteFile_failsWithEmptyFile() {
        val emptyFile = tempFolder.newFile("empty.mp4") // 0 bytes

        val result = PlayerMediaValidator.validateLocalFile(emptyFile.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.EmptyFile)
    }

    @Test
    fun validateLocalFile_temporaryTmpExtension_failsWithTemporaryFile() {
        val tmpFile = tempFolder.newFile("downloading_video.mp4.tmp")
        tmpFile.writeText("in progress download data")

        val result = PlayerMediaValidator.validateLocalFile(tmpFile.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.TemporaryOrIncompleteFile)
    }

    @Test
    fun validateLocalFile_downloadExtension_failsWithTemporaryFile() {
        val downloadFile = tempFolder.newFile("video.download")
        downloadFile.writeText("partial download")

        val result = PlayerMediaValidator.validateLocalFile(downloadFile.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.TemporaryOrIncompleteFile)
    }

    @Test
    fun validateLocalFile_unsupportedExtension_failsWithUnsupportedFormat() {
        val textFile = tempFolder.newFile("notes.txt")
        textFile.writeText("not a video")

        val result = PlayerMediaValidator.validateLocalFile(textFile.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.UnsupportedFormat)
    }

    @Test
    fun validateOnlineUrl_validHttpsUrl_succeeds() {
        val validUrl = "https://d.terabox.app/file/stream.mp4?auth=12345"

        val result = PlayerMediaValidator.validateOnlineUrl(
            url = validUrl,
            title = "Online Stream Test"
        )

        assertTrue(result.isSuccess)
        val playable = result.getOrThrow()
        assertEquals(MediaSourceType.ONLINE_DIRECT, playable.sourceType)
        assertEquals(validUrl, playable.uri)
        assertEquals("Online Stream Test", playable.title)
    }

    @Test
    fun validateOnlineUrl_insecureHttpUrl_failsWithInsecureUrl() {
        val httpUrl = "http://d.terabox.app/file/stream.mp4"

        val result = PlayerMediaValidator.validateOnlineUrl(httpUrl)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.InsecureUrl)
    }

    @Test
    fun validateOnlineUrl_emptyOrBlankUrl_failsWithNoDirectStreamAvailable() {
        val resultNull = PlayerMediaValidator.validateOnlineUrl(null)
        val resultBlank = PlayerMediaValidator.validateOnlineUrl("   ")

        assertTrue(resultNull.isFailure)
        assertTrue(resultNull.exceptionOrNull() is MediaValidationError.NoDirectStreamAvailable)

        assertTrue(resultBlank.isFailure)
        assertTrue(resultBlank.exceptionOrNull() is MediaValidationError.NoDirectStreamAvailable)
    }

    @Test
    fun validateOnlineUrl_webPageUrl_failsWithMalformedUrl() {
        val htmlUrl = "https://terabox.com/sharing/link.html"

        val result = PlayerMediaValidator.validateOnlineUrl(htmlUrl)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.MalformedUrl)
    }

    @Test
    fun validateOnlineUrl_missingHost_failsWithMalformedUrl() {
        val invalidUrl = "https:///stream.mp4"

        val result = PlayerMediaValidator.validateOnlineUrl(invalidUrl)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaValidationError.MalformedUrl)
    }
}
