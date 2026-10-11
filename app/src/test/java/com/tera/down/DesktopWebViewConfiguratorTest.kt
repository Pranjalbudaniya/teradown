package com.tera.down

import com.tera.down.ui.auth.DesktopWebViewConfigurator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWebViewConfiguratorTest {

    @Test
    fun desktopUserAgentExtractsEngineChromiumVersion() {
        val sampleMobileUa = "Mozilla/5.0 (Linux; U; Android 14; Pixel 8 Build/UD1A.230804.041; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36"

        val desktopUa = DesktopWebViewConfigurator.desktopUserAgent(sampleMobileUa)

        assertTrue(desktopUa.contains("Chrome/128.0.6613.127"))
        assertTrue(desktopUa.contains("X11; Linux x86_64"))
        assertFalse(desktopUa.contains("Android"))
        assertFalse(desktopUa.contains("Mobile"))
        assertFalse(desktopUa.contains("; wv"))
    }

    @Test
    fun desktopUserAgentFallsBackGracefullyWhenNoVersionFound() {
        val fallbackUa = DesktopWebViewConfigurator.desktopUserAgent("Mozilla/5.0")
        assertTrue(fallbackUa.contains("Chrome/120.0.0.0"))
        assertTrue(fallbackUa.contains("X11; Linux x86_64"))
        assertFalse(fallbackUa.contains("Android"))
        assertFalse(fallbackUa.contains("Mobile"))
    }

    @Test
    fun desktopUserAgentHandlesNullInput() {
        val desktopUa = DesktopWebViewConfigurator.desktopUserAgent(null)
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            desktopUa
        )
    }

    @Test
    fun unrestrictedZoomScriptEnsuresUserScalableAndWideRange() {
        val script = DesktopWebViewConfigurator.UNRESTRICTED_ZOOM_JS
        assertTrue(script.contains("user-scalable=yes"))
        assertTrue(script.contains("width=1280"))
        assertTrue(script.contains("overflow-x: auto"))
        assertTrue(script.contains("touchAction = 'auto'"))
    }
}


