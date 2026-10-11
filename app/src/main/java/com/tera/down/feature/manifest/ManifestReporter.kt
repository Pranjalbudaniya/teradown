package com.tera.down.feature.manifest

import com.tera.down.core.manifest.ManifestErrorCategory
import com.tera.down.core.model.MediaType

/**
 * Interface for reporting download lifecycle events into the persistent manifest.
 * Keeps reporting decoupled from UI and individual download worker internals.
 */
interface ManifestReporter {

    /**
     * Records a newly enqueued download task into the manifest.
     */
    suspend fun recordEnqueued(
        taskId: String,
        originalUrl: String,
        fileName: String,
        fileSize: Long,
        mediaType: MediaType
    )

    /**
     * Records that downloading has actively begun for a task.
     */
    suspend fun recordStarted(taskId: String)

    /**
     * Updates bytes received during transfer.
     */
    suspend fun recordProgress(taskId: String, bytesReceived: Long)

    /**
     * Records successful download completion and file finalization.
     */
    suspend fun recordCompleted(
        taskId: String,
        destinationPath: String,
        bytesReceived: Long,
        resumed: Boolean,
        checksumSha256: String? = null
    )

    /**
     * Records task failure with structured error categorization.
     */
    suspend fun recordFailed(
        taskId: String,
        errorCategory: ManifestErrorCategory,
        errorMessage: String,
        bytesReceived: Long = 0L
    )

    /**
     * Records user cancellation of a task.
     */
    suspend fun recordCancelled(taskId: String, bytesReceived: Long = 0L)

    /**
     * Records task interruption (e.g. pause or unexpected process termination).
     */
    suspend fun recordInterrupted(taskId: String, bytesReceived: Long = 0L)

    /**
     * Records that a previously failed or interrupted task is being retried.
     */
    suspend fun recordRetrying(taskId: String)

    /**
     * Detects tasks that were left in active state across app restarts and marks them as interrupted.
     */
    suspend fun reconcileInterruptedTasks()
}
