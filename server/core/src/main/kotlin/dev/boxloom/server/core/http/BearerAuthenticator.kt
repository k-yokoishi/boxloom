package dev.boxloom.server.core.http

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ApiException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class BearerAuthenticator(
    private val expectedToken: String?,
) {
    fun requireAuthorization(exchange: HttpExchange) {
        val expected = expectedToken ?: return
        val authorization = exchange.requestHeaders.getFirst("Authorization")

        if (authorization == null || !authorization.startsWith("Bearer ", ignoreCase = true)) {
            throw ApiException(401, "UNAUTHORIZED", "A Bearer token is required")
        }

        val suppliedToken = authorization.substring(7)
        val matches = MessageDigest.isEqual(
            suppliedToken.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )

        if (!matches) {
            throw ApiException(403, "FORBIDDEN", "The Bearer token is invalid")
        }
    }
}
