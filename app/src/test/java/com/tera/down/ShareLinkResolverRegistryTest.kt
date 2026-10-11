package com.tera.down

import com.tera.down.core.model.ShareLink
import com.tera.down.feature.resolver.ShareLinkResolver
import com.tera.down.feature.resolver.ShareLinkResolverRegistryImpl
import com.tera.down.feature.resolver.ShareResolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkResolverRegistryTest {

    private class FakeResolver(
        override val supportedDomains: Set<String> = setOf("mockhost.com", "mirror.mockhost.com")
    ) : ShareLinkResolver {
        override fun canHandle(host: String): Boolean {
            val lower = host.lowercase()
            return supportedDomains.any { lower == it || lower.endsWith(".$it") }
        }

        override suspend fun resolve(
            shareLink: ShareLink,
            password: String?,
            dirPath: String,
            page: Int
        ): ShareResolveResult = ShareResolveResult.Failure(
            com.tera.down.feature.resolver.ShareResolveErrorType.UNSUPPORTED_HOST,
            "Fake"
        )
    }

    @Test
    fun registry_findsRegisteredResolverByHostAndUrl() {
        val resolver = FakeResolver()
        val registry = ShareLinkResolverRegistryImpl(listOf(resolver))

        assertNotNull(registry.getResolverForHost("mockhost.com"))
        assertNotNull(registry.getResolverForHost("sub.mockhost.com"))
        assertNotNull(registry.getResolverForUrl("https://mockhost.com/s/1xyz"))
        assertNotNull(registry.getResolverForUrl("https://sub.mirror.mockhost.com/s/1xyz"))
        assertNull(registry.getResolverForHost("unknown.org"))
        assertNull(registry.getResolverForUrl("https://unknown.org/share"))
    }

    @Test
    fun registry_aggregatesAllSupportedDomains() {
        val r1 = FakeResolver(setOf("host1.com"))
        val r2 = FakeResolver(setOf("host2.com"))
        val registry = ShareLinkResolverRegistryImpl(listOf(r1, r2))

        val all = registry.getAllSupportedDomains()
        assertEquals(2, all.size)
        assertTrue(all.contains("host1.com"))
        assertTrue(all.contains("host2.com"))
    }
}
