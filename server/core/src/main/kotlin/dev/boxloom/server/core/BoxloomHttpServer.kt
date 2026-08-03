package dev.boxloom.server.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class BoxloomHttpServer(
    private val config: BoxloomConfig,
    private val minecraft: MinecraftOperations,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(4, DaemonThreadFactory())

    init {
        server.createContext("/", ::handleSafely)
        server.executor = executor
    }

    internal val boundPort: Int
        get() = server.address.port

    fun start() {
        server.start()
        LOGGER.log(
            System.Logger.Level.INFO,
            "boxloom HTTP server listening on http://${config.displayHost}:${config.port}",
        )
    }

    override fun close() {
        server.stop(1)
        executor.shutdown()

        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }

    private fun handleSafely(exchange: HttpExchange) {
        try {
            requireAuthorization(exchange)
            route(exchange)
        } catch (exception: ApiException) {
            sendError(exchange, exception.status, exception.code, exception.message.orEmpty())
        } catch (throwable: Throwable) {
            LOGGER.log(System.Logger.Level.ERROR, "Unhandled boxloom HTTP error", throwable)
            sendError(exchange, 500, "INTERNAL_ERROR", "boxloom could not complete the request")
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        val playerPosition = PLAYER_POSITION_PATH.matcher(path)

        when {
            path == SAY_PATH -> {
                requireMethod(exchange, "POST")
                handleSay(exchange)
            }

            path == PLAYERS_PATH -> {
                requireMethod(exchange, "GET")
                handlePlayers(exchange)
            }

            playerPosition.matches() -> {
                requireMethod(exchange, "GET")
                handlePlayerPosition(exchange, decodePathSegment(playerPosition.group(1)))
            }

            path == SET_BLOCK_PATH -> {
                requireMethod(exchange, "POST")
                handleSetBlock(exchange)
            }

            else -> throw ApiException(
                404,
                "ROUTE_NOT_FOUND",
                "No API route matches this request",
            )
        }
    }

    private fun handleSay(exchange: HttpExchange) {
        requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, SAY_FIELDS)
        val message = JsonSupport.requireString(objectValue, "message")

        if (message.length > MAX_CHAT_MESSAGE_LENGTH) {
            throw JsonSupport.invalid(
                "Field 'message' must contain at most $MAX_CHAT_MESSAGE_LENGTH characters",
            )
        }

        val result = await(minecraft.say(SayRequest(message)))
        val response = buildJsonObject {
            put("message", result.message)
            put("recipients", result.recipients)
        }.toString()

        sendJson(exchange, 200, response)
    }

    private fun handlePlayers(exchange: HttpExchange) {
        val players = await(minecraft.players())
        val response = buildJsonObject {
            put("players", buildJsonArray {
                players.forEach { player ->
                    add(buildJsonObject {
                        put("username", player.username)
                        put("uuid", player.uuid)
                    })
                }
            })
        }.toString()

        sendJson(exchange, 200, response)
    }

    private fun handlePlayerPosition(exchange: HttpExchange, username: String) {
        if (!USERNAME.matcher(username).matches()) {
            throw JsonSupport.invalid("The username must contain 3-16 letters, numbers, or underscores")
        }

        val position = await(minecraft.playerPosition(username))
        val response = buildJsonObject {
            put("username", position.username)
            put("uuid", position.uuid)
            put("dimension", position.dimension)
            put("x", position.x)
            put("y", position.y)
            put("z", position.z)
            put("yaw", position.yaw)
            put("pitch", position.pitch)
        }.toString()

        sendJson(exchange, 200, response)
    }

    private fun handleSetBlock(exchange: HttpExchange) {
        requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, SET_BLOCK_FIELDS)

        val request = SetBlockRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            JsonSupport.requireInteger(objectValue, "x"),
            JsonSupport.requireInteger(objectValue, "y"),
            JsonSupport.requireInteger(objectValue, "z"),
            JsonSupport.requireString(objectValue, "block"),
        )
        val result = await(minecraft.setBlock(request))
        val response = buildJsonObject {
            put("changed", result.changed)
            put("dimension", result.dimension)
            put("x", result.x)
            put("y", result.y)
            put("z", result.z)
            put("block", result.block)
        }.toString()

        sendJson(exchange, 200, response)
    }

    private fun <T> await(future: CompletableFuture<T>): T {
        try {
            return future.get(config.requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            future.cancel(false)
            throw ApiException(504, "TIMEOUT", "The Minecraft server did not respond in time")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiException(500, "INTERNAL_ERROR", "The boxloom request was interrupted")
        } catch (exception: ExecutionException) {
            val cause = exception.cause

            if (cause is ApiException) {
                throw cause
            }

            LOGGER.log(
                System.Logger.Level.ERROR,
                "Minecraft server-thread operation failed",
                cause ?: exception,
            )
            throw ApiException(500, "INTERNAL_ERROR", "The Minecraft operation failed")
        }
    }

    private fun requireAuthorization(exchange: HttpExchange) {
        val expectedToken = config.authToken ?: return
        val authorization = exchange.requestHeaders.getFirst("Authorization")

        if (authorization == null || !authorization.startsWith("Bearer ", ignoreCase = true)) {
            throw ApiException(401, "UNAUTHORIZED", "A Bearer token is required")
        }

        val suppliedToken = authorization.substring(7)
        val matches = MessageDigest.isEqual(
            suppliedToken.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )

        if (!matches) {
            throw ApiException(403, "FORBIDDEN", "The Bearer token is invalid")
        }
    }

    private fun requireMethod(exchange: HttpExchange, expected: String) {
        if (exchange.requestMethod != expected) {
            exchange.responseHeaders.set("Allow", expected)
            throw ApiException(405, "METHOD_NOT_ALLOWED", "This route requires $expected")
        }
    }

    private fun requireJsonContentType(exchange: HttpExchange) {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")

        if (contentType == null || !contentType.startsWith("application/json", ignoreCase = true)) {
            throw ApiException(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json",
            )
        }
    }

    private fun readRequestBody(exchange: HttpExchange): String {
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

    private fun decodePathSegment(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (exception: IllegalArgumentException) {
        throw JsonSupport.invalid("The username path segment is not valid URL encoding")
    }

    private fun sendError(exchange: HttpExchange, status: Int, code: String, message: String) {
        sendJson(exchange, status, JsonSupport.error(code, message))
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
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

    private class DaemonThreadFactory : ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "boxloom-http-${sequence.incrementAndGet()}").apply {
                isDaemon = true
            }
    }

    companion object {
        private val LOGGER = System.getLogger("boxloom-http")
        private val PLAYER_POSITION_PATH = Pattern.compile("^/v1/players/([^/]+)/position$")
        private val USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$")
        private const val SAY_PATH = "/v1/chat/messages"
        private const val PLAYERS_PATH = "/v1/players"
        private const val SET_BLOCK_PATH = "/v1/world/blocks"
        private const val MAX_REQUEST_BODY_BYTES = 16 * 1_024
        private const val MAX_CHAT_MESSAGE_LENGTH = 256
        private val SAY_FIELDS = setOf("message")
        private val SET_BLOCK_FIELDS = setOf("dimension", "x", "y", "z", "block")
    }
}
