package com.tera.down.feature.queue

import com.tera.down.core.model.DownloadStatus

/**
 * State machine validator governing allowed transitions and progress invariants
 * within the Teradown download queue.
 *
 * NOTE: These govern queue lifecycle states only. Real network downloads and transfer
 * execution are implemented in subsequent phases.
 */
object DownloadQueueStateValidator {

    /**
     * Determines whether a transition from [current] status to [target] status is permitted.
     */
    fun canTransition(current: DownloadStatus, target: DownloadStatus): Boolean {
        if (current == target) return true

        return when (current) {
            DownloadStatus.QUEUED -> target in setOf(
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED
            )

            DownloadStatus.DOWNLOADING -> target in setOf(
                DownloadStatus.PAUSED,
                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED
            )

            DownloadStatus.PAUSED -> target in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.CANCELLED
            )

            DownloadStatus.FAILED -> target in setOf(
                DownloadStatus.QUEUED,      // Retried
                DownloadStatus.CANCELLED
            )

            DownloadStatus.CANCELLED -> target in setOf(
                DownloadStatus.QUEUED       // Re-queued
            )

            DownloadStatus.COMPLETED -> false // Terminal state for download task
        }
    }

    /**
     * Validates and constrains downloaded byte count within [0, totalSize].
     */
    fun coerceDownloadedBytes(downloadedBytes: Long, totalSize: Long): Long {
        if (downloadedBytes <= 0L) return 0L
        if (totalSize <= 0L) return downloadedBytes
        return downloadedBytes.coerceIn(0L, totalSize)
    }

    /**
     * Computes the normalized progress ratio in range [0.0, 1.0].
     */
    fun computeProgress(downloadedBytes: Long, totalSize: Long): Float {
        if (totalSize <= 0L || downloadedBytes <= 0L) return 0f
        return (downloadedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
    }
}
