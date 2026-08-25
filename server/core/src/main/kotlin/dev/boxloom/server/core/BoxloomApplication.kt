package dev.boxloom.server.core

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.send
import io.ktor.sse.ServerSentEvent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun Application.configureBoxloom(
    config: BoxloomConfig,
    minecraft: MinecraftOperations,
    events: BoxloomEventBroker,
) {
    install(ContentNegotiation) {
        json(PROTOCOL_JSON)
    }
    install(SSE)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message.orEmpty())
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondError(
                413,
                "REQUEST_TOO_LARGE",
                "The JSON request body exceeds 16 KiB",
            )
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondError(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json",
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondError(400, "INVALID_REQUEST", "The request body must be valid JSON")
        }
        exception<BadRequestException> { call, cause ->
            call.respondError(400, "INVALID_REQUEST", cause.message ?: "The request is invalid")
        }
        exception<ClosedWriteChannelException> { _, _ ->
            // A disconnected SSE client is a normal stream termination.
        }
        exception<Throwable> { call, cause ->
            LOGGER.log(System.Logger.Level.ERROR, "Unhandled boxloom HTTP error", cause)
            call.respondError(
                500,
                "INTERNAL_ERROR",
                "boxloom could not complete the request",
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(404, "ROUTE_NOT_FOUND", "No API route matches this request")
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respondError(401, "UNAUTHORIZED", "A Bearer token is required")
        }
    }
    install(Authentication) {
        bearer(AUTH_PROVIDER) {
            realm = "boxloom"
            skipWhen { config.authToken == null }
            authenticate { credential ->
                if (tokensMatch(credential.token, config.authToken)) {
                    UserIdPrincipal("boxloom-sdk")
                } else {
                    throw ApiException(403, "FORBIDDEN", "The Bearer token is invalid")
                }
            }
        }
    }

    routing {
        authenticate(AUTH_PROVIDER) {
            boxloomRoutes(config, minecraft, events)
        }
    }
}

private fun Route.boxloomRoutes(
    config: BoxloomConfig,
    minecraft: MinecraftOperations,
    events: BoxloomEventBroker,
) {
    route(SAY_PATH) {
        install(RequestBodyLimit) {
            bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() }
        }
        post {
            call.requireJsonContentType()
            val request = call.receive<SayRequest>()
            if (request.message.isBlank()) {
                throw invalid("Field 'message' must be a non-empty string")
            }
            if (request.message.length > MAX_CHAT_MESSAGE_LENGTH) {
                throw invalid(
                    "Field 'message' must contain at most $MAX_CHAT_MESSAGE_LENGTH characters",
                )
            }
            call.respondJson(withMinecraftTimeout(config) { minecraft.say(request) })
        }
        methodNotAllowed(HttpMethod.Post)
    }

    route(PLAYERS_PATH) {
        get {
            call.respondJson(
                PlayersResponse(withMinecraftTimeout(config) { minecraft.players() }),
            )
        }
        methodNotAllowed(HttpMethod.Get)
    }

    route(PLAYER_POSITION_PATH) {
        get {
            val username = call.parameters["username"]
                ?: throw invalid("The username path segment is missing")
            if (!USERNAME.matches(username)) {
                throw invalid(
                    "The username must contain 3-16 letters, numbers, or underscores",
                )
            }
            call.respondJson(
                withMinecraftTimeout(config) { minecraft.playerPosition(username) },
            )
        }
        methodNotAllowed(HttpMethod.Get)
    }

    route(SET_BLOCK_PATH) {
        install(RequestBodyLimit) {
            bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() }
        }
        post {
            call.requireJsonContentType()
            val request = call.receive<SetBlockRequest>()
            if (request.dimension.isBlank()) {
                throw invalid("Field 'dimension' must be a non-empty string")
            }
            if (request.block.isBlank()) {
                throw invalid("Field 'block' must be a non-empty string")
            }
            call.respondJson(withMinecraftTimeout(config) { minecraft.setBlock(request) })
        }
        methodNotAllowed(HttpMethod.Post)
    }

    route(SUMMON_PATH) {
        install(RequestBodyLimit) {
            bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() }
        }
        post {
            call.requireJsonContentType()
            val body = call.receive<SummonRequestBody>()
            if (body.dimension.isBlank()) {
                throw invalid("Field 'dimension' must be a non-empty string")
            }
            if (body.entity.isBlank()) {
                throw invalid("Field 'entity' must be a non-empty string")
            }
            if (!body.x.isFinite()) throw invalid("Field 'x' must be a finite number")
            if (!body.y.isFinite()) throw invalid("Field 'y' must be a finite number")
            if (!body.z.isFinite()) throw invalid("Field 'z' must be a finite number")

            val request = SummonRequest(
                body.dimension,
                body.entity,
                body.x,
                body.y,
                body.z,
                body.nbt?.let { parseNbtCompound(it, depth = 0) },
            )
            call.respondJson(withMinecraftTimeout(config) { minecraft.summon(request) })
        }
        methodNotAllowed(HttpMethod.Post)
    }

    route(EVENTS_PATH) {
        get {
            call.requireEventStreamAccept()
            val cursor = try {
                events.openCursor(call.request.header("Last-Event-ID"))
            } catch (exception: InvalidEventCursorException) {
                throw ApiException(400, "INVALID_EVENT_CURSOR", exception.message.orEmpty())
            } catch (exception: EventCursorExpiredException) {
                throw ApiException(410, "EVENT_CURSOR_EXPIRED", exception.message.orEmpty())
            }
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-transform")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respond(
                SSEServerContent(call, handle = {
                    streamEvents(events, cursor)
                }),
            )
        }
        methodNotAllowed(HttpMethod.Get)
    }
}

