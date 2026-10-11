package com.tera.down

import com.tera.down.feature.resolver.TeraboxUrlParseResult
import com.tera.down.feature.resolver.TeraboxUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeraboxUrlParserTest {

    @Test
    fun parse_standardPathWithLeadingOne_returnsSuccess() {
        val url = "https://terabox.com/s/1aB2cD3e"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("1aB2cD3e", success.shareLink.surl)
        assertEquals("terabox.com", success.shareLink.host)
        assertNull(success.shareLink.pwd)
        assertEquals(url, success.shareLink.originalUrl)
        assertEquals("aB2cD3e", success.shareLink.surlWithoutLeadingOne)
        assertEquals("1aB2cD3e", success.shareLink.surlWithLeadingOne)
    }

    @Test
    fun parse_pathWithoutLeadingOne_preservesExtractedSurl() {
        val url = "https://www.terabox.com/s/aB2cD3e"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("aB2cD3e", success.shareLink.surl)
        assertEquals("www.terabox.com", success.shareLink.host)
        assertNull(success.shareLink.pwd)
        assertEquals("aB2cD3e", success.shareLink.surlWithoutLeadingOne)
        assertEquals("1aB2cD3e", success.shareLink.surlWithLeadingOne)
    }

    @Test
    fun parse_wapShareQueryParameter_returnsSuccess() {
        val url = "https://terabox.com/wap/share/filelist?surl=xyz987"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("xyz987", success.shareLink.surl)
        assertNull(success.shareLink.pwd)
    }

    @Test
    fun parse_shareInitQueryParameter_returnsSuccess() {
        val url = "https://1024tera.com/share/init?surl=1alphaBeta"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("1alphaBeta", success.shareLink.surl)
        assertEquals("1024tera.com", success.shareLink.host)
    }

    @Test
    fun parse_urlWithExtractionPassword_extractsPassword() {
        val url = "https://terabox.com/s/1aB2cD3e?pwd=7x9z"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("1aB2cD3e", success.shareLink.surl)
        assertEquals("7x9z", success.shareLink.pwd)
    }

    @Test
    fun parse_wapShareWithPassword_extractsBothSurlAndPwd() {
        val url = "https://www.teraboxapp.com/wap/share/filelist?surl=1demo123&pwd=pass"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("1demo123", success.shareLink.surl)
        assertEquals("pass", success.shareLink.pwd)
    }

    @Test
    fun parse_percentEncodedQueryValues_decodesCorrectly() {
        val url = "https://terabox.com/s/1test?pwd=%31%32%33%34"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("1234", success.shareLink.pwd)

        val urlEncodedSurl = "https://terabox.com/wap/share/filelist?surl=%31%61%62%63&pwd=%6b%65%79"
        val result2 = TeraboxUrlParser.parse(urlEncodedSurl)
        assertTrue(result2 is TeraboxUrlParseResult.Success)
        val success2 = result2 as TeraboxUrlParseResult.Success
        assertEquals("1abc", success2.shareLink.surl)
        assertEquals("key", success2.shareLink.pwd)
    }

    @Test
    fun parse_schemelessInput_normalizesToHttps() {
        val url = "terabox.com/s/1aB2cD3e"
        val result = TeraboxUrlParser.parse(url)

        assertTrue(result is TeraboxUrlParseResult.Success)
        val success = result as TeraboxUrlParseResult.Success
        assertEquals("1aB2cD3e", success.shareLink.surl)
        assertEquals("terabox.com", success.shareLink.host)
    }

    @Test
    fun parse_allRecognizedMirrorDomains_accepted() {
        val mirrors = listOf(
            "terabox.com",
            "teraboxapp.com",
            "1024tera.com",
            "1024terabox.com",
            "teraboxshare.com",
            "teraboxlink.com",
            "dubox.com",
            "mirrobox.com",
            "nephobox.com",
            "4funbox.com",
            "momerybox.com"
        )

        for (mirror in mirrors) {
            val url = "https://$mirror/s/1xyz"
            val result = TeraboxUrlParser.parse(url)
            assertTrue("Mirror failed: $mirror", result is TeraboxUrlParseResult.Success)
            assertEquals("1xyz", (result as TeraboxUrlParseResult.Success).shareLink.surl)

            val subdomainUrl = "https://sub.$mirror/s/1xyz"
            val resultSub = TeraboxUrlParser.parse(subdomainUrl)
            assertTrue("Subdomain failed: sub.$mirror", resultSub is TeraboxUrlParseResult.Success)
        }
    }

    @Test
    fun parse_unsupportedSchemes_returnsInvalidUrl() {
        val unsupported = listOf(
            "ftp://terabox.com/s/1abc",
            "file:///sdcard/movie.mp4",
            "javascript:alert(1)",
            "content://media/external/123",
            "ws://terabox.com/s/1abc"
        )
        for (u in unsupported) {
            val result = TeraboxUrlParser.parse(u)
            assertTrue("Expected InvalidUrl for: $u", result is TeraboxUrlParseResult.InvalidUrl)
        }
    }

    @Test
    fun parse_unrelatedDomains_returnsInvalidUrl() {
        val unrelated = listOf(
            "https://google.com/s/1abc",
            "https://example.com/share?surl=1abc",
            "https://dropbox.com/s/1abc",
            "https://mega.nz/file/1abc"
        )
        for (u in unrelated) {
            val result = TeraboxUrlParser.parse(u)
            assertTrue("Expected InvalidUrl for: $u", result is TeraboxUrlParseResult.InvalidUrl)
            val invalid = result as TeraboxUrlParseResult.InvalidUrl
            assertTrue(invalid.reason.contains("not a recognized Terabox"))
        }
    }

    @Test
    fun parse_misleadingHostnamesContainingTerabox_rejected() {
        val phishingUrls = listOf(
            "https://fake-terabox.com/s/1abc",
            "https://not-terabox.com/s/1abc",
            "https://terabox.attacker.org/s/1abc",
            "https://terabox-download.xyz/s/1abc",
            "https://evilterabox.com/s/1abc",
            "https://terabox.com.attacker.com/s/1abc"
        )
        for (u in phishingUrls) {
            val result = TeraboxUrlParser.parse(u)
            assertTrue("Expected rejection for phishing URL: $u", result is TeraboxUrlParseResult.InvalidUrl)
        }
    }

    @Test
    fun parse_missingIdentifier_returnsInvalidUrl() {
        val invalidUrls = listOf(
            "https://terabox.com/s/",
            "https://terabox.com/s",
            "https://terabox.com/wap/share/filelist?surl=",
            "https://terabox.com/wap/share/filelist",
            "https://terabox.com/"
        )
        for (u in invalidUrls) {
            val result = TeraboxUrlParser.parse(u)
            assertTrue("Expected InvalidUrl for: $u", result is TeraboxUrlParseResult.InvalidUrl)
        }
    }

    @Test
    fun parse_emptyAndBlankInput_returnsInvalidUrl() {
        assertTrue(TeraboxUrlParser.parse("") is TeraboxUrlParseResult.InvalidUrl)
        assertTrue(TeraboxUrlParser.parse("   ") is TeraboxUrlParseResult.InvalidUrl)
        assertTrue(TeraboxUrlParser.parse(null) is TeraboxUrlParseResult.InvalidUrl)
    }

    @Test
    fun parse_malformedInput_doesNotThrowExceptions() {
        val malformed = listOf(
            "not a url",
            "://missing-scheme",
            "https://",
            "https:///s/1abc",
            "http://[invalid-host]:8080/s/1abc",
            "%%%not_a_valid_percent_encoding%%%",
            "https://terabox.com/s/1abc?pwd=%%%"
        )
        for (item in malformed) {
            val result = TeraboxUrlParser.parse(item)
            assertTrue("Malformed input should yield InvalidUrl or safe result without crashing: $item",
                result is TeraboxUrlParseResult.InvalidUrl || result is TeraboxUrlParseResult.Success)
        }
    }

    @Test
    fun shareLink_masksPasswordInToString() {
        val linkWithPassword = com.tera.down.core.model.ShareLink(
            originalUrl = "https://terabox.com/s/1abc?pwd=secretPassword",
            surl = "1abc",
            pwd = "secretPassword",
            host = "terabox.com"
        )
        val stringRepresentation = linkWithPassword.toString()
        assertFalse(stringRepresentation.contains("secretPassword"))
        assertTrue(stringRepresentation.contains("[PROTECTED]"))
    }
}
