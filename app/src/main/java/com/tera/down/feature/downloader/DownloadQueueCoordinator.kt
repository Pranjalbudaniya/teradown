package com.tera.down.feature.downloader

import android.content.Context
import com.tera.down.core.manifest.ManifestErrorCategory
import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.DownloadStatus
import com.tera.down.core.network.NetworkConnectivityMonitor
import com.tera.down.feature.manifest.ManifestReporter
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates download execution by connecting the high-level [DownloadQueueRepository]
 * with the low-level [DownloadEngine], [StorageManager], and user [SettingsRepository].
 *
 * Responsibilities:
 * - Manages concurrent download worker jobs within user concurrency limits.
 * - Enforces network policies (e.g. Wi-Fi only downloads).
 * - Validates storage destination health and accessibility before transfer.
 * - Prevents duplicate active jobs for the same task ID.
 * - Bridges download progress and status transitions to Room persistence.
 * - Reports accurate lifecycle outcomes to the manifest.
 * - Automatically advances queued tasks as capacity becomes available.
 */
interface DownloadQueueCoordinator {
    fun startDownload(taskId: String): Boolean
    fun pauseDownload(taskId: String): Boolean
    fun cancelDownload(taskId: String): Boolean
    fun retryDownload(taskId: String): Boolean
    fun isDownloading(taskId: String): Boolean
    fun getActiveTaskCount(): Int
    fun addProgressListener(listener: DownloadProgressListener)
    fun removeProgressListener(listener: DownloadProgressListener)
}

