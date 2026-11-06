package dev.boxloom.server.core.api.events

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.BoxloomEventBroker
import dev.boxloom.server.core.BlockActionEvent
import dev.boxloom.server.core.ChatMessageEvent
import dev.boxloom.server.core.EventCursorExpiredException
import dev.boxloom.server.core.InvalidEventCursorException
import dev.boxloom.server.core.PlayerDeathEvent
import dev.boxloom.server.core.PlayerActionEvent
import dev.boxloom.server.core.http.Router
import dev.boxloom.server.core.http.SseWriter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Duration

internal class EventRoutes(
    private val events: BoxloomEventBroker,
) {
    fun register(router: Router) {
        router.get("/events") { exchange, _ -> stream(exchange) }
    }

    private fun stream(exchange: HttpExchange) {
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

        val writer = SseWriter(exchange.responseBody)
        var currentCursor = cursor

        try {
            writer.retry(EVENT_RETRY_MS)
            writer.event(
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
                    writer.event(
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
                    writer.heartbeat()
                    continue
                }

                available.forEach { event ->
                    writer.event(
                        event.type,
                        event.id,
                        buildJsonObject {
                            put("type", event.type)
                            put("id", event.id)
                            put("timestamp", event.timestamp.toString())
                            when (event) {
                                is ChatMessageEvent -> put("message", event.message)
                                is PlayerDeathEvent -> put("message", event.message)
                                else -> Unit
                            }
                            put("player", buildJsonObject {
                                put("username", event.username)
                                put("uuid", event.uuid)
                            })
                            if (event is PlayerDeathEvent) {
                                put("dimension", event.dimension)
                                put("x", event.x)
                                put("y", event.y)
                                put("z", event.z)
                            }
                            if (event is PlayerActionEvent) {
                                val context = event.context
                                put("dimension", context.dimension)
                                put("x", context.x)
                                put("y", context.y)
                                put("z", context.z)
                                put("hand", context.hand)
                                put("item", context.item)
                            }
                            if (event is BlockActionEvent) {
                                val target = event.target
                                put("block", target.block)
                                put("block_x", target.x)
                                put("block_y", target.y)
                                put("block_z", target.z)
                                put("face", target.face)
                            }
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
                writer.event(
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

    companion object {
        private const val EVENT_RETRY_MS = 1_000L
        private val EVENT_HEARTBEAT_INTERVAL = Duration.ofSeconds(5)
        private val LOGGER = System.getLogger("boxloom-http")
    }
}
