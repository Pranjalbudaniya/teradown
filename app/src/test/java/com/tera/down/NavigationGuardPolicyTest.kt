package com.tera.down

import com.tera.down.ui.auth.NavigationAction
import com.tera.down.ui.auth.NavigationGuardPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigationGuardPolicyTest {

    private lateinit var policy: NavigationGuardPolicy

    @Before
    fun setup() {
        policy = NavigationGuardPolicy(maxPromoHits = 3)
    }

    @Test
    fun normalHttpsNavigationIsAllowed() {
        val result = policy.evaluateUrl("https://www.terabox.com/s/1abcDEF")
        assertEquals(NavigationAction.Allow, result)
        assertEquals(0, policy.promoHits)
    }

    @Test
    fun marketAndTeraboxSchemesAreBlockedAndIncrementPromoHits() {
        val marketResult = policy.evaluateUrl("market://details?id=com.dubox.drive")
        assertTrue(marketResult is NavigationAction.BlockAndStay)
        assertEquals(1, policy.promoHits)

        val teraboxResult = policy.evaluateUrl("terabox://share?surl=1abcDEF")
        assertTrue(teraboxResult is NavigationAction.BlockAndStay)
        assertEquals(2, policy.promoHits)
    }

    @Test
    fun appStoreWebUrlsAreBlockedAndIncrementPromoHits() {
        val playStoreResult = policy.evaluateUrl("https://play.google.com/store/apps/details?id=com.dubox.drive")
        assertTrue(playStoreResult is NavigationAction.BlockAndStay)
        assertEquals(1, policy.promoHits)

        val appleStoreResult = policy.evaluateUrl("https://apps.apple.com/app/terabox/id1509453185")
        assertTrue(appleStoreResult is NavigationAction.BlockAndStay)
        assertEquals(2, policy.promoHits)
    }

    @Test
    fun installTrackerWebUrlsAreBlockedAndIncrementPromoHits() {
        val adjustResult = policy.evaluateUrl("https://app.adjust.com/abc123_xyz?campaign=web_banner")
        assertTrue(adjustResult is NavigationAction.BlockAndStay)
        assertEquals(1, policy.promoHits)

        val branchResult = policy.evaluateUrl("https://terabox.app.link/download?pid=site")
        assertTrue(branchResult is NavigationAction.BlockAndStay)
        assertEquals(2, policy.promoHits)
    }

    @Test
    fun intentWithSafeApprovedFallbackUrlLoadsFallback() {
        val safeFallback = "https://www.terabox.com/s/1xyz987"
        val intentUri = "intent://details#Intent;package=com.dubox.drive;S.browser_fallback_url=" +
                java.net.URLEncoder.encode(safeFallback, "UTF-8") + ";end"

        val result = policy.evaluateUrl(intentUri)
        assertTrue(result is NavigationAction.LoadFallback)
        assertEquals(safeFallback, (result as NavigationAction.LoadFallback).fallbackUrl)
        assertEquals(1, policy.promoHits)
    }

    @Test
    fun intentWithUnsafeOrExternalFallbackUrlIsBlocked() {
        val unsafeFallback = "https://malicious-site.example.com/phishing"
        val intentUri = "intent://details#Intent;package=com.dubox.drive;S.browser_fallback_url=" +
                java.net.URLEncoder.encode(unsafeFallback, "UTF-8") + ";end"

        val result = policy.evaluateUrl(intentUri)
        assertTrue(result is NavigationAction.BlockAndStay)
        assertEquals(1, policy.promoHits)
    }

    @Test
    fun intentWithoutFallbackUrlIsBlocked() {
        val intentUri = "intent://share/123#Intent;scheme=terabox;package=com.dubox.drive;end"
        val result = policy.evaluateUrl(intentUri)
        assertTrue(result is NavigationAction.BlockAndStay)
        assertEquals(1, policy.promoHits)
    }

    @Test
    fun maxPromoHitsCapHaltsRedirectLoopsAtThree() {
        policy.evaluateUrl("market://details?id=com.dubox.drive") // 1
        policy.evaluateUrl("https://play.google.com/store/apps/details?id=com.dubox.drive") // 2

        val thirdAttempt = policy.evaluateUrl("https://app.adjust.com/install") // 3
        assertTrue(thirdAttempt is NavigationAction.MaxPromoHitsExceeded)
        assertEquals(3, (thirdAttempt as NavigationAction.MaxPromoHitsExceeded).hits)
    }

    @Test
    fun extractBrowserFallbackUrlHandlesQueryParamAndIntentComponent() {
        val fromComponent = policy.extractBrowserFallbackUrl(
            "intent://#Intent;S.browser_fallback_url=https%3A%2F%2Fterabox.app%2Fs%2F123;end"
        )
        assertEquals("https://terabox.app/s/123", fromComponent)

        val fromParam = policy.extractBrowserFallbackUrl(
            "intent://open?browser_fallback_url=https%3A%2F%2F1024tera.com%2Fs%2F456"
        )
        assertEquals("https://1024tera.com/s/456", fromParam)

        val none = policy.extractBrowserFallbackUrl("intent://open?no_fallback=1")
        assertNull(none)
    }

    @Test
    fun isSafeApprovedTeraboxUrlValidatesHosts() {
        assertTrue(policy.isSafeApprovedTeraboxUrl("https://www.terabox.com/s/123"))
        assertTrue(policy.isSafeApprovedTeraboxUrl("https://1024tera.com/wap/share"))
        assertTrue(policy.isSafeApprovedTeraboxUrl("https://teraboxapp.com/s/123"))

        assertFalse(policy.isSafeApprovedTeraboxUrl("https://google.com"))
        assertFalse(policy.isSafeApprovedTeraboxUrl("https://attacker-terabox.com"))
        assertFalse(policy.isSafeApprovedTeraboxUrl("ftp://terabox.com"))
    }
}
