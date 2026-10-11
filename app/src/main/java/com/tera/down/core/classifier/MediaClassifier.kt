package com.tera.down.core.classifier

import com.tera.down.core.model.MediaType

/**
 * Deterministic classifier for media files according to Section 7 of the Terabox specification.
 *
 * Rules:
 * - Check the filename extension first, case-insensitively.
 * - Recognized videos: mp4, mkv, mov, avi, webm, m4v, ts, flv, wmv, 3gp.
 * - Recognized photos: jpg, jpeg, png, webp, heic, gif, bmp, tiff.
 * - Fallback to numeric category code only if the extension is missing or unrecognized:
 *   - Category 1 -> Video
 *   - Category 3 -> Photo
 *   - Otherwise  -> Other
 */
object MediaClassifier {

    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "flv", "wmv", "3gp"
    )

    val PHOTO_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "gif", "bmp", "tiff"
    )

    /**
     * Extracts the lowercase file extension without the leading dot.
     * Returns an empty string if there is no extension or if the filename ends with a dot.
     */
    fun extractExtension(filename: String?): String {
        if (filename.isNullOrBlank()) return ""
        val trimmed = filename.trim()
        val lastDotIndex = trimmed.lastIndexOf('.')
        if (lastDotIndex == -1 || lastDotIndex == trimmed.length - 1) {
            return ""
        }
        return trimmed.substring(lastDotIndex + 1).trim().lowercase()
    }

    /**
     * Checks if a given extension string corresponds to a supported video format.
     */
    fun isVideoExtension(extension: String?): Boolean {
        if (extension.isNullOrBlank()) return false
        val clean = extension.trim().lowercase().removePrefix(".")
        return VIDEO_EXTENSIONS.contains(clean)
    }

    /**
     * Checks if a given extension string corresponds to a supported photo format.
     */
    fun isPhotoExtension(extension: String?): Boolean {
        if (extension.isNullOrBlank()) return false
        val clean = extension.trim().lowercase().removePrefix(".")
        return PHOTO_EXTENSIONS.contains(clean)
    }

    /**
     * Classifies a file entry into MediaType.VIDEO, MediaType.PHOTO, or MediaType.OTHER.
     * Extension matching takes precedence over category codes.
     */
    fun classify(filename: String?, category: Int? = null): MediaType {
        val ext = extractExtension(filename)
        if (ext.isNotEmpty()) {
            if (VIDEO_EXTENSIONS.contains(ext)) {
                return MediaType.VIDEO
            }
            if (PHOTO_EXTENSIONS.contains(ext)) {
                return MediaType.PHOTO
            }
        }

        return when (category) {
            1 -> MediaType.VIDEO
            3 -> MediaType.PHOTO
            else -> MediaType.OTHER
        }
    }
}
