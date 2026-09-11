package dev.boxloom.server.core.http

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.JsonSupport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object HttpExchangeSupport {
    fun requireJsonContentType(exchange: HttpExchange) {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")
        val mediaType = contentType?.substringBefore(';')?.trim()

        if (!mediaType.equals("application/json", ignoreCase = true)) {
            throw ApiException(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json",
            )
        }
    }

    fun readRequestBody(exchange: HttpExchange): String {
        try {
            exchange.requestBody.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(4_096)
                    var total = 0

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read

                        if (total > MAX_REQUEST_BODY_BYTES) {
                            throw ApiException(
                                413,
                                "REQUEST_TOO_LARGE",
                                "The JSON request body exceeds 16 KiB",
                            )
                        }

                        output.write(buffer, 0, read)
                    }

                    return output.toString(StandardCharsets.UTF_8)
                }
            }
        } catch (exception: IOException) {
            throw ApiException(400, "INVALID_REQUEST", "The request body could not be read")
        }
    }

    fun decodePathSegment(value: String, name: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (exception: IllegalArgumentException) {
        throw JsonSupport.invalid("The $name path segment is not valid URL encoding")
    }

    fun sendError(exchange: HttpExchange, status: Int, code: String, message: String) {
        sendJson(exchange, status, JsonSupport.error(code, message))
    }

    fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.apply {
            set("Content-Type", "application/json; charset=utf-8")
            set("Cache-Control", "no-store")
            set("X-Content-Type-Options", "nosniff")
        }

        try {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } catch (exception: IOException) {
            LOGGER.log(System.Logger.Level.DEBUG, "Could not write boxloom HTTP response", exception)
        }
    }

    private const val MAX_REQUEST_BODY_BYTES = 16 * 1_024
    private val LOGGER = System.getLogger("boxloom-http")
}