class DownloadQueueCoordinatorImpl(
    private val queueRepository: DownloadQueueRepository,
    private val downloadEngine: DownloadEngine,
    private val storageManager: StorageManager,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val manifestReporter: ManifestReporter? = null,
    private val context: Context? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val networkConnectivityMonitor: NetworkConnectivityMonitor? = null
) : DownloadQueueCoordinator {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val progressListeners = java.util.concurrent.CopyOnWriteArraySet<DownloadProgressListener>()
    @Volatile
    private var currentSettings = AppSettings()

    init {
        settingsRepository?.let { repo ->
            scope.launch(ioDispatcher) {
                repo.settingsFlow.collect { settings ->
                    val previousWifiOnly = currentSettings.wifiOnly
                    currentSettings = settings

                    if (!previousWifiOnly && settings.wifiOnly && networkConnectivityMonitor != null && !networkConnectivityMonitor.isWifi()) {
                        pauseActiveDownloadsForWifiLoss()
                    }
                }
            }
        }

        if (networkConnectivityMonitor != null) {
            scope.launch(ioDispatcher) {
                networkConnectivityMonitor.isWifiFlow.collect { isWifi ->
                    if (currentSettings.wifiOnly && !isWifi) {
                        pauseActiveDownloadsForWifiLoss()
                    }
                }
            }
        }
    }

    override fun getActiveTaskCount(): Int = activeJobs.size

    override fun addProgressListener(listener: DownloadProgressListener) {
        progressListeners.add(listener)
    }

    override fun removeProgressListener(listener: DownloadProgressListener) {
        progressListeners.remove(listener)
    }

    override fun isDownloading(taskId: String): Boolean {
        return activeJobs[taskId]?.isActive == true
    }

    override fun startDownload(taskId: String): Boolean {
        synchronized(activeJobs) {
            // Prevent launching duplicate workers for the same task ID
            if (activeJobs[taskId]?.isActive == true) {
                return false
            }

            // Enforce concurrency limit
            if (activeJobs.size >= currentSettings.maxConcurrentDownloads) {
                return false
            }
        }

        // Enforce Wi-Fi only network constraint
        if (currentSettings.wifiOnly && networkConnectivityMonitor != null && !networkConnectivityMonitor.isWifi()) {
            scope.launch(ioDispatcher) {
                queueRepository.updateTaskStatus(taskId, DownloadStatus.PAUSED, "Waiting for Wi-Fi (Wi-Fi only enabled)")
            }
            return false
        }

        context?.let { ctx ->
            DownloadForegroundService.startDownload(ctx, taskId)
        }

        val job = scope.launch(ioDispatcher) {
            val taskEntity = queueRepository.getTaskEntityById(taskId) ?: return@launch

            // Validate that task is eligible to run
            if (taskEntity.status != DownloadStatus.QUEUED && taskEntity.status != DownloadStatus.PAUSED) {
                return@launch
            }

            // Validate destination storage directory
            val effectiveDestDir = storageManager.getEffectiveDownloadDirectory(currentSettings.downloadDirectory)
            val validation = storageManager.validateDirectory(effectiveDestDir)
            if (validation is StorageValidationResult.Invalid) {
                queueRepository.updateTaskStatus(taskId, DownloadStatus.FAILED, validation.reason)
                manifestReporter?.recordFailed(
                    taskId = taskId,
                    errorCategory = ManifestErrorCategory.FILE_IO_ERROR,
                    errorMessage = validation.reason
                )
                progressListeners.forEach {
                    it.onDownloadFailed(taskId, taskEntity.name, validation.reason, isPermanent = true)
                }
                return@launch
            }

            // Ensure task is recorded in manifest and report download started
            manifestReporter?.recordEnqueued(
                taskId = taskEntity.id,
                originalUrl = taskEntity.originalUrl,
                fileName = taskEntity.name,
                fileSize = taskEntity.size,
                mediaType = taskEntity.mediaType
            )
            manifestReporter?.recordStarted(taskId)
            progressListeners.forEach { it.onDownloadStarted(taskId, taskEntity.name) }

            queueRepository.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)

            val sanitizedFilename = FilenameSanitizer.sanitize(taskEntity.name)
            val destFile = storageManager.getDestinationFile(sanitizedFilename, effectiveDestDir)
            val tempFile = storageManager.getTempFile(taskId)

            val request = DownloadRequest(
                taskId = taskId,
                url = taskEntity.originalUrl,
                destinationFile = destFile,
                tempFile = tempFile,
                expectedSize = taskEntity.size,
                allowResume = true
            )

            var lastReportedBytes = 0L
            val result = downloadEngine.download(request) { bytesDownloaded, totalBytes, speedBytesPerSec ->
                scope.launch(ioDispatcher) {
                    queueRepository.updateProgress(taskId, bytesDownloaded, speedBytesPerSec)
                    progressListeners.forEach {
                        it.onDownloadProgress(taskId, taskEntity.name, bytesDownloaded, totalBytes, speedBytesPerSec)
                    }
                    if (bytesDownloaded - lastReportedBytes >= 256 * 1024 || bytesDownloaded == totalBytes) {
                        manifestReporter?.recordProgress(taskId, bytesDownloaded)
                        lastReportedBytes = bytesDownloaded
                    }
                }
            }

            when (result) {
                is DownloadResult.Success -> {
                    val completed = queueRepository.completeTask(taskId, result.file.absolutePath)
                    if (completed) {
                        manifestReporter?.recordCompleted(
                            taskId = taskId,
                            destinationPath = result.file.absolutePath,
                            bytesReceived = result.totalBytes,
                            resumed = result.resumed
                        )
                        progressListeners.forEach { it.onDownloadCompleted(taskId, taskEntity.name, result.file) }
                    } else {
                        // The task transitioned to CANCELLED, PAUSED, or FAILED while download was running.
                        val currentTask = queueRepository.getTaskById(taskId)
                        if (currentTask?.status == DownloadStatus.CANCELLED) {
                            storageManager.cleanupTempFile(taskId)
                            if (result.file.exists()) {
                                result.file.delete()
                            }
                            progressListeners.forEach { it.onDownloadCancelled(taskId, taskEntity.name) }
                        }
                    }
                }

                is DownloadResult.Cancelled -> {
                    queueRepository.updateTaskStatus(taskId, DownloadStatus.CANCELLED)
                    manifestReporter?.recordCancelled(taskId)
                    storageManager.cleanupTempFile(taskId)
                    progressListeners.forEach { it.onDownloadCancelled(taskId, taskEntity.name) }
                }

                is DownloadResult.Error -> {
                    val isPerm = DownloadRetryPolicy.isPermanentError(result.type, result.httpStatusCode)
                    val manifestCategory = when (result.type) {
                        DownloadErrorType.INVALID_URL, DownloadErrorType.UNSUPPORTED_SCHEME ->
                            ManifestErrorCategory.INVALID_URL
                        DownloadErrorType.NETWORK_FAILURE ->
                            ManifestErrorCategory.NETWORK_ERROR
                        DownloadErrorType.HTTP_ERROR ->
                            ManifestErrorCategory.HTTP_ERROR
                        DownloadErrorType.INSUFFICIENT_SPACE ->
                            ManifestErrorCategory.INSUFFICIENT_STORAGE
                        DownloadErrorType.FILE_IO_ERROR ->
                            ManifestErrorCategory.FILE_IO_ERROR
                        DownloadErrorType.SERVER_ERROR ->
                            ManifestErrorCategory.SERVER_ERROR
                        DownloadErrorType.UNKNOWN ->
                            ManifestErrorCategory.UNKNOWN
                    }
                    queueRepository.updateTaskStatus(taskId, DownloadStatus.FAILED, result.message)
                    manifestReporter?.recordFailed(
                        taskId = taskId,
                        errorCategory = manifestCategory,
                        errorMessage = result.message
                    )
                    progressListeners.forEach {
                        it.onDownloadFailed(taskId, taskEntity.name, result.message, isPerm)
                    }
                    if (!result.isResumable) {
                        storageManager.cleanupTempFile(taskId)
                    }
                }
            }
        }

        activeJobs[taskId] = job
        job.invokeOnCompletion {
            activeJobs.remove(taskId)
            triggerNextQueuedTasks()
        }

        return true
    }

    override fun pauseDownload(taskId: String): Boolean {
        val job = activeJobs.remove(taskId)
        job?.cancel()

        scope.launch(ioDispatcher) {
            val task = queueRepository.getTaskById(taskId)
            queueRepository.pauseTask(taskId)
            manifestReporter?.recordInterrupted(taskId)
            progressListeners.forEach { it.onDownloadCancelled(taskId, task?.name ?: taskId) }
            triggerNextQueuedTasks()
        }
        return true
    }

    override fun cancelDownload(taskId: String): Boolean {
        val job = activeJobs.remove(taskId)
        job?.cancel()

        storageManager.cleanupTempFile(taskId)

        scope.launch(ioDispatcher) {
            val task = queueRepository.getTaskById(taskId)
            queueRepository.cancelTask(taskId)
            manifestReporter?.recordCancelled(taskId)
            progressListeners.forEach { it.onDownloadCancelled(taskId, task?.name ?: taskId) }
            triggerNextQueuedTasks()
        }
        return true
    }

    override fun retryDownload(taskId: String): Boolean {
        val job = activeJobs.remove(taskId)
        job?.cancel()

        storageManager.cleanupTempFile(taskId)

        scope.launch(ioDispatcher) {
            manifestReporter?.recordRetrying(taskId)
            queueRepository.retryTask(taskId)
            startDownload(taskId)
        }
        return true
    }

    private fun triggerNextQueuedTasks() {
        scope.launch(ioDispatcher) {
            if (currentSettings.wifiOnly && networkConnectivityMonitor != null && !networkConnectivityMonitor.isWifi()) {
                return@launch
            }
            val availableSlots = currentSettings.maxConcurrentDownloads - activeJobs.size
            if (availableSlots <= 0) return@launch

            val queued = queueRepository.getQueuedTaskEntities()
            for (task in queued.take(availableSlots)) {
                if (!isDownloading(task.id)) {
                    startDownload(task.id)
                }
            }
        }
    }

    private fun pauseActiveDownloadsForWifiLoss() {
        for ((taskId, job) in activeJobs) {
            job.cancel()
            activeJobs.remove(taskId)
            scope.launch(ioDispatcher) {
                queueRepository.updateTaskStatus(taskId, DownloadStatus.PAUSED, "Paused: Wi-Fi disconnected")
                manifestReporter?.recordInterrupted(taskId)
            }
        }
    }
}
