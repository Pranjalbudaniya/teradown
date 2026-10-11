package com.tera.down.core.network

import com.tera.down.core.model.AppError
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Security interceptor ensuring redirect and on-the-wire requests:
 * 1. Enforce HTTPS only (rejects downgrade to cleartext HTTP).
 * 2. Restricts redirects to recognized/allowed hosts when a predicate is provided.
 * 3. Prevents redirection to untrusted internal or external endpoints.
 */
class SafeRedirectInterceptor(
    private val allowedHostsPredicate: ((String) -> Boolean)? = null,
    private val requireHttps: Boolean = true
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 1. Enforce HTTPS scheme
        if (requireHttps && !request.isHttps) {
            throw IOException(
                "Cleartext HTTP request rejected for security: ${AppError.redactSensitive(request.url.toString())}"
            )
        }

        // 2. Validate host against allowed predicate if specified
        allowedHostsPredicate?.let { predicate ->
            val host = request.url.host
            if (!predicate(host)) {
                throw IOException(
                    "Request to untrusted or unsupported host rejected: ${AppError.redactSensitive(host)}"
                )
            }
        }

        return chain.proceed(request)
    }
}
