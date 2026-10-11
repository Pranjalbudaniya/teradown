package com.tera.down.feature.downloader

import com.tera.down.core.database.dao.DownloadTaskDao
import com.tera.down.core.model.DownloadStatus
import com.tera.down.feature.manifest.ManifestReporter
import com.tera.down.feature.queue.DownloadQueueRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Action taken by [DownloadRecoveryManager] for a specific queue task.
 */
enum class RecoveryAction {
    NO_ACTION_NEEDED,
    SKIPPED_ACTIVE_TRANSFER,
    RECOVERED_TO_PAUSED_WITH_PARTIAL_DATA,
    RECOVERED_TO_PAUSED_NO_PARTIAL_DATA,
    VERIFIED_COMPLETED_FROM_FINAL_FILE
}

/**
 * Diagnostic record explaining reconciliation decisions taken for an individual task.
 */
data class RecoveryReport(
    val taskId: String,
    val taskName: String,
    val previousStatus: DownloadStatus,
    val newStatus: DownloadStatus,
    val actionTaken: RecoveryAction,
    val reason: String,
    val partialBytesRecovered: Long = 0L
)

/**
 * Manages conservative startup recovery by reconciling persistent tasks, active worker state,
 * temporary files, destination files, and manifest records.
 */
interface DownloadRecoveryManager {

    /**
     * Reconciles queue records against disk state and active jobs. Safe to run multiple times.
     */
    suspend fun reconcileAndRecover(): List<RecoveryReport>
}

class DownloadRecoveryManagerImpl(
    private val downloadTaskDao: DownloadTaskDao,
    private val queueRepository: DownloadQueueRepository,
    private val storageManager: StorageManager,
    private val coordinator: DownloadQueueCoordinator? = null,
    private val manifestReporter: ManifestReporter? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DownloadRecoveryManager {

    override suspend fun reconcileAndRecover(): List<RecoveryReport> = withContext(ioDispatcher) {
        val reports = mutableListOf<RecoveryReport>()
        val tasks = downloadTaskDao.getAllTasks()

        for (task in tasks) {
            // 1. Never touch a task that is actively running in memory
            if (coordinator?.isDownloading(task.id) == true) {
                reports.add(
                    RecoveryReport(
                        taskId = task.id,
                        taskName = task.name,
                        previousStatus = task.status,
                        newStatus = task.status,
                        actionTaken = RecoveryAction.SKIPPED_ACTIVE_TRANSFER,
                        reason = "Task is actively downloading in memory."
                    )
                )
                continue
            }

            // 2. Reconcile tasks that were left in DOWNLOADING (e.g. process death, crash, reboot)
            if (task.status == DownloadStatus.DOWNLOADING) {
                // Check if destination file exists and is verified complete
                val sanitizedName = FilenameSanitizer.sanitize(task.name)
                val destFile = task.destinationPath?.let { File(it) } ?: storageManager.getDestinationFile(sanitizedName)

                if (destFile.exists() && task.size > 0L && destFile.length() == task.size) {
                    // Verified: file is completely finalized
                    queueRepository.completeTask(task.id, destFile.absolutePath)
                    manifestReporter?.recordCompleted(
                        taskId = task.id,
                        destinationPath = destFile.absolutePath,
                        bytesReceived = task.size,
                        resumed = false
                    )
                    reports.add(
                        RecoveryReport(
                            taskId = task.id,
                            taskName = task.name,
                            previousStatus = DownloadStatus.DOWNLOADING,
                            newStatus = DownloadStatus.COMPLETED,
                            actionTaken = RecoveryAction.VERIFIED_COMPLETED_FROM_FINAL_FILE,
                            reason = "Destination file exists and verified to match expected size (${task.size} bytes)."
                        )
                    )
                    continue
                }

                // Check temporary file for partial data
                val tempFile = storageManager.getTempFile(task.id)
                if (tempFile.exists() && tempFile.length() > 0L) {
                    val partialBytes = tempFile.length()
                    val safeBytes = if (task.size > 0L) minOf(partialBytes, task.size) else partialBytes

                    // Move to PAUSED so user can tap Resume
                    downloadTaskDao.updateTask(
                        task.copy(
                            status = DownloadStatus.PAUSED,
                            downloadedBytes = safeBytes,
                            speedBytesPerSec = 0L,
                            errorMessage = "Recovered after process restart. Tap Resume to continue.",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    manifestReporter?.recordInterrupted(task.id, safeBytes)

                    reports.add(
                        RecoveryReport(
                            taskId = task.id,
                            taskName = task.name,
                            previousStatus = DownloadStatus.DOWNLOADING,
                            newStatus = DownloadStatus.PAUSED,
                            actionTaken = RecoveryAction.RECOVERED_TO_PAUSED_WITH_PARTIAL_DATA,
                            reason = "Recovered partial staging file ($safeBytes bytes). Task transitioned to PAUSED.",
                            partialBytesRecovered = safeBytes
                        )
                    )
                } else {
                    // No partial file found; reset cleanly to PAUSED
                    downloadTaskDao.updateTask(
                        task.copy(
                            status = DownloadStatus.PAUSED,
                            downloadedBytes = 0L,
                            speedBytesPerSec = 0L,
                            errorMessage = "Download interrupted by process restart.",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    manifestReporter?.recordInterrupted(task.id, 0L)

                    reports.add(
                        RecoveryReport(
                            taskId = task.id,
                            taskName = task.name,
                            previousStatus = DownloadStatus.DOWNLOADING,
                            newStatus = DownloadStatus.PAUSED,
                            actionTaken = RecoveryAction.RECOVERED_TO_PAUSED_NO_PARTIAL_DATA,
                            reason = "No partial file found. Transitioned to PAUSED."
                        )
                    )
                }
            } else {
                // Task is already in QUEUED, PAUSED, COMPLETED, FAILED, or CANCELLED
                reports.add(
                    RecoveryReport(
                        taskId = task.id,
                        taskName = task.name,
                        previousStatus = task.status,
                        newStatus = task.status,
                        actionTaken = RecoveryAction.NO_ACTION_NEEDED,
                        reason = "Task is already in a stable state (${task.status.name})."
                    )
                )
            }
        }

        // Conservative policy: never delete unknown files in storage
        reports
    }
}
