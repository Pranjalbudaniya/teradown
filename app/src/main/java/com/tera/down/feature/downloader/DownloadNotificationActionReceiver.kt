package com.tera.down.feature.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tera.down.TeradownApplication

/**
 * Handles actionable buttons from ongoing notifications (Pause, Cancel, Resume)
 * and dispatches them safely to [DownloadQueueCoordinator].
 */
class DownloadNotificationActionReceiver : BroadcastReceiver() {

    companion object {
        fun handleAction(
            coordinator: DownloadQueueCoordinator,
            action: String?,
            taskId: String?
        ): Boolean {
            if (taskId.isNullOrBlank() || action.isNullOrBlank()) return false
            return when (action) {
                DownloadNotificationManagerImpl.ACTION_PAUSE -> coordinator.pauseDownload(taskId)
                DownloadNotificationManagerImpl.ACTION_CANCEL -> coordinator.cancelDownload(taskId)
                DownloadNotificationManagerImpl.ACTION_RESUME -> coordinator.startDownload(taskId)
                else -> false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val taskId = intent.getStringExtra(DownloadNotificationManagerImpl.EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) return

        val app = context.applicationContext as? TeradownApplication ?: return
        val coordinator = app.container.downloadQueueCoordinator

        handleAction(coordinator, action, taskId)
    }
}
