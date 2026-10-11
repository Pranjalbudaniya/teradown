package com.tera.down

import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.MediaType
import com.tera.down.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private lateinit var settingsRepository: FakeSettingsRepository

    @Before
    fun setUp() {
        settingsRepository = FakeSettingsRepository()
    }

    @Test
    fun defaultSettings_hasSafeExpectedDefaults() = runTest {
        val settings = settingsRepository.getSettings()

        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals("", settings.downloadDirectory)
        assertFalse(settings.wifiOnly)
        assertEquals(2, settings.maxConcurrentDownloads)
        assertTrue(settings.autoRetryEnabled)
        assertEquals(3, settings.maxRetryAttempts)
        assertTrue(settings.notificationsEnabled)
        assertTrue(settings.confirmLargeBatches)
        assertNull(settings.defaultMediaFilter)
    }

    @Test
    fun persistenceAndRestoration_updatesSettingsFlowReactively() = runTest {
        settingsRepository.setThemeMode(ThemeMode.DARK)
        settingsRepository.setWifiOnly(true)
        settingsRepository.setDownloadDirectory("/storage/emulated/0/Download")
        settingsRepository.setNotificationsEnabled(false)
        settingsRepository.setDefaultMediaFilter(MediaType.VIDEO)

        val updated = settingsRepository.settingsFlow.first()

        assertEquals(ThemeMode.DARK, updated.themeMode)
        assertTrue(updated.wifiOnly)
        assertEquals("/storage/emulated/0/Download", updated.downloadDirectory)
        assertFalse(updated.notificationsEnabled)
        assertEquals(MediaType.VIDEO, updated.defaultMediaFilter)
    }

    @Test
    fun maxConcurrentDownloads_clampedToValidRange() = runTest {
        // Below minimum (1)
        settingsRepository.setMaxConcurrentDownloads(0)
        assertEquals(AppSettings.MIN_CONCURRENT, settingsRepository.getSettings().maxConcurrentDownloads)

        settingsRepository.setMaxConcurrentDownloads(-5)
        assertEquals(AppSettings.MIN_CONCURRENT, settingsRepository.getSettings().maxConcurrentDownloads)

        // Above maximum (4)
        settingsRepository.setMaxConcurrentDownloads(10)
        assertEquals(AppSettings.MAX_CONCURRENT, settingsRepository.getSettings().maxConcurrentDownloads)

        // Valid values
        settingsRepository.setMaxConcurrentDownloads(3)
        assertEquals(3, settingsRepository.getSettings().maxConcurrentDownloads)
    }

    @Test
    fun maxRetryAttempts_clampedToValidRange() = runTest {
        // Below minimum (1)
        settingsRepository.setMaxRetryAttempts(0)
        assertEquals(AppSettings.MIN_RETRIES, settingsRepository.getSettings().maxRetryAttempts)

        // Above maximum (5)
        settingsRepository.setMaxRetryAttempts(99)
        assertEquals(AppSettings.MAX_RETRIES, settingsRepository.getSettings().maxRetryAttempts)

        // Valid
        settingsRepository.setMaxRetryAttempts(4)
        assertEquals(4, settingsRepository.getSettings().maxRetryAttempts)
    }

    @Test
    fun resetToDefaults_restoresInitialState() = runTest {
        settingsRepository.setThemeMode(ThemeMode.LIGHT)
        settingsRepository.setWifiOnly(true)
        settingsRepository.setMaxConcurrentDownloads(4)

        settingsRepository.resetToDefaults()

        val reset = settingsRepository.getSettings()
        assertEquals(ThemeMode.SYSTEM, reset.themeMode)
        assertFalse(reset.wifiOnly)
        assertEquals(2, reset.maxConcurrentDownloads)
    }
}
