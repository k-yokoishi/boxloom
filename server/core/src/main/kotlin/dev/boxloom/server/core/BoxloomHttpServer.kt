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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

class BoxloomHttpServer(
    private val config: BoxloomConfig,
    private val minecraft: MinecraftOperations,
    private val events: BoxloomEventBroker,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0)
    private val executor: ExecutorService =
        Executors.newVirtualThreadPerTaskExecutor()

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
            "boxloom HTTP server listening on http://${config.displayHost}:$boundPort",
        )
    }

    override fun close() {
        events.close()
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
        val playerTeleport = PLAYER_TELEPORT_PATH.matcher(path)

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

            playerTeleport.matches() -> {
                requireMethod(exchange, "POST")
                handlePlayerTeleport(exchange, decodePathSegment(playerTeleport.group(1)))
            }

            path == SET_BLOCK_PATH -> {
                requireMethod(exchange, "POST")
                handleSetBlock(exchange)
            }

            path == SUMMON_PATH -> {
                requireMethod(exchange, "POST")
                handleSummon(exchange)
            }

            path == EVENTS_PATH -> {
                requireMethod(exchange, "GET")
                handleEvents(exchange)
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
        requireUsername(username)

        val position = await(minecraft.playerPosition(username))
        sendPlayerPosition(exchange, position)
    }

    private fun handlePlayerTeleport(exchange: HttpExchange, username: String) {
        requireUsername(username)
        requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, TELEPORT_PLAYER_FIELDS)

        val request = TeleportPlayerRequest(
            JsonSupport.requireFiniteDouble(objectValue, "x"),
            JsonSupport.requireFiniteDouble(objectValue, "y"),
            JsonSupport.requireFiniteDouble(objectValue, "z"),
            JsonSupport.optionalString(objectValue, "dimension"),
            JsonSupport.optionalFiniteDouble(objectValue, "yaw"),
            JsonSupport.optionalFiniteDouble(objectValue, "pitch"),
        )
        val position = await(minecraft.teleportPlayer(username, request))
        sendPlayerPosition(exchange, position)
    }

    private fun sendPlayerPosition(exchange: HttpExchange, position: PlayerPosition) {
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

    private fun requireUsername(username: String) {
        if (!USERNAME.matcher(username).matches()) {
            throw JsonSupport.invalid(
                "The username must contain 3-16 letters, numbers, or underscores",
            )
        }
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

    private fun handleSummon(exchange: HttpExchange) {
        requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, SUMMON_FIELDS)

        val request = SummonRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            JsonSupport.requireString(objectValue, "entity"),
            JsonSupport.requireFiniteDouble(objectValue, "x"),
            JsonSupport.requireFiniteDouble(objectValue, "y"),
            JsonSupport.requireFiniteDouble(objectValue, "z"),
            JsonSupport.optionalNbt(objectValue, "nbt"),
        )
        val result = await(minecraft.summon(request))
        val response = buildJsonObject {
            put("uuid", result.uuid)
            put("entity", result.entity)
            put("dimension", result.dimension)
            put("x", result.x)
            put("y", result.y)
            put("z", result.z)
        }.toString()

        sendJson(exchange, 200, response)
    }

    private fun handleEvents(exchange: HttpExchange) {
        requireEventStreamAccept(exchange)
        val cursor = try {
            events.openCursor(exchange.requestHeaders.getFirst("Last-Event-ID"))
        } catch (exception: InvalidEventCursorException) {
            throw ApiException(400, "INVALID_EVENT_CURSOR", exception.message.orEmpty())
        } catch (exception: EventCursorExpiredException) {
            throw ApiException(410, "EVENT_CURSOR_EXPIRED", exception.message.orEmpty())
        }

        exchange.responseHeaders.apply {
            set("Content-Type", "text/event-stream")
            set("Cache-Control", "no-cache, no-transform")
            set("X-Content-Type-Options", "nosniff")
        }
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody
        var currentCursor = cursor

        try {
            writeEventRetry(output)
            writeSseEvent(
                output,
                "stream.ready",
                currentCursor.toString(),
                buildJsonObject {
                    put("type", "stream.ready")
                    put("cursor", currentCursor.toString())
                }.toString(),
            )

            while (true) {
                val available = try {
                    events.awaitAfter(currentCursor, EVENT_HEARTBEAT_INTERVAL)
                } catch (exception: EventCursorExpiredException) {
                    writeSseEvent(
                        output,
                        "stream.reset",
                        null,
                        buildJsonObject {
                            put("type", "stream.reset")
                            put("code", "EVENT_CURSOR_EXPIRED")
                            put("message", exception.message.orEmpty())
                        }.toString(),
                    )
                    return
                }

                if (available == null) return
                if (available.isEmpty()) {
                    writeEventHeartbeat(output)
                    continue
                }

                available.forEach { event ->
                    writeSseEvent(
                        output,
                        "chat.message",
                        event.id,
                        buildJsonObject {
                            put("type", "chat.message")
                            put("id", event.id)
                            put("timestamp", event.timestamp.toString())
                            put("message", event.message)
                            put("player", buildJsonObject {
                                put("username", event.username)
                                put("uuid", event.uuid)
                            })
                        }.toString(),
                    )
                    currentCursor = event.cursor
                }
            }
        } catch (exception: IOException) {
            LOGGER.log(System.Logger.Level.DEBUG, "boxloom event stream disconnected", exception)
        } catch (throwable: Throwable) {
            LOGGER.log(System.Logger.Level.ERROR, "boxloom event stream failed", throwable)
            try {
                writeSseEvent(
                    output,
                    "error",
                    null,
                    buildJsonObject {
                        put("type", "error")
                        put("code", "INTERNAL_ERROR")
                        put("message", "The boxloom event stream failed")
                    }.toString(),
                )
            } catch (_: IOException) {
                // The connection is already unusable.
            }
        }
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
        val mediaType = contentType?.substringBefore(';')?.trim()

        if (!mediaType.equals("application/json", ignoreCase = true)) {
            throw ApiException(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json",
            )
        }
    }

    private fun requireEventStreamAccept(exchange: HttpExchange) {
        val accept = exchange.requestHeaders.getFirst("Accept") ?: return
        val accepted = accept.split(',').any { mediaRange ->
            val mediaType = mediaRange.substringBefore(';').trim()
            mediaType.equals("text/event-stream", ignoreCase = true) ||
                mediaType.equals("text/*", ignoreCase = true) ||
                mediaType == "*/*"
        }

        if (!accepted) {
            throw ApiException(
                406,
                "NOT_ACCEPTABLE",
                "Accept must allow text/event-stream",
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

    private fun writeEventRetry(output: java.io.OutputStream) {
        output.write("retry: $EVENT_RETRY_MS\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun writeEventHeartbeat(output: java.io.OutputStream) {
        output.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun writeSseEvent(
        output: java.io.OutputStream,
        eventType: String,
        id: String?,
        data: String,
    ) {
        val frame = buildString {
            append("event: ").append(eventType).append('\n')
            id?.let { append("id: ").append(it).append('\n') }
            data.lineSequence().forEach { line ->
                append("data: ").append(line).append('\n')
            }
            append('\n')
        }
        output.write(frame.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    companion object {
        private val LOGGER = System.getLogger("boxloom-http")
        private val PLAYER_POSITION_PATH = Pattern.compile("^/v1/players/([^/]+)/position$")
        private val PLAYER_TELEPORT_PATH = Pattern.compile("^/v1/players/([^/]+)/teleport$")
        private val USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$")
        private const val SAY_PATH = "/v1/chat/messages"
        private const val PLAYERS_PATH = "/v1/players"
        private const val SET_BLOCK_PATH = "/v1/world/blocks"
        private const val SUMMON_PATH = "/v1/world/entities"
        private const val EVENTS_PATH = "/v1/events"
        private const val MAX_REQUEST_BODY_BYTES = 16 * 1_024
        private const val MAX_CHAT_MESSAGE_LENGTH = 256
        private const val EVENT_RETRY_MS = 1_000
        private val EVENT_HEARTBEAT_INTERVAL = java.time.Duration.ofSeconds(5)
        private val SAY_FIELDS = setOf("message")
        private val TELEPORT_PLAYER_FIELDS = setOf("x", "y", "z", "dimension", "yaw", "pitch")
        private val SET_BLOCK_FIELDS = setOf("dimension", "x", "y", "z", "block")
        private val SUMMON_FIELDS = setOf("dimension", "entity", "x", "y", "z", "nbt")
    }
}
