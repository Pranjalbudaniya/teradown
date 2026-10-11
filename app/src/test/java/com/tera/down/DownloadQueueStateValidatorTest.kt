package com.tera.down

import com.tera.down.core.model.DownloadStatus
import com.tera.down.feature.queue.DownloadQueueStateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueStateValidatorTest {

    @Test
    fun selfTransition_isAlwaysAllowed() {
        DownloadStatus.entries.forEach { status ->
            assertTrue(
                "Self transition for $status should be allowed",
                DownloadQueueStateValidator.canTransition(status, status)
            )
        }
    }

    @Test
    fun queued_allowedAndDisallowedTransitions() {
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.QUEUED, DownloadStatus.PAUSED))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.QUEUED, DownloadStatus.CANCELLED))

        // Direct transition from QUEUED to COMPLETED is invalid (must be downloaded first)
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.QUEUED, DownloadStatus.COMPLETED))
    }

    @Test
    fun downloading_allowedAndDisallowedTransitions() {
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.FAILED))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.CANCELLED))

        // DOWNLOADING to QUEUED directly is not a standard forward transition (use PAUSE or CANCEL)
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED))
    }

    @Test
    fun paused_allowedAndDisallowedTransitions() {
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.PAUSED, DownloadStatus.QUEUED))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.PAUSED, DownloadStatus.CANCELLED))

        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.PAUSED, DownloadStatus.COMPLETED))
    }

    @Test
    fun failed_allowedAndDisallowedTransitions() {
        // Retrying transitions to QUEUED
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.FAILED, DownloadStatus.QUEUED))
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.FAILED, DownloadStatus.CANCELLED))

        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.FAILED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.FAILED, DownloadStatus.COMPLETED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.FAILED, DownloadStatus.PAUSED))
    }

    @Test
    fun completed_isTerminal() {
        // COMPLETED cannot transition to anything else
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.QUEUED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.PAUSED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.FAILED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED))
    }

    @Test
    fun cancelled_canBeRequeued() {
        assertTrue(DownloadQueueStateValidator.canTransition(DownloadStatus.CANCELLED, DownloadStatus.QUEUED))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.CANCELLED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadQueueStateValidator.canTransition(DownloadStatus.CANCELLED, DownloadStatus.COMPLETED))
    }

    @Test
    fun coerceDownloadedBytes_boundsCorrectly() {
        val totalSize = 1000L

        // Negative values become 0
        assertEquals(0L, DownloadQueueStateValidator.coerceDownloadedBytes(-100L, totalSize))
        assertEquals(0L, DownloadQueueStateValidator.coerceDownloadedBytes(0L, totalSize))

        // Normal values remain unchanged
        assertEquals(500L, DownloadQueueStateValidator.coerceDownloadedBytes(500L, totalSize))
        assertEquals(1000L, DownloadQueueStateValidator.coerceDownloadedBytes(1000L, totalSize))

        // Values over total are capped
        assertEquals(1000L, DownloadQueueStateValidator.coerceDownloadedBytes(1500L, totalSize))

        // When total is 0 or negative
        assertEquals(50L, DownloadQueueStateValidator.coerceDownloadedBytes(50L, 0L))
    }

    @Test
    fun computeProgress_normalizesWithinZeroAndOne() {
        val totalSize = 1000L

        assertEquals(0f, DownloadQueueStateValidator.computeProgress(-10L, totalSize), 0.001f)
        assertEquals(0f, DownloadQueueStateValidator.computeProgress(0L, totalSize), 0.001f)
        assertEquals(0.25f, DownloadQueueStateValidator.computeProgress(250L, totalSize), 0.001f)
        assertEquals(0.5f, DownloadQueueStateValidator.computeProgress(500L, totalSize), 0.001f)
        assertEquals(1.0f, DownloadQueueStateValidator.computeProgress(1000L, totalSize), 0.001f)
        assertEquals(1.0f, DownloadQueueStateValidator.computeProgress(1500L, totalSize), 0.001f)

        // When totalSize is zero
        assertEquals(0f, DownloadQueueStateValidator.computeProgress(500L, 0L), 0.001f)
    }
}
