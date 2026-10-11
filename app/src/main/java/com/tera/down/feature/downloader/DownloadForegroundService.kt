package com.tera.down.feature.downloader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tera.down.TeradownApplication
import java.io.File

/**
 * Android Foreground Service maintaining active network downloads and displaying
 * an ongoing notification with progress, speed, and pause/cancel controls.
 *
 * Utilizes `foregroundServiceType="dataSync"` complying with Android 14+ requirements.
 * Automatically stops itself when no downloads remain active.
 */
class DownloadForegroundService : Service(), DownloadProgressListener {

    companion object {
        fun startDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadNotificationManagerImpl.ACTION_START
                putExtra(DownloadNotificationManagerImpl.EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var notificationManager: DownloadNotificationManager? = null
    private var coordinator: DownloadQueueCoordinator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? TeradownApplication
        val container = app?.container

        notificationManager = container?.downloadNotificationManager ?: DownloadNotificationManagerImpl(this)
        coordinator = container?.downloadQueueCoordinator

        coordinator?.addProgressListener(this)
        notificationManager?.createNotificationChannels()

        val initialNotification = notificationManager?.buildOngoingNotification(
            taskId = "",
            taskName = "Teradown Engine",
            downloadedBytes = 0L,
            totalBytes = 0L,
            speedBytesPerSec = 0L
        ) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotificationManagerImpl.FOREGROUND_NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                DownloadNotificationManagerImpl.FOREGROUND_NOTIFICATION_ID,
                initialNotification
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(DownloadNotificationManagerImpl.EXTRA_TASK_ID)
        val action = intent?.action

        if (!taskId.isNullOrBlank() && coordinator != null) {
            when (action) {
                DownloadNotificationManagerImpl.ACTION_START -> {
                    coordinator?.startDownload(taskId)
                }
                DownloadNotificationManagerImpl.ACTION_PAUSE -> {
                    coordinator?.pauseDownload(taskId)
                }
                DownloadNotificationManagerImpl.ACTION_CANCEL -> {
                    coordinator?.cancelDownload(taskId)
                }
                DownloadNotificationManagerImpl.ACTION_RESUME -> {
                    coordinator?.startDownload(taskId)
                }
            }
        }

        checkAndStopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDownloadStarted(taskId: String, taskName: String) {
        notificationManager?.updateOngoingNotification(
            taskId = taskId,
            taskName = taskName,
            downloadedBytes = 0L,
            totalBytes = 0L,
            speedBytesPerSec = 0L
        )
    }

    override fun onDownloadProgress(
        taskId: String,
        taskName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        notificationManager?.updateOngoingNotification(
            taskId = taskId,
            taskName = taskName,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speedBytesPerSec
        )
    }

    override fun onDownloadCompleted(taskId: String, taskName: String, file: File) {
        notificationManager?.showCompletionNotification(taskId, taskName)
        checkAndStopIfIdle()
    }

    override fun onDownloadFailed(
        taskId: String,
        taskName: String,
        errorMessage: String,
        isPermanent: Boolean
    ) {
        notificationManager?.showFailureNotification(taskId, taskName, errorMessage)
        checkAndStopIfIdle()
    }

    override fun onDownloadCancelled(taskId: String, taskName: String) {
        checkAndStopIfIdle()
    }

    private fun checkAndStopIfIdle() {
        val activeCount = coordinator?.getActiveTaskCount() ?: 0
        if (activeCount == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        coordinator?.removeProgressListener(this)
        super.onDestroy()
    }
}
