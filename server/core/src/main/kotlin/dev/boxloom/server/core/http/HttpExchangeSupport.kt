package dev.boxloom.server.core.http

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.JsonSupport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object HttpExchangeSupport {
    fun queryParameters(exchange: HttpExchange): Map<String, String> {
        val query = exchange.requestURI.rawQuery
        if (query.isNullOrEmpty()) return emptyMap()

        val parameters = linkedMapOf<String, String>()
        query.split('&').forEach { field ->
            val separator = field.indexOf('=')
            val rawName = if (separator == -1) field else field.substring(0, separator)
            val rawValue = if (separator == -1) "" else field.substring(separator + 1)
            val name = decodeQueryComponent(rawName)
            val value = decodeQueryComponent(rawValue)

            if (name.isEmpty()) {
                throw JsonSupport.invalid("Query parameter names must not be empty")
            }
            if (parameters.put(name, value) != null) {
                throw JsonSupport.invalid("Query parameter '$name' must not be repeated")
            }
        }
        return parameters
    }

    fun requireOnlyQueryParameters(parameters: Map<String, String>, allowed: Set<String>) {
        parameters.keys.firstOrNull { it !in allowed }?.let { name ->
            throw JsonSupport.invalid("Unknown query parameter '$name'")
        }
    }

    fun requireQueryString(parameters: Map<String, String>, name: String): String {
        val value = parameters[name]
        if (value.isNullOrBlank()) {
            throw JsonSupport.invalid("Query parameter '$name' must be a non-empty string")
        }
        return value
    }

    fun requireQueryInteger(parameters: Map<String, String>, name: String): Int {
        val value = parameters[name]
        if (value == null || !INTEGER_TOKEN.matches(value)) {
            throw JsonSupport.invalid("Query parameter '$name' must be an integer")
        }
        return value.toIntOrNull()
            ?: throw JsonSupport.invalid(
                "Query parameter '$name' is outside the 32-bit integer range",
            )
    }

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

    private fun decodeQueryComponent(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (exception: IllegalArgumentException) {
        throw JsonSupport.invalid("The query string is not valid URL encoding")
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
    private val INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
    private val LOGGER = System.getLogger("boxloom-http")
}
