package com.tera.down.ui.components

import java.util.Locale

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return if (digitGroups == 0) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[digitGroups])
        }
    }

    fun formatFileSize(bytes: Long): String {
        return if (bytes < 0L) "Unknown size" else formatBytes(bytes)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 B/s"
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatDuration(ms: Long?): String {
        if (ms == null || ms <= 0L) return "--:--"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val hr = min / 60
        return if (hr > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hr, min % 60, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }
}
