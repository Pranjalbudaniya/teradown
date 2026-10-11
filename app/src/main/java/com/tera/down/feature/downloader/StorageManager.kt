package com.tera.down.feature.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * Result of validating a download destination directory.
 */
sealed class StorageValidationResult {
    data class Valid(val directory: File, val availableBytes: Long) : StorageValidationResult()
    data class Invalid(val reason: String) : StorageValidationResult()
}

/**
 * Manages storage for downloads and temporary staging files.
 *
 * Defaults to the standard public Downloads/Teradown folder (/storage/emulated/0/Download/Teradown)
 * so downloaded media is easily discoverable by user file managers, gallery, and video players.
 * Supports direct file operations as well as MediaStore fallback for Android Q+ Scoped Storage.
 */
class StorageManager(
    private val context: Context? = null,
    baseDir: File? = null
) {

    val downloadDirectory: File = baseDir ?: run {
        getDefaultDownloadDirectory()
    }

    val tempDirectory: File = run {
        if (baseDir != null) {
            File(baseDir, ".temp")
        } else {
            // For app runtime, store staging temp chunks in app-specific storage so downloading never fails
            val appSpecific = context?.getExternalFilesDir(null) ?: context?.filesDir
            if (appSpecific != null) {
                File(appSpecific, ".temp")
            } else {
                File(downloadDirectory, ".temp")
            }
        }
    }.apply {
        if (!exists()) mkdirs()
    }

    init {
        if (!downloadDirectory.exists()) {
            try {
                downloadDirectory.mkdirs()
            } catch (_: Exception) {}
        }
    }

    /**
     * Resolves the default download directory.
     * Uses the public standard Downloads folder with a "Teradown" subfolder (/storage/emulated/0/Download/Teradown).
     * Falls back to app-specific external files dir if public storage is unavailable or not accessible.
     */
    fun getDefaultDownloadDirectory(): File {
        return try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(publicDownloads, "Teradown").apply {
                if (!exists()) {
                    mkdirs()
                }
            }
        } catch (_: Exception) {
            val ctx = context
            if (ctx != null) {
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(ctx.filesDir, "downloads").apply { mkdirs() }
            } else {
                File(System.getProperty("java.io.tmpdir", "/tmp"), "Teradown").apply { mkdirs() }
            }
        }
    }

    /**
     * Resolves the effective download directory, falling back to default if custom is invalid or blank.
     */
    fun getEffectiveDownloadDirectory(customPath: String? = null): File {
        if (customPath.isNullOrBlank()) {
            return downloadDirectory
        }
        val customFile = File(customPath)
        return if (validateDirectory(customFile) is StorageValidationResult.Valid) {
            customFile
        } else {
            downloadDirectory
        }
    }

    /**
     * Validates whether a given directory exists, is writable, and has usable storage space.
     */
    fun validateDirectory(directory: File): StorageValidationResult {
        val isPublicDownloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (directory.absolutePath.contains("/Download") || directory.absolutePath.contains("/Downloads"))

        if (!directory.exists()) {
            val created = try {
                directory.mkdirs()
            } catch (_: SecurityException) {
                false
            }
            if (!created && !directory.exists() && !isPublicDownloads) {
                return StorageValidationResult.Invalid("Directory '${directory.path}' does not exist and cannot be created.")
            }
        }

        if (directory.exists() && !directory.isDirectory) {
            return StorageValidationResult.Invalid("Path '${directory.path}' is not a directory.")
        }

        if (!directory.canWrite() && !isPublicDownloads) {
            return StorageValidationResult.Invalid("Directory '${directory.path}' lacks write permission.")
        }

        val usableSpace = if (directory.usableSpace > 0L) {
            directory.usableSpace
        } else {
            try {
                Environment.getExternalStorageDirectory()?.usableSpace ?: 0L
            } catch (_: Exception) {
                0L
            }
        }

        if (usableSpace <= 0L && !isPublicDownloads) {
            return StorageValidationResult.Invalid("Storage volume for '${directory.path}' is exhausted or unavailable.")
        }

        return StorageValidationResult.Valid(
            directory,
            if (usableSpace > 0L) usableSpace else 1024L * 1024L * 1024L
        )
    }

    /**
     * Validates a destination path string.
     */
    fun validateDestinationPath(pathString: String?): StorageValidationResult {
        if (pathString.isNullOrBlank()) {
            return validateDirectory(downloadDirectory)
        }
        return validateDirectory(File(pathString))
    }

    /**
     * Resolves the final destination file path for a sanitized filename.
     */
    fun getDestinationFile(sanitizedFilename: String, customDir: File? = null): File {
        val targetDir = customDir ?: downloadDirectory
        return File(targetDir, sanitizedFilename)
    }

    /**
     * Resolves the temporary staging file for a given task ID.
     */
    fun getTempFile(taskId: String): File {
        return File(tempDirectory, "task_${taskId}.tmp")
    }

    /**
     * Atomically moves/finalizes the completed temporary download file to the target destination.
     * If direct file operations encounter Scoped Storage restrictions, safely routes via MediaStore.
     */
    fun finalizeDownload(tempFile: File, destinationFile: File): File {
        if (!tempFile.exists()) {
            throw IOException("Temporary file '${tempFile.name}' does not exist for finalization.")
        }

        val target = if (destinationFile.exists()) {
            resolveUniqueDestination(destinationFile)
        } else {
            destinationFile
        }

        try {
            target.parentFile?.mkdirs()
        } catch (_: Exception) {}

        var finalizedDirectly = false
        try {
            finalizedDirectly = tempFile.renameTo(target)
        } catch (_: Exception) {}

        if (!finalizedDirectly) {
            // Fallback for cross-filesystem / volume moves or permission constraints
            try {
                tempFile.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
                finalizedDirectly = true
            } catch (e: Exception) {
                // If direct copy threw exception (e.g. Android 10+ Scoped Storage restriction without All Files permission)
                if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(context, tempFile, target)
                } else {
                    if (target.exists()) {
                        target.delete()
                    }
                    throw e
                }
            }
        }

        // Notify MediaScanner so Gallery, Files app, and media players immediately recognize the completed file in Downloads/Teradown
        context?.let { ctx ->
            try {
                MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), null, null)
            } catch (_: Exception) {}
        }

        return target
    }

    /**
     * Saves file into the public Downloads collection via MediaStore (Android 10+ / API 29+).
     */
    private fun saveViaMediaStore(ctx: Context, tempFile: File, target: File) {
        val resolver = ctx.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Teradown/"
        val mimeType = getMimeType(target.name)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create MediaStore entry for ${target.name}")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open output stream for MediaStore URI")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            tempFile.delete()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun getMimeType(filename: String): String {
        val ext = File(filename).extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp4", "mkv", "webm", "avi", "mov" -> "video/*"
                "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
                "mp3", "m4a", "flac", "wav", "aac" -> "audio/*"
                "pdf" -> "application/pdf"
                "zip", "rar", "7z" -> "application/zip"
                else -> "application/octet-stream"
            }
    }

    /**
     * Deletes any partial temporary file associated with [taskId].
     */
    fun cleanupTempFile(taskId: String): Boolean {
        val temp = getTempFile(taskId)
        return if (temp.exists()) temp.delete() else true
    }

    /**
     * Queries available storage space in bytes.
     */
    fun getAvailableStorageBytes(): Long {
        return downloadDirectory.usableSpace
    }

    private fun resolveUniqueDestination(file: File): File {
        val parent = file.parentFile ?: downloadDirectory
        val name = file.nameWithoutExtension
        val ext = if (file.extension.isNotEmpty()) ".${file.extension}" else ""
        var index = 1
        var candidate: File
        do {
            candidate = File(parent, "${name}_($index)$ext")
            index++
        } while (candidate.exists())
        return candidate
    }
}
