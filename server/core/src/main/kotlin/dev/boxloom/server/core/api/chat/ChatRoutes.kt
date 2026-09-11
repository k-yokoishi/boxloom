package dev.boxloom.server.core.api.chat

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ChatOperations
import dev.boxloom.server.core.JsonSupport
import dev.boxloom.server.core.SayRequest
import dev.boxloom.server.core.http.HttpExchangeSupport
import dev.boxloom.server.core.http.MinecraftOperationRunner
import dev.boxloom.server.core.http.Router
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ChatRoutes(
    private val minecraft: ChatOperations,
    private val operations: MinecraftOperationRunner,
) {
    fun register(router: Router) {
        router.post("/chat/messages") { exchange, _ -> say(exchange) }
    }

    private fun say(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, SAY_FIELDS)
        val message = JsonSupport.requireString(objectValue, "message")

        if (message.length > MAX_CHAT_MESSAGE_LENGTH) {
            throw JsonSupport.invalid(
                "Field 'message' must contain at most $MAX_CHAT_MESSAGE_LENGTH characters",
            )
        }

        val result = operations.await(minecraft.say(SayRequest(message)))
        val response = buildJsonObject {
            put("message", result.message)
            put("recipients", result.recipients)
        }.toString()

        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    companion object {
        private const val MAX_CHAT_MESSAGE_LENGTH = 256
        private val SAY_FIELDS = setOf("message")
    }
}
