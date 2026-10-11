package com.tera.down.ui.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Dedicated, testable desktop-mode configuration for Android WebViews per Section 18.3.
 *
 * Configures the WebView to emulate a desktop browser, stripping mobile identifiers
 * so Terabox endpoints serve the standard desktop web layout instead of mobile promo gates.
 */
object DesktopWebViewConfigurator {

    private val CHROME_VERSION_REGEX = Regex("""Chrome/([0-9.]+)""")
    private const val FALLBACK_CHROME_VERSION = "120.0.0.0"

    /**
     * Generates a desktop-mode User-Agent from the WebView's engine User-Agent.
     *
     * Extracts the actual Chromium version from [fromUserAgent] to prevent version mismatches,
     * and guarantees that mobile tokens ("; wv", "Mobile", "Android") are removed.
     */
    fun desktopUserAgent(fromUserAgent: String?): String {
        val raw = fromUserAgent.orEmpty()
        val chromeVer = CHROME_VERSION_REGEX.find(raw)?.groupValues?.getOrNull(1)?.ifBlank { null }
            ?: FALLBACK_CHROME_VERSION

        // Standard Linux x86_64 desktop User-Agent string
        val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"

        // Ensure no mobile indicators remain
        return desktopUa
            .replace("; wv", "")
            .replace("Mobile", "")
            .replace("Android", "")
            .trim()
    }

    const val UNRESTRICTED_ZOOM_JS = """
        (function() {
            try {
                var metas = document.querySelectorAll('meta[name="viewport"]');
                for (var i = 0; i < metas.length; i++) {
                    metas[i].setAttribute('content', 'width=1280, initial-scale=0.35, minimum-scale=0.05, maximum-scale=5.0, user-scalable=yes');
                }
                if (metas.length === 0) {
                    var meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=1280, initial-scale=0.35, minimum-scale=0.05, maximum-scale=5.0, user-scalable=yes';
                    if (document.head) {
                        document.head.appendChild(meta);
                    }
                }

                var styleId = 'teradown-desktop-viewport-style';
                var style = document.getElementById(styleId);
                if (!style) {
                    style = document.createElement('style');
                    style.id = styleId;
                    style.type = 'text/css';
                    style.innerHTML = 'html, body { overflow-x: auto !important; min-width: 1200px !important; width: 1280px !important; touch-action: auto !important; -webkit-overflow-scrolling: touch !important; }';
                    if (document.head) {
                        document.head.appendChild(style);
                    }
                }

                document.documentElement.style.overflowX = 'auto';
                document.documentElement.style.touchAction = 'auto';
                if (document.body) {
                    document.body.style.overflowX = 'auto';
                    document.body.style.touchAction = 'auto';
                }
            } catch (e) {}
        })();
    """

    /**
     * Applies desktop-mode settings to the given Android [webView].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL

        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true

        val currentUa = settings.userAgentString
        settings.userAgentString = desktopUserAgent(currentUa)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Injects JavaScript to override any restrictive viewport meta tags,
     * ensuring pinch-to-zoom and multi-touch scaling remain fully operational.
     */
    fun enableUnrestrictedZoom(webView: WebView?) {
        webView?.evaluateJavascript(UNRESTRICTED_ZOOM_JS, null)
    }
}


