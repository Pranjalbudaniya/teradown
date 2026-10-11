package com.tera.down.feature.downloader

import android.content.Context
import android.os.Environment
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
 * Manages app-specific file storage for downloads and temporary staging files.
 *
 * Adheres to modern scoped storage principles: uses app-specific storage directories
 * which do not require broad runtime permissions (e.g. READ_EXTERNAL_STORAGE),
 * while remaining reliable across all supported Android API levels.
 */
class StorageManager(
    private val context: Context? = null,
    baseDir: File? = null
) {

    val downloadDirectory: File = baseDir ?: run {
        getDefaultDownloadDirectory()
    }

    val tempDirectory: File = File(downloadDirectory, ".temp").apply {
        if (!exists()) mkdirs()
    }

    init {
        if (!downloadDirectory.exists()) {
            downloadDirectory.mkdirs()
        }
    }

    /**
     * Resolves the default app-specific download directory.
     */
    fun getDefaultDownloadDirectory(): File {
        val ctx = checkNotNull(context) { "Context must be provided when baseDir is not specified." }
        return ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.filesDir, "downloads").apply { mkdirs() }
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
        if (!directory.exists()) {
            val created = try {
                directory.mkdirs()
            } catch (_: SecurityException) {
                false
            }
            if (!created && !directory.exists()) {
                return StorageValidationResult.Invalid("Directory '${directory.path}' does not exist and cannot be created.")
            }
        }

        if (!directory.isDirectory) {
            return StorageValidationResult.Invalid("Path '${directory.path}' is not a directory.")
        }

        if (!directory.canWrite()) {
            return StorageValidationResult.Invalid("Directory '${directory.path}' lacks write permission.")
        }

        val usableSpace = directory.usableSpace
        if (usableSpace <= 0L) {
            return StorageValidationResult.Invalid("Storage volume for '${directory.path}' is exhausted or unavailable.")
        }

        return StorageValidationResult.Valid(directory, usableSpace)
    }

    /**
     * Validates a destination path string.
     */
    fun validateDestinationPath(pathString: String?): StorageValidationResult {
        return if (pathString.isNullOrBlank()) {
            validateDirectory(downloadDirectory)
        } else {
            validateDirectory(File(pathString))
        }
    }

    /**
     * Resolves the target destination file for the given sanitized name, optionally using a custom directory.
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
     * If the destination file already exists, appends an incrementing index to avoid data destruction.
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

        target.parentFile?.mkdirs()

        val renamed = tempFile.renameTo(target)
        if (!renamed) {
            // Fallback for cross-filesystem / volume moves
            try {
                tempFile.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
            } catch (e: Exception) {
                // Clean up partial target file on stream copy failure
                if (target.exists()) {
                    target.delete()
                }
                throw e
            }
        }

        return target
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
