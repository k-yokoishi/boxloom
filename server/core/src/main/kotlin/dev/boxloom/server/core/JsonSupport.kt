package dev.boxloom.server.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JsonSupport {
    private val parser = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }
    private val integerToken = Regex("-?(0|[1-9][0-9]*)")

    fun parseObject(source: String): JsonObject {
        val element = try {
            parser.parseToJsonElement(source)
        } catch (exception: SerializationException) {
            throw invalid("The request body must be valid JSON")
        } catch (exception: IllegalArgumentException) {
            throw invalid("The request body must be valid JSON")
        }

        return element as? JsonObject
            ?: throw invalid("The request body must be a JSON object")
    }

    fun requireString(objectValue: JsonObject, name: String): String {
        val value = objectValue[name] as? JsonPrimitive

        if (value == null || !value.isString || value.content.isBlank()) {
            throw invalid("Field '$name' must be a non-empty string")
        }

        return value.content
    }

    fun requireInteger(objectValue: JsonObject, name: String): Int {
        val value = objectValue[name] as? JsonPrimitive
        val token = value?.takeUnless(JsonPrimitive::isString)?.content

        if (token == null || !integerToken.matches(token)) {
            throw invalid("Field '$name' must be an integer")
        }

        return token.toIntOrNull()
            ?: throw invalid("Field '$name' is outside the 32-bit integer range")
    }

    fun requireOnlyFields(objectValue: JsonObject, allowed: Set<String>) {
        objectValue.keys.firstOrNull { it !in allowed }?.let { field ->
            throw invalid("Unknown field '$field'")
        }
    }

    fun error(code: String, message: String): String = buildJsonObject {
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    fun invalid(message: String): ApiException =
        ApiException(400, "INVALID_REQUEST", message)
}
