package com.tera.down.feature.resolver

import com.tera.down.core.model.ShareLink
import java.net.URI

/**
 * Contract for host-specific share link resolvers.
 */
interface ShareLinkResolver {
    val supportedDomains: Set<String>
    fun canHandle(host: String): Boolean

    suspend fun resolve(
        shareLink: ShareLink,
        password: String? = null,
        dirPath: String = "/",
        page: Int = 1
    ): ShareResolveResult
}

/**
 * Registry managing host-specific share link resolvers.
 */
interface ShareLinkResolverRegistry {
    fun register(resolver: ShareLinkResolver)
    fun getResolverForHost(host: String): ShareLinkResolver?
    fun getResolverForUrl(url: String): ShareLinkResolver?
    fun getAllSupportedDomains(): Set<String>
}

/**
 * Default implementation of [ShareLinkResolverRegistry].
 */
class ShareLinkResolverRegistryImpl(
    initialResolvers: List<ShareLinkResolver> = emptyList()
) : ShareLinkResolverRegistry {

    private val resolvers = mutableListOf<ShareLinkResolver>().apply {
        addAll(initialResolvers)
    }

    override fun register(resolver: ShareLinkResolver) {
        resolvers.add(resolver)
    }

    override fun getResolverForHost(host: String): ShareLinkResolver? {
        val lowerHost = host.lowercase().trim()
        return resolvers.firstOrNull { it.canHandle(lowerHost) }
    }

    override fun getResolverForUrl(url: String): ShareLinkResolver? {
        val normalized = if (!url.contains("://")) "https://${url.trim()}" else url.trim()
        val host = try {
            URI(normalized).host
        } catch (_: Exception) {
            null
        } ?: return null
        return getResolverForHost(host)
    }

    override fun getAllSupportedDomains(): Set<String> {
        return resolvers.flatMap { it.supportedDomains }.toSet()
    }
}
