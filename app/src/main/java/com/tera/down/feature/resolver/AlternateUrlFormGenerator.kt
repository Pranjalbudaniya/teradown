package com.tera.down.feature.resolver

import com.tera.down.core.model.ShareLink

/**
 * Generates alternate URL forms for a Terabox share link per Section 18.7 (Layer C).
 *
 * Terabox servers occasionally present promo gates on specific URL paths or subdomains
 * while rendering the standard file-list view on alternate endpoint forms.
 */
object AlternateUrlFormGenerator {

    /**
     * Generates an ordered, de-duplicated list of safe alternate URLs for [shareLink].
     *
     * Forms generated:
     * 1. The original validated share URL.
     * 2. The standard desktop share path: `https://<domain>/s/<surl>`.
     * 3. The `1`-prefixed share path: `https://<domain>/s/1<surl>` (only if [surl] does not start with `1`).
     * 4. The wap filelist path: `https://<domain>/wap/share/filelist?surl=<surlWithoutLeadingOne>`.
     * 5. Verified mirror domains for the desktop share path.
     */
    fun generateForms(shareLink: ShareLink): List<String> {
        val forms = LinkedHashSet<String>()
        val host = shareLink.host?.ifBlank { "terabox.com" } ?: "terabox.com"
        val surl = shareLink.surl
        val surlWithout1 = shareLink.surlWithoutLeadingOne

        // 1. Original URL
        if (shareLink.originalUrl.isNotBlank() && shareLink.originalUrl.startsWith("http", ignoreCase = true)) {
            forms.add(shareLink.originalUrl)
        }

        // 2. Standard desktop /s/ path on current host
        if (surl.isNotBlank()) {
            forms.add("https://$host/s/$surl")
        }

        // 3. 1-prefixed share identifier form (only if surl does not start with 1)
        if (surl.isNotBlank() && !surl.startsWith("1")) {
            forms.add("https://$host/s/1$surl")
        }

        // 4. Wap filelist path
        if (surlWithout1.isNotBlank()) {
            forms.add("https://$host/wap/share/filelist?surl=$surlWithout1")
        }

        // 5. Configured verified mirror domains (e.g. 1024tera.com, terabox.app)
        val verifiedMirrors = TeraboxShareLinkParser.supportedDomains
        for (mirror in verifiedMirrors) {
            if (!mirror.equals(host, ignoreCase = true) && surl.isNotBlank()) {
                forms.add("https://$mirror/s/$surl")
            }
        }

        return forms.toList()
    }
}
