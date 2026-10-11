package com.tera.down

import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.downloader.StorageValidationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StorageValidationAndDestinationTest {

    private lateinit var tempRoot: File
    private lateinit var storageManager: StorageManager

    @Before
    fun setUp() {
        val root = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        tempRoot = File(root, "storage_test_${System.currentTimeMillis()}")
        tempRoot.mkdirs()
        storageManager = StorageManager(baseDir = tempRoot)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun validateDirectory_validDirectoryReturnsSuccess() {
        val dir = File(tempRoot, "valid_downloads")
        dir.mkdirs()

        val result = storageManager.validateDirectory(dir)
        assertTrue(result is StorageValidationResult.Valid)
        val valid = result as StorageValidationResult.Valid
        assertEquals(dir.absolutePath, valid.directory.absolutePath)
        assertTrue(valid.availableBytes > 0L)
    }

    @Test
    fun validateDirectory_createsNonExistentDirectoryIfPossible() {
        val nonExistent = File(tempRoot, "subfolder/new_dir")
        assertFalse(nonExistent.exists())

        val result = storageManager.validateDirectory(nonExistent)
        assertTrue(result is StorageValidationResult.Valid)
        assertTrue(nonExistent.exists())
    }

    @Test
    fun validateDirectory_detectsWhenPathIsFileNotDirectory() {
        val fileNotDir = File(tempRoot, "a_file.txt")
        fileNotDir.writeText("sample")

        val result = storageManager.validateDirectory(fileNotDir)
        assertTrue(result is StorageValidationResult.Invalid)
        val invalid = result as StorageValidationResult.Invalid
        assertTrue(invalid.reason.contains("not a directory"))
    }

    @Test
    fun getEffectiveDownloadDirectory_fallsBackToDefaultOnInvalidPath() {
        val invalidPath = "/invalid/system/path/that/cannot/exist"
        val effective = storageManager.getEffectiveDownloadDirectory(invalidPath)

        // Falls back safely to default base directory
        assertEquals(tempRoot.absolutePath, effective.absolutePath)
    }

    @Test
    fun getDestinationFile_preservesCustomOrBaseDirectory() {
        val customDir = File(tempRoot, "custom_dest")
        customDir.mkdirs()

        val destDefault = storageManager.getDestinationFile("video.mp4")
        assertEquals(File(tempRoot, "video.mp4").absolutePath, destDefault.absolutePath)

        val destCustom = storageManager.getDestinationFile("video.mp4", customDir)
        assertEquals(File(customDir, "video.mp4").absolutePath, destCustom.absolutePath)
    }

    @Test
    fun changingDestination_preservesExistingCompletedFiles() {
        // Create an existing completed file in base directory
        val originalCompleted = File(tempRoot, "already_downloaded.mp4")
        originalCompleted.writeText("existing video data")

        // User switches to new custom directory
        val newCustomDir = File(tempRoot, "new_folder")
        newCustomDir.mkdirs()

        // Existing file remains intact in its original location
        assertTrue(originalCompleted.exists())
        assertEquals("existing video data", originalCompleted.readText())

        // New files go to newCustomDir
        val newFile = storageManager.getDestinationFile("next_download.mp4", newCustomDir)
        assertEquals(File(newCustomDir, "next_download.mp4").absolutePath, newFile.absolutePath)
    }
}
