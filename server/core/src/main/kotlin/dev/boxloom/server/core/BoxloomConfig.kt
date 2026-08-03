package dev.boxloom.server.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

data class BoxloomConfig(
    val bindAddress: InetAddress,
    val port: Int,
    val authToken: String?,
    val requestTimeout: Duration,
) {
    val displayHost: String
        get() = bindAddress.hostAddress

    val authenticationEnabled: Boolean
        get() = authToken != null

    companion object {
        private const val DEFAULT_BIND_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 28_886
        private const val DEFAULT_TIMEOUT_MS = 5_000L
        private const val MAX_AUTH_TOKEN_LENGTH = 4_096
        private val CONFIG_FIELDS = setOf(
            "bindHost",
            "port",
            "authToken",
            "requestTimeoutMs",
        )
        private val CONFIG_PARSER = Json {
            isLenient = false
            ignoreUnknownKeys = false
        }
        private val DEFAULT_CONFIG = """
            {
              "bindHost": "$DEFAULT_BIND_HOST",
              "port": $DEFAULT_PORT,
              "authToken": "",
              "requestTimeoutMs": $DEFAULT_TIMEOUT_MS
            }
        """.trimIndent() + "\n"

        fun load(configPath: Path, environment: Map<String, String>): BoxloomConfig {
            val fileConfig = readConfigFile(configPath)
            val host = environment.nonBlank("BOXLOOM_BIND_HOST")
                ?: fileConfig.string("bindHost")
                ?: DEFAULT_BIND_HOST
            val port = parseLong(
                "port",
                environment.nonBlank("BOXLOOM_PORT")
                    ?: fileConfig.integer("port")
                    ?: DEFAULT_PORT.toString(),
                1,
                65_535,
            ).toInt()
            val authToken = (
                environment.nonBlank("BOXLOOM_AUTH_TOKEN")
                    ?: fileConfig.string("authToken")
                )?.takeUnless(String::isBlank)
            check(authToken == null || authToken.length <= MAX_AUTH_TOKEN_LENGTH) {
                "authToken must contain at most $MAX_AUTH_TOKEN_LENGTH characters"
            }

            val timeoutMs = parseLong(
                "requestTimeoutMs",
                environment.nonBlank("BOXLOOM_REQUEST_TIMEOUT_MS")
                    ?: fileConfig.integer("requestTimeoutMs")
                    ?: DEFAULT_TIMEOUT_MS.toString(),
                100,
                60_000,
            )

            try {
                val config = BoxloomConfig(
                    InetAddress.getByName(host),
                    port,
                    authToken,
                    Duration.ofMillis(timeoutMs),
                )

                check(config.authenticationEnabled || config.bindAddress.isLoopbackAddress) {
                    "authToken is required when bindHost is not a loopback address"
                }

                return config
            } catch (exception: UnknownHostException) {
                throw IllegalStateException(
                    "bindHost cannot be resolved: $host",
                    exception,
                )
            }
        }

        private fun readConfigFile(configPath: Path): JsonObject {
            try {
                if (Files.notExists(configPath)) {
                    configPath.parent?.let { Files.createDirectories(it) }
                    Files.writeString(
                        configPath,
                        DEFAULT_CONFIG,
                        StandardOpenOption.CREATE_NEW,
                    )
                }

                val source = Files.readString(configPath)
                val element = CONFIG_PARSER.parseToJsonElement(source)
                val objectValue = element as? JsonObject
                    ?: throw IllegalStateException("boxloom config must be a JSON object: $configPath")
                objectValue.keys.firstOrNull { it !in CONFIG_FIELDS }?.let { field ->
                    throw IllegalStateException("Unknown boxloom config field '$field': $configPath")
                }
                return objectValue
            } catch (exception: SerializationException) {
                throw IllegalStateException("boxloom config must contain valid JSON: $configPath", exception)
            } catch (exception: IllegalArgumentException) {
                throw IllegalStateException("boxloom config must contain valid JSON: $configPath", exception)
            } catch (exception: IOException) {
                throw IllegalStateException("Could not read or create boxloom config: $configPath", exception)
            }
        }

        private fun Map<String, String>.nonBlank(name: String): String? =
            get(name)?.trim()?.takeUnless(String::isBlank)

        private fun JsonObject.string(name: String): String? {
            val value = get(name) ?: return null
            val primitive = value as? JsonPrimitive

            check(primitive != null && primitive.isString) {
                "boxloom config field '$name' must be a string"
            }

            return primitive.content.trim()
        }

        private fun JsonObject.integer(name: String): String? {
            val value = get(name) ?: return null
            val primitive = value as? JsonPrimitive
            val token = primitive?.takeUnless(JsonPrimitive::isString)?.content

            check(token != null && token.toLongOrNull() != null) {
                "boxloom config field '$name' must be an integer"
            }

            return token
        }

        private fun parseLong(
            name: String,
            value: String,
            minimum: Long,
            maximum: Long,
        ): Long {
            val parsed = value.toLongOrNull()
                ?: throw IllegalStateException("$name must be an integer")

            check(parsed in minimum..maximum) {
                "$name must be between $minimum and $maximum"
            }

            return parsed
        }
    }
}
