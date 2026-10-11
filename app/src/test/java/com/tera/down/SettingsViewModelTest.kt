package com.tera.down

import com.tera.down.core.model.ThemeMode
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.ui.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var storageManager: StorageManager
    private lateinit var connectivityMonitor: FakeNetworkConnectivityMonitor
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val root = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        tempRoot = File(root, "settings_vm_test_${System.currentTimeMillis()}")
        tempRoot.mkdirs()

        settingsRepo = FakeSettingsRepository()
        storageManager = StorageManager(baseDir = tempRoot)
        connectivityMonitor = FakeNetworkConnectivityMonitor(initialConnected = true, initialWifi = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempRoot.deleteRecursively()
    }

    @Test
    fun uiState_reflectsInitialRepositoryAndStorageState() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            networkConnectivityMonitor = connectivityMonitor
        )

        val state = viewModel.uiState.value
        assertEquals(ThemeMode.SYSTEM, state.settings.themeMode)
        assertFalse(state.settings.wifiOnly)
        assertEquals(2, state.settings.maxConcurrentDownloads)
        assertTrue(state.isStorageValid)
        assertEquals(tempRoot.absolutePath, state.effectiveDownloadPath)
        assertTrue(state.isWifiConnected)
        assertTrue(state.isNetworkConnected)
    }

    @Test
    fun userActions_updateRepositoryAndState() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            networkConnectivityMonitor = connectivityMonitor
        )

        viewModel.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, viewModel.uiState.value.settings.themeMode)
        assertEquals(ThemeMode.DARK, settingsRepo.getSettings().themeMode)

        viewModel.setWifiOnly(true)
        assertTrue(viewModel.uiState.value.settings.wifiOnly)
        assertTrue(settingsRepo.getSettings().wifiOnly)

        viewModel.setMaxConcurrentDownloads(4)
        assertEquals(4, viewModel.uiState.value.settings.maxConcurrentDownloads)
        assertEquals(4, settingsRepo.getSettings().maxConcurrentDownloads)

        viewModel.setAutoRetryEnabled(false)
        assertFalse(viewModel.uiState.value.settings.autoRetryEnabled)

        viewModel.setConfirmLargeBatches(false)
        assertFalse(viewModel.uiState.value.settings.confirmLargeBatches)

        viewModel.setNotificationsEnabled(false)
        assertFalse(viewModel.uiState.value.settings.notificationsEnabled)
    }

    @Test
    fun runDiagnostic_generatesComprehensiveDiagnosticReport() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            networkConnectivityMonitor = connectivityMonitor
        )

        viewModel.runDiagnostic()

        val summary = viewModel.uiState.value.diagnosticSummary
        assertNotNull(summary)
        assertTrue(summary!!.contains("=== Teradown Diagnostics ==="))
        assertTrue(summary.contains("Network: Wi-Fi (Unmetered)"))
        assertTrue(summary.contains("Storage Path: ${tempRoot.absolutePath}"))
        assertTrue(summary.contains("Storage Health: Accessible"))
    }

    @Test
    fun resetDownloadDirectoryToDefault_clearsCustomPath() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            networkConnectivityMonitor = connectivityMonitor
        )

        val customDir = File(tempRoot, "custom_folder").apply { mkdirs() }
        viewModel.setDownloadDirectory(customDir.absolutePath)
        assertEquals(customDir.absolutePath, viewModel.uiState.value.effectiveDownloadPath)

        viewModel.resetDownloadDirectoryToDefault()
        assertEquals(tempRoot.absolutePath, viewModel.uiState.value.effectiveDownloadPath)
        assertEquals("", settingsRepo.getSettings().downloadDirectory)
    }
}
