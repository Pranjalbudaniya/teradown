package com.tera.down

import com.tera.down.core.model.ShareLink
import com.tera.down.feature.resolver.AlternateUrlFormGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternateUrlFormGeneratorTest {

    @Test
    fun generateFormsProducesOrderedDeduplicatedForms() {
        val shareLink = ShareLink(
            originalUrl = "https://terabox.com/s/abcdef",
            surl = "abcdef",
            host = "terabox.com"
        )

        val forms = AlternateUrlFormGenerator.generateForms(shareLink)

        // 1. Original URL
        assertEquals("https://terabox.com/s/abcdef", forms[0])

        // 2. Contains 1-prefixed path because surl does not start with 1
        assertTrue(forms.contains("https://terabox.com/s/1abcdef"))

        // 3. Contains wap filelist path
        assertTrue(forms.contains("https://terabox.com/wap/share/filelist?surl=abcdef"))

        // 4. Contains verified mirror domains
        assertTrue(forms.any { it.contains("1024tera.com/s/abcdef") })
        assertTrue(forms.any { it.contains("teraboxapp.com/s/abcdef") })

        // 5. No duplicates
        assertEquals(forms.size, forms.toSet().size)
    }

    @Test
    fun generateFormsOmitsRedundant1PrefixWhenAlreadyPresent() {
        val shareLink = ShareLink(
            originalUrl = "https://www.terabox.com/s/1xyz987",
            surl = "1xyz987",
            host = "www.terabox.com"
        )

        val forms = AlternateUrlFormGenerator.generateForms(shareLink)

        // Must not generate https://www.terabox.com/s/11xyz987
        assertFalse(forms.any { it.contains("/s/11xyz987") })

        // Wap share filelist path uses surlWithoutLeadingOne
        assertTrue(forms.any { it.contains("/wap/share/filelist?surl=xyz987") })
    }
}
