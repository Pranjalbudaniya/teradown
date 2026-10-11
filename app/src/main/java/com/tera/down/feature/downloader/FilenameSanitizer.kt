package com.tera.down.feature.downloader

import java.net.URLDecoder

/**
 * Utility for sanitizing filenames to protect against directory traversal,
 * illegal filesystem characters, and excessively long names.
 */
object FilenameSanitizer {

    private const val MAX_FILENAME_LENGTH = 180
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")

    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Sanitizes [rawName] into a safe filename suitable for local filesystem storage.
     * Replaces illegal characters with underscores, strips path traversal elements,
     * decodes URL-encoded traversal attempts, handles reserved FAT32 names,
     * and constrains length while preserving extension.
     */
    fun sanitize(
        rawName: String?,
        defaultName: String = "download_${System.currentTimeMillis()}.bin"
    ): String {
        if (rawName.isNullOrBlank()) return defaultName

        // 0. URL decode if percent-encoded sequences exist
        val decoded = if (rawName.contains('%')) {
            try {
                URLDecoder.decode(rawName, "UTF-8")
            } catch (_: Exception) {
                rawName
            }
        } else {
            rawName
        }

        // 1. Normalize separators and extract basename only (defense against traversal)
        var cleaned = decoded
            .replace("\\", "/")
            .substringAfterLast("/")
            .trim()

        // 2. Remove leading dots and slashes to prevent hidden files or traversal prefixes
        while (cleaned.startsWith(".") || cleaned.startsWith("/") || cleaned.startsWith("\\")) {
            cleaned = cleaned.trimStart('.', '/', '\\').trim()
        }

        // 3. Replace illegal file system characters with underscore
        cleaned = ILLEGAL_CHARS.replace(cleaned, "_")

        // 4. Collapse consecutive underscores and trim trailing dots/spaces
        cleaned = cleaned.replace(Regex("_+"), "_").trim().trimEnd('.')

        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            return defaultName
        }

        // 5. Constrain length while preserving extension
        if (cleaned.length > MAX_FILENAME_LENGTH) {
            val dotIndex = cleaned.lastIndexOf('.')
            cleaned = if (dotIndex in 1 until cleaned.length - 1) {
                val ext = cleaned.substring(dotIndex)
                val base = cleaned.substring(0, dotIndex)
                val maxBaseLen = (MAX_FILENAME_LENGTH - ext.length).coerceAtLeast(10)
                "${base.take(maxBaseLen)}$ext"
            } else {
                cleaned.take(MAX_FILENAME_LENGTH)
            }
        }

        // 6. Protect against FAT32/exFAT reserved device names
        val baseName = cleaned.substringBefore('.').uppercase()
        if (baseName in RESERVED_NAMES) {
            cleaned = "file_$cleaned"
        }

        return cleaned
    }

    /**
     * Extracts and sanitizes filename from a Content-Disposition HTTP header.
     * Example: 'attachment; filename="vacation.mp4"' -> 'vacation.mp4'
     */
    fun extractFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val regex = Regex("""filename\*?=(?:UTF-8''|")?([^";]+)"?""", RegexOption.IGNORE_CASE)
        val match = regex.find(header) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim() ?: return null
        return sanitize(raw)
    }
}
