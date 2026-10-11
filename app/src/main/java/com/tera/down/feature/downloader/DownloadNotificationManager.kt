package com.tera.down.feature.downloader

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tera.down.MainActivity
import com.tera.down.ui.components.Formatters
import java.util.concurrent.ConcurrentHashMap

interface DownloadNotificationManager {
    fun createNotificationChannels()
    fun buildOngoingNotification(
        taskId: String,
        taskName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ): Notification
    fun updateOngoingNotification(
        taskId: String,
        taskName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    )
    fun showCompletionNotification(taskId: String, taskName: String)
    fun showFailureNotification(taskId: String, taskName: String, errorMessage: String)
    fun cancelNotification(notificationId: Int)
}

class DownloadNotificationManagerImpl(
    private val context: Context
) : DownloadNotificationManager {

    companion object {
        const val CHANNEL_ONGOING = "teradown_downloads_ongoing"
        const val CHANNEL_COMPLETED = "teradown_downloads_completed"
        const val FOREGROUND_NOTIFICATION_ID = 1001

        const val ACTION_START = "com.tera.down.action.START"
        const val ACTION_PAUSE = "com.tera.down.action.PAUSE"
        const val ACTION_CANCEL = "com.tera.down.action.CANCEL"
        const val ACTION_RESUME = "com.tera.down.action.RESUME"
        const val EXTRA_TASK_ID = "extra_task_id"

        private const val NOTIFICATION_THROTTLE_MS = 500L

        fun isIndeterminateProgress(totalBytes: Long): Boolean = totalBytes <= 0L

        fun formatProgressContentText(
            downloadedBytes: Long,
            totalBytes: Long,
            speedBytesPerSec: Long
        ): String {
            return if (totalBytes > 0L) {
                val progressPercent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                "${Formatters.formatBytes(downloadedBytes)} / ${Formatters.formatBytes(totalBytes)} ($progressPercent%) • ${Formatters.formatSpeed(speedBytesPerSec)}"
            } else {
                "${Formatters.formatBytes(downloadedBytes)} • ${Formatters.formatSpeed(speedBytesPerSec)}"
            }
        }
    }

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val lastUpdateMap = ConcurrentHashMap<String, Long>()

    init {
        createNotificationChannels()
    }

    override fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ongoingChannel = NotificationChannel(
                CHANNEL_ONGOING,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for active file downloads"
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                "Finished Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads finish or fail"
                setShowBadge(true)
            }

            notificationManager?.createNotificationChannel(ongoingChannel)
            notificationManager?.createNotificationChannel(completedChannel)
        }
    }

    override fun buildOngoingNotification(
        taskId: String,
        taskName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ): Notification {
        val contentIntent = createContentIntent()
        val pauseIntent = createActionPendingIntent(ACTION_PAUSE, taskId, requestCode = 100)
        val cancelIntent = createActionPendingIntent(ACTION_CANCEL, taskId, requestCode = 200)

        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(taskName)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        val text = formatProgressContentText(downloadedBytes, totalBytes, speedBytesPerSec)
        if (isIndeterminateProgress(totalBytes)) {
            // Truthful unknown-size representation without fabricating a percentage
            builder.setProgress(0, 0, true)
            builder.setContentText(text)
        } else {
            val progressPercent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            builder.setProgress(100, progressPercent, false)
            builder.setContentText(text)
        }

        return builder.build()
    }

    override fun updateOngoingNotification(
        taskId: String,
        taskName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateMap[taskId] ?: 0L
        if (now - lastUpdate < NOTIFICATION_THROTTLE_MS && downloadedBytes != totalBytes) {
            return
        }
        lastUpdateMap[taskId] = now

        if (!hasNotificationPermission()) return

        val notification = buildOngoingNotification(taskId, taskName, downloadedBytes, totalBytes, speedBytesPerSec)
        try {
            notificationManager?.notify(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission denied on Android 13+
        }
    }

    override fun showCompletionNotification(taskId: String, taskName: String) {
        lastUpdateMap.remove(taskId)
        if (!hasNotificationPermission()) return

        val contentIntent = createContentIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(taskName)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationId = (FOREGROUND_NOTIFICATION_ID + 100 + taskId.hashCode().and(0x7FFF))
        try {
            notificationManager?.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Graceful degradation
        }
    }

    override fun showFailureNotification(taskId: String, taskName: String, errorMessage: String) {
        lastUpdateMap.remove(taskId)
        if (!hasNotificationPermission()) return

        val contentIntent = createContentIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("$taskName: $errorMessage")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationId = (FOREGROUND_NOTIFICATION_ID + 500 + taskId.hashCode().and(0x7FFF))
        try {
            notificationManager?.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Graceful degradation
        }
    }

    override fun cancelNotification(notificationId: Int) {
        notificationManager?.cancel(notificationId)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "downloads")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun createActionPendingIntent(action: String, taskId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode + taskId.hashCode().and(0xFF), intent, flags)
    }
}
