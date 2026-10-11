package com.tera.down

import com.tera.down.feature.resolver.ShareLinkParseResult
import com.tera.down.feature.resolver.TeraboxShareLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkParserTest {

    @Test
    fun parse_validHttpsTeraboxLinks_returnsSuccess() {
        val url = "https://terabox.com/s/1abcXYZ"
        val result = TeraboxShareLinkParser.parse(url)
        assertTrue(result is ShareLinkParseResult.Success)
        val success = result as ShareLinkParseResult.Success
        assertEquals("1abcXYZ", success.shareLink.surl)
        assertEquals("terabox.com", success.shareLink.host)
        assertNull(success.shareLink.pwd)
    }

    @Test
    fun parse_urlWithPassword_extractsPasswordSafely() {
        val url = "https://www.teraboxapp.com/s/1def456?pwd=secret12"
        val result = TeraboxShareLinkParser.parse(url)
        assertTrue(result is ShareLinkParseResult.Success)
        val success = result as ShareLinkParseResult.Success
        assertEquals("1def456", success.shareLink.surl)
        assertEquals("secret12", success.shareLink.pwd)
        assertEquals("www.teraboxapp.com", success.shareLink.host)
    }

    @Test
    fun parse_wapShareFileListFormat_extractsSurl() {
        val url = "https://1024tera.com/wap/share/filelist?surl=quick789&pwd=9999"
        val result = TeraboxShareLinkParser.parse(url)
        assertTrue(result is ShareLinkParseResult.Success)
        val success = result as ShareLinkParseResult.Success
        assertEquals("quick789", success.shareLink.surl)
        assertEquals("9999", success.shareLink.pwd)
    }

    @Test
    fun parse_unsupportedHost_returnsUnsupportedHost() {
        val url = "https://dropbox.com/s/1abcXYZ"
        val result = TeraboxShareLinkParser.parse(url)
        assertTrue(result is ShareLinkParseResult.UnsupportedHost)
        val unsupported = result as ShareLinkParseResult.UnsupportedHost
        assertEquals("dropbox.com", unsupported.host)
    }

    @Test
    fun parse_malformedOrEmptyUrls_returnsInvalidUrl() {
        assertTrue(TeraboxShareLinkParser.parse(null) is ShareLinkParseResult.InvalidUrl)
        assertTrue(TeraboxShareLinkParser.parse("") is ShareLinkParseResult.InvalidUrl)
        assertTrue(TeraboxShareLinkParser.parse("   ") is ShareLinkParseResult.InvalidUrl)
        assertTrue(TeraboxShareLinkParser.parse("ftp://terabox.com/s/1abc") is ShareLinkParseResult.InvalidUrl)
        assertTrue(TeraboxShareLinkParser.parse("https://terabox.com/invalid/path") is ShareLinkParseResult.InvalidUrl)
    }

    @Test
    fun canParse_correctlyFiltersSupportedHosts() {
        assertTrue(TeraboxShareLinkParser.canParse("https://terabox.com/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("https://sub.1024tera.com/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("dubox.com/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("https://terabox.app/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("https://terasharelink.com/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("https://tibibox.com/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("https://nephobox.com/s/1test"))
        assertTrue(TeraboxShareLinkParser.canParse("https://4funbox.com/s/1test"))
        assertFalse(TeraboxShareLinkParser.canParse("https://google.com"))
        assertFalse(TeraboxShareLinkParser.canParse(null))
        assertFalse(TeraboxShareLinkParser.canParse(""))
    }

    @Test
    fun parse_variousMirrorDomainsAndPathTypes_returnsSuccess() {
        val testCases = listOf(
            "https://terabox.app/s/1applink" to "1applink",
            "https://terasharelink.com/share/1sharelink" to "1sharelink",
            "https://tibibox.com/sharing/1tibibox" to "1tibibox",
            "https://freeterabox.com/sharing/link?surl=1free" to "1free",
            "https://terafileshare.com/disk/share?surl=1disk" to "1disk",
            "https://terabox.com/share/init?surl=1init" to "1init",
            "https://teraboxapp.com/wap/link/1wap" to "1wap",
            "https://box.sharing.in/s/1box" to "1box"
        )

        for ((url, expectedSurl) in testCases) {
            val result = TeraboxShareLinkParser.parse(url)
            assertTrue("Expected success for $url, got $result", result is ShareLinkParseResult.Success)
            assertEquals(expectedSurl, (result as ShareLinkParseResult.Success).shareLink.surl)
        }
    }

    @Test
    fun parse_spaHashRoutingFormat_extractsSurlAndPwd() {
        val url = "https://terabox.com/#/s/1hashRoute?pwd=abcd"
        val result = TeraboxShareLinkParser.parse(url)
        assertTrue(result is ShareLinkParseResult.Success)
        val success = result as ShareLinkParseResult.Success
        assertEquals("1hashRoute", success.shareLink.surl)
        assertEquals("abcd", success.shareLink.pwd)
    }

    @Test
    fun parse_pastedChatMessageWithTextAndCode_extractsCleanUrlAndPwd() {
        val text = "Hey, download this file: https://terabox.com/s/1messageLink password: 9876 enjoy!"
        val result = TeraboxShareLinkParser.parse(text)
        assertTrue(result is ShareLinkParseResult.Success)
        val success = result as ShareLinkParseResult.Success
        assertEquals("1messageLink", success.shareLink.surl)
        assertEquals("9876", success.shareLink.pwd)
    }
}

