package com.tera.down

import com.tera.down.feature.downloader.DownloadNotificationActionReceiver
import com.tera.down.feature.downloader.DownloadNotificationManagerImpl
import com.tera.down.feature.downloader.DownloadProgressListener
import com.tera.down.feature.downloader.DownloadQueueCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationAndActionTest {

    private class MockCoordinator : DownloadQueueCoordinator {
        var pausedTaskId: String? = null
        var cancelledTaskId: String? = null
        var startedTaskId: String? = null

        override fun isDownloading(taskId: String): Boolean = false
        override fun startDownload(taskId: String): Boolean {
            startedTaskId = taskId
            return true
        }
        override fun pauseDownload(taskId: String): Boolean {
            pausedTaskId = taskId
            return true
        }
        override fun cancelDownload(taskId: String): Boolean {
            cancelledTaskId = taskId
            return true
        }
        override fun retryDownload(taskId: String): Boolean = true
        override fun getActiveTaskCount(): Int = 0
        override fun addProgressListener(listener: DownloadProgressListener) {}
        override fun removeProgressListener(listener: DownloadProgressListener) {}
    }

    @Test
    fun handleAction_routesPauseActionCorrectly() {
        val coordinator = MockCoordinator()
        val handled = DownloadNotificationActionReceiver.handleAction(
            coordinator = coordinator,
            action = DownloadNotificationManagerImpl.ACTION_PAUSE,
            taskId = "task-pause-1"
        )
        assertTrue(handled)
        assertEquals("task-pause-1", coordinator.pausedTaskId)
    }

    @Test
    fun handleAction_routesCancelActionCorrectly() {
        val coordinator = MockCoordinator()
        val handled = DownloadNotificationActionReceiver.handleAction(
            coordinator = coordinator,
            action = DownloadNotificationManagerImpl.ACTION_CANCEL,
            taskId = "task-cancel-2"
        )
        assertTrue(handled)
        assertEquals("task-cancel-2", coordinator.cancelledTaskId)
    }

    @Test
    fun handleAction_routesResumeActionCorrectly() {
        val coordinator = MockCoordinator()
        val handled = DownloadNotificationActionReceiver.handleAction(
            coordinator = coordinator,
            action = DownloadNotificationManagerImpl.ACTION_RESUME,
            taskId = "task-resume-3"
        )
        assertTrue(handled)
        assertEquals("task-resume-3", coordinator.startedTaskId)
    }

    @Test
    fun handleAction_rejectsInvalidOrBlankParameters() {
        val coordinator = MockCoordinator()

        // Null taskId
        assertFalse(
            DownloadNotificationActionReceiver.handleAction(
                coordinator = coordinator,
                action = DownloadNotificationManagerImpl.ACTION_PAUSE,
                taskId = null
            )
        )

        // Blank taskId
        assertFalse(
            DownloadNotificationActionReceiver.handleAction(
                coordinator = coordinator,
                action = DownloadNotificationManagerImpl.ACTION_PAUSE,
                taskId = "   "
            )
        )

        // Unknown action
        assertFalse(
            DownloadNotificationActionReceiver.handleAction(
                coordinator = coordinator,
                action = "com.unknown.ACTION",
                taskId = "task-1"
            )
        )

        // Null action
        assertFalse(
            DownloadNotificationActionReceiver.handleAction(
                coordinator = coordinator,
                action = null,
                taskId = "task-1"
            )
        )
    }

    @Test
    fun unknownSize_progressReportedAsIndeterminateWithoutInventedPercentage() {
        // When total size is unknown (<= 0), progress must be indeterminate
        assertTrue(DownloadNotificationManagerImpl.isIndeterminateProgress(0L))
        assertTrue(DownloadNotificationManagerImpl.isIndeterminateProgress(-1L))

        val textUnknown = DownloadNotificationManagerImpl.formatProgressContentText(
            downloadedBytes = 1536000L, // 1.5 MB
            totalBytes = -1L,
            speedBytesPerSec = 512000L // 500 KB/s
        )

        // Must NOT contain invented percentage
        assertFalse(textUnknown.contains("%"))
        // Must contain truthful downloaded bytes and speed
        assertTrue(textUnknown.contains("1.5 MB"))
        assertTrue(textUnknown.contains("500.0 KB/s"))
    }

    @Test
    fun knownSize_progressReportedWithTruthfulPercentage() {
        assertFalse(DownloadNotificationManagerImpl.isIndeterminateProgress(10000000L))

        val textKnown = DownloadNotificationManagerImpl.formatProgressContentText(
            downloadedBytes = 5000000L,
            totalBytes = 10000000L,
            speedBytesPerSec = 1048576L
        )

        assertTrue(textKnown.contains("50%"))
        assertTrue(textKnown.contains("4.8 MB / 9.5 MB (50%)"))
        assertTrue(textKnown.contains("1.0 MB/s"))
    }
}
