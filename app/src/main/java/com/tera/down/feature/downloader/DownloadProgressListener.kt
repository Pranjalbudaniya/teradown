package com.tera.down.feature.downloader

import java.io.File

/**
 * Observer interface for download lifecycle and progress events emitted by [DownloadQueueCoordinator].
 * Enables foreground services, notification managers, and diagnostics to stay synchronized
 * without tight coupling.
 */
interface DownloadProgressListener {

    /**
     * Invoked when downloading has actively begun.
     */
    fun onDownloadStarted(taskId: String, taskName: String)

    /**
     * Invoked periodically with progress updates during streaming.
     */
    fun onDownloadProgress(
        taskId: String,
        taskName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    )

    /**
     * Invoked when a download successfully completes and is finalized to its destination file.
     */
    fun onDownloadCompleted(taskId: String, taskName: String, file: File)

    /**
     * Invoked when a download encounters a failure.
     */
    fun onDownloadFailed(
        taskId: String,
        taskName: String,
        errorMessage: String,
        isPermanent: Boolean
    )

    /**
     * Invoked when a download is cancelled.
     */
    fun onDownloadCancelled(taskId: String, taskName: String)
}
