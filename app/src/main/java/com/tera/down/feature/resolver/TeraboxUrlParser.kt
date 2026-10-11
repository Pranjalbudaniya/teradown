package com.tera.down.feature.resolver

import com.tera.down.core.model.ShareLink

/**
 * Result of parsing a Terabox share link.
 */
sealed interface TeraboxUrlParseResult {
    data class Success(val shareLink: ShareLink) : TeraboxUrlParseResult
    data class InvalidUrl(val reason: String) : TeraboxUrlParseResult
}

/**
 * Pure Kotlin parser for Terabox share URLs according to Section 1 & 5 of the specification.
 * Delegates to [TeraboxShareLinkParser].
 */
object TeraboxUrlParser {

    /**
     * Recognized official Terabox and verified mirror root domains.
     */
    val RECOGNIZED_DOMAINS: Set<String> get() = TeraboxShareLinkParser.supportedDomains

    /**
     * Parses and validates a Terabox share link string.
     */
    fun parse(rawInput: String?): TeraboxUrlParseResult {
        return when (val res = TeraboxShareLinkParser.parse(rawInput)) {
            is ShareLinkParseResult.Success -> TeraboxUrlParseResult.Success(res.shareLink)
            is ShareLinkParseResult.InvalidUrl -> TeraboxUrlParseResult.InvalidUrl(res.reason)
            is ShareLinkParseResult.UnsupportedHost -> TeraboxUrlParseResult.InvalidUrl(res.reason)
        }
    }

    /**
     * Checks if the host strictly matches a recognized domain or is a subdomain of a recognized domain.
     */
    fun isRecognizedHost(host: String): Boolean = TeraboxShareLinkParser.isRecognizedHost(host)
}