private suspend fun ServerSSESession.streamEvents(
    events: BoxloomEventBroker,
    initialCursor: EventCursor,
) {
    heartbeat {
        period = EVENT_HEARTBEAT_INTERVAL_SECONDS.seconds
        event = ServerSentEvent(comments = "keepalive")
    }
    send(retry = EVENT_RETRY_MS)

    var currentCursor = initialCursor
    send(
        data = PROTOCOL_JSON.encodeToString(
            StreamReadyPayload("stream.ready", currentCursor.toString()),
        ),
        event = "stream.ready",
        id = currentCursor.toString(),
    )

    try {
        while (true) {
            val available = try {
                events.awaitAfter(currentCursor, EVENT_WAIT_INTERVAL)
            } catch (exception: EventCursorExpiredException) {
                send(
                    data = PROTOCOL_JSON.encodeToString(
                        StreamFailurePayload(
                            "stream.reset",
                            "EVENT_CURSOR_EXPIRED",
                            exception.message.orEmpty(),
                        ),
                    ),
                    event = "stream.reset",
                )
                return
            }

            if (available == null) return
            available.forEach { event ->
                send(
                    data = PROTOCOL_JSON.encodeToString(
                        ChatMessagePayload(
                            "chat.message",
                            event.id,
                            event.timestamp.toString(),
                            event.message,
                            Player(event.username, event.uuid),
                        ),
                    ),
                    event = "chat.message",
                    id = event.id,
                )
                currentCursor = event.cursor
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (throwable: Throwable) {
        LOGGER.log(System.Logger.Level.ERROR, "boxloom event stream failed", throwable)
        try {
            send(
                data = PROTOCOL_JSON.encodeToString(
                    StreamFailurePayload(
                        "error",
                        "INTERNAL_ERROR",
                        "The boxloom event stream failed",
                    ),
                ),
                event = "error",
            )
        } catch (_: Throwable) {
            // The connection is already unusable.
        }
    }
}

private suspend fun <T> withMinecraftTimeout(
    config: BoxloomConfig,
    operation: suspend () -> T,
): T {
    try {
        return withTimeout(config.requestTimeout.toMillis()) {
            operation()
        }
    } catch (exception: TimeoutCancellationException) {
        throw ApiException(504, "TIMEOUT", "The Minecraft server did not respond in time")
    } catch (exception: ApiException) {
        throw exception
    } catch (exception: CancellationException) {
        throw exception
    } catch (throwable: Throwable) {
        LOGGER.log(System.Logger.Level.ERROR, "Minecraft server-thread operation failed", throwable)
        throw ApiException(500, "INTERNAL_ERROR", "The Minecraft operation failed")
    }
}

private fun Route.methodNotAllowed(expected: HttpMethod) {
    handle {
        call.response.header(HttpHeaders.Allow, expected.value)
        throw ApiException(
            405,
            "METHOD_NOT_ALLOWED",
            "This route requires ${expected.value}",
        )
    }
}

private fun ApplicationCall.requireJsonContentType() {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "Content-Type must be application/json",
        )
    }
}

private fun ApplicationCall.requireEventStreamAccept() {
    val accept = request.header(HttpHeaders.Accept) ?: return
    val accepted = accept.split(',').any { mediaRange ->
        val mediaType = mediaRange.substringBefore(';').trim()
        mediaType.equals("text/event-stream", ignoreCase = true) ||
            mediaType.equals("text/*", ignoreCase = true) ||
            mediaType == "*/*"
    }
    if (!accepted) {
        throw ApiException(406, "NOT_ACCEPTABLE", "Accept must allow text/event-stream")
    }
}

private suspend fun ApplicationCall.respondJson(value: Any) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    respond(value)
}

private suspend fun ApplicationCall.respondError(status: Int, code: String, message: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    respondText(
        text = PROTOCOL_JSON.encodeToString(ErrorResponse(ApiErrorBody(code, message))),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.fromValue(status),
    )
}

private fun tokensMatch(supplied: String, expected: String?): Boolean {
    if (expected == null) return true
    return MessageDigest.isEqual(supplied.toByteArray(), expected.toByteArray())
}

private fun invalid(message: String): ApiException =
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
    if (value.isString) return NbtValue.StringValue(value.content)

    return when (value.content) {
        "true" -> NbtValue.BooleanValue(true)
        "false" -> NbtValue.BooleanValue(false)
        else -> parseNbtNumber(value.content)
    }
}

private fun parseNbtNumber(token: String): NbtValue {
    if (NBT_INTEGER_TOKEN.matches(token)) {
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

private val PROTOCOL_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    isLenient = false
}
private val LOGGER = System.getLogger("boxloom-http")
private val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
private val NBT_INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
private val EVENT_WAIT_INTERVAL = Duration.ofSeconds(5)
private const val AUTH_PROVIDER = "boxloom-bearer"
private const val SAY_PATH = "/v1/chat/messages"
private const val PLAYERS_PATH = "/v1/players"
private const val PLAYER_POSITION_PATH = "/v1/players/{username}/position"
private const val SET_BLOCK_PATH = "/v1/world/blocks"
private const val SUMMON_PATH = "/v1/world/entities"
private const val EVENTS_PATH = "/v1/events"
private const val MAX_REQUEST_BODY_BYTES = 16 * 1_024
private const val MAX_CHAT_MESSAGE_LENGTH = 256
private const val MAX_NBT_DEPTH = 128
private const val EVENT_RETRY_MS = 1_000L
private const val EVENT_HEARTBEAT_INTERVAL_SECONDS = 5
