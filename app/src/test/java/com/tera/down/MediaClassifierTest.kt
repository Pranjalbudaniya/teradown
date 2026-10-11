package com.tera.down

import com.tera.down.core.classifier.MediaClassifier
import com.tera.down.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {

    @Test
    fun classify_allVideoExtensions_returnsVideo() {
        val videoExtensions = listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "flv", "wmv", "3gp")
        for (ext in videoExtensions) {
            val filename = "test_file.$ext"
            assertEquals("Failed for extension: $ext", MediaType.VIDEO, MediaClassifier.classify(filename))
            assertTrue("isVideoExtension failed for: $ext", MediaClassifier.isVideoExtension(ext))
        }
    }

    @Test
    fun classify_allPhotoExtensions_returnsPhoto() {
        val photoExtensions = listOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp", "tiff")
        for (ext in photoExtensions) {
            val filename = "photo_shot.$ext"
            assertEquals("Failed for extension: $ext", MediaType.PHOTO, MediaClassifier.classify(filename))
            assertTrue("isPhotoExtension failed for: $ext", MediaClassifier.isPhotoExtension(ext))
        }
    }

    @Test
    fun classify_uppercaseAndMixedCaseExtensions_recognizedCorrectly() {
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("VACATION.MP4"))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("CLIP.Mkv"))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("DRONE.MOV"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("FAMILY.JPG"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("PORTRAIT.Jpeg"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("SCREENSHOT.PNG"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("IMAGE.WeBp"))
    }

    @Test
    fun classify_multipleDotsInFilename_usesFinalExtension() {
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("my.holiday.trip.mp4"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("raw.camera.shot.jpg"))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("backup.tar.gz"))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("archive.7z"))
    }

    @Test
    fun classify_unknownExtensions_returnsOther() {
        val unsupported = listOf("zip", "rar", "exe", "iso", "pdf", "docx", "txt", "apk", "bin", "dmg")
        for (ext in unsupported) {
            assertEquals("Failed for: $ext", MediaType.OTHER, MediaClassifier.classify("file.$ext"))
            assertFalse(MediaClassifier.isVideoExtension(ext))
            assertFalse(MediaClassifier.isPhotoExtension(ext))
        }
    }

    @Test
    fun classify_missingExtension_returnsOtherWhenCategoryAbsent() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify("file_without_extension"))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("trailing_dot."))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("."))
        assertEquals(MediaType.OTHER, MediaClassifier.classify(""))
        assertEquals(MediaType.OTHER, MediaClassifier.classify(null))
    }

    @Test
    fun classify_categoryFallback_whenExtensionIsUnrecognizedOrMissing() {
        // Category 1 is video fallback
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("movie_stream", category = 1))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("unrecognized.bin", category = 1))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("archive.zip", category = 1))

        // Category 3 is photo fallback
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("picture_stream", category = 3))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("unrecognized.dat", category = 3))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("image_without_ext", category = 3))

        // Other categories fallback to OTHER
        assertEquals(MediaType.OTHER, MediaClassifier.classify("file.bin", category = 2))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("file.bin", category = 6))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("file.bin", category = null))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("file.bin", category = 0))
    }

    @Test
    fun classify_categoryFallback_doesNotOverrideRecognizedExtension() {
        // A video file (.mp4) with photo category (3) must still be classified as VIDEO
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("clip.mp4", category = 3))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("video.mkv", category = 6))

        // A photo file (.jpg) with video category (1) must still be classified as PHOTO
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("image.jpg", category = 1))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("snapshot.png", category = 2))
    }

    @Test
    fun extractExtension_helperVerification() {
        assertEquals("mp4", MediaClassifier.extractExtension("video.mp4"))
        assertEquals("jpg", MediaClassifier.extractExtension("path/to/my.photo.jpg"))
        assertEquals("", MediaClassifier.extractExtension("no_ext"))
        assertEquals("", MediaClassifier.extractExtension("file."))
        assertEquals("", MediaClassifier.extractExtension(""))
        assertEquals("", MediaClassifier.extractExtension(null))
    }
}
