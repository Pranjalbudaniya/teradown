package com.tera.down

import com.tera.down.feature.downloader.StorageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StorageManagerTest {

    private lateinit var tempBaseDir: File
    private lateinit var storageManager: StorageManager

    @Before
    fun setup() {
        val root = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        tempBaseDir = File(root, "storage_test_${System.currentTimeMillis()}")
        tempBaseDir.mkdirs()

        // Create with custom base directory for isolated file testing
        storageManager = StorageManager(
            baseDir = tempBaseDir
        )
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun directoriesCreatedProperly() {
        assertTrue(storageManager.downloadDirectory.exists())
        assertTrue(storageManager.tempDirectory.exists())
    }

    @Test
    fun destinationAndTempPaths_resolved() {
        val dest = storageManager.getDestinationFile("test_file.mp4")
        assertEquals(File(tempBaseDir, "test_file.mp4").absolutePath, dest.absolutePath)

        val temp = storageManager.getTempFile("task_123")
        assertEquals(File(File(tempBaseDir, ".temp"), "task_task_123.tmp").absolutePath, temp.absolutePath)
    }

    @Test
    fun finalizeDownload_atomicallyMovesTempFileToDestination() {
        val temp = storageManager.getTempFile("task_abc")
        temp.writeText("Download content payload")

        val targetDest = storageManager.getDestinationFile("output.txt")
        val finalized = storageManager.finalizeDownload(temp, targetDest)

        assertTrue(finalized.exists())
        assertEquals("Download content payload", finalized.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun finalizeDownload_resolvesCollisionsWithoutOverwriting() {
        val existingDest = storageManager.getDestinationFile("collision.txt")
        existingDest.writeText("Original file")

        val temp = storageManager.getTempFile("task_new")
        temp.writeText("New file")

        val finalized = storageManager.finalizeDownload(temp, existingDest)

        // Original preserved
        assertTrue(existingDest.exists())
        assertEquals("Original file", existingDest.readText())

        // New file given collision index
        assertTrue(finalized.exists())
        assertEquals("collision_(1).txt", finalized.name)
        assertEquals("New file", finalized.readText())
    }

    @Test
    fun cleanupTempFile_removesStagingFile() {
        val temp = storageManager.getTempFile("to_clean")
        temp.writeText("temporary partial data")
        assertTrue(temp.exists())

        val cleaned = storageManager.cleanupTempFile("to_clean")
        assertTrue(cleaned)
        assertFalse(temp.exists())
    }
}
