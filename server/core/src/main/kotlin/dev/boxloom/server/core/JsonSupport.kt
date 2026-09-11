package dev.boxloom.server.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    fun requireFiniteDouble(objectValue: JsonObject, name: String): Double {
        val value = objectValue[name] as? JsonPrimitive
        val result = value?.takeUnless(JsonPrimitive::isString)?.content?.toDoubleOrNull()

        if (result == null || !result.isFinite()) {
            throw invalid("Field '$name' must be a finite number")
        }

        return result
    }

    fun optionalString(objectValue: JsonObject, name: String): String? {
        if (name !in objectValue) return null
        return requireString(objectValue, name)
    }

    fun optionalFiniteDouble(objectValue: JsonObject, name: String): Double? {
        if (name !in objectValue) return null
        return requireFiniteDouble(objectValue, name)
    }

    fun optionalNbt(objectValue: JsonObject, name: String): NbtValue.Compound? {
        val value = objectValue[name] ?: return null
        val compound = value as? JsonObject
            ?: throw invalid("Field '$name' must be an object")

        return parseNbtCompound(compound, depth = 0)
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

    private fun parseNbtCompound(value: JsonObject, depth: Int): NbtValue.Compound {
        requireNbtDepth(depth)
        return NbtValue.Compound(
            value.mapValues { (_, element) -> parseNbtValue(element, depth + 1) },
        )
    }

    private fun parseNbtValue(value: JsonElement, depth: Int): NbtValue {
        requireNbtDepth(depth)
        return when (value) {
            is JsonObject -> parseNbtCompound(value, depth)
            is JsonArray -> NbtValue.ListValue(
                value.map { element -> parseNbtValue(element, depth + 1) },
            )
            JsonNull -> throw invalid("NBT values cannot be null")
            is JsonPrimitive -> parseNbtPrimitive(value)
        }
    }

    private fun parseNbtPrimitive(value: JsonPrimitive): NbtValue {
        if (value.isString) {
            return NbtValue.StringValue(value.content)
        }

        return when (value.content) {
            "true" -> NbtValue.BooleanValue(true)
            "false" -> NbtValue.BooleanValue(false)
            else -> parseNbtNumber(value.content)
        }
    }

    private fun parseNbtNumber(token: String): NbtValue {
        if (integerToken.matches(token)) {
            val value = token.toLongOrNull()
                ?: throw invalid("NBT integers must fit in a signed 64-bit integer")
            return if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                NbtValue.IntValue(value.toInt())
            } else {
                NbtValue.LongValue(value)
            }
        }

        val value = token.toDoubleOrNull()
        if (value == null || !value.isFinite()) {
            throw invalid("NBT floating-point values must be finite numbers")
        }
        return NbtValue.DoubleValue(value)
    }

    private fun requireNbtDepth(depth: Int) {
        if (depth > MAX_NBT_DEPTH) {
            throw invalid("Field 'nbt' exceeds the maximum nesting depth of $MAX_NBT_DEPTH")
        }
    }

    private const val MAX_NBT_DEPTH = 128
}
