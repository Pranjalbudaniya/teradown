package com.tera.down.core.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tera.down.core.model.MediaType
import java.io.File

object LocalFileActions {

    fun getShareIntent(context: Context, file: File, mediaType: MediaType = MediaType.OTHER): Intent? {
        if (!file.exists()) return null
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val mime = getMimeType(mediaType, file)
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getViewIntent(context: Context, file: File, mediaType: MediaType = MediaType.OTHER): Intent? {
        if (!file.exists()) return null
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val mime = getMimeType(mediaType, file)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getMimeType(mediaType: MediaType, file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp4", "mkv", "webm", "mov", "avi" -> "video/*"
            "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
            "mp3", "m4a", "aac", "wav", "flac" -> "audio/*"
            "pdf" -> "application/pdf"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            else -> when (mediaType) {
                MediaType.VIDEO -> "video/*"
                MediaType.PHOTO -> "image/*"
                MediaType.OTHER -> "*/*"
            }
        }
    }
}
