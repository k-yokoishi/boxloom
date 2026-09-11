package dev.boxloom.server.core.api.players

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.JsonSupport
import dev.boxloom.server.core.PlayerOperations
import dev.boxloom.server.core.PlayerPosition
import dev.boxloom.server.core.TeleportPlayerRequest
import dev.boxloom.server.core.http.HttpExchangeSupport
import dev.boxloom.server.core.http.MinecraftOperationRunner
import dev.boxloom.server.core.http.RouteParameters
import dev.boxloom.server.core.http.Router
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.regex.Pattern

internal class PlayerRoutes(
    private val minecraft: PlayerOperations,
    private val operations: MinecraftOperationRunner,
) {
    fun register(router: Router) {
        router.get("/players") { exchange, _ -> list(exchange) }
        router.get("/players/{username}/position", ::position)
        router.post("/players/{username}/teleport", ::teleport)
    }

    private fun list(exchange: HttpExchange) {
        val players = operations.await(minecraft.players())
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

        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    private fun position(exchange: HttpExchange, parameters: RouteParameters) {
        val username = requireUsername(parameters)
        val playerPosition = operations.await(minecraft.playerPosition(username))
        sendPlayerPosition(exchange, playerPosition)
    }

    private fun teleport(exchange: HttpExchange, parameters: RouteParameters) {
        val username = requireUsername(parameters)
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, TELEPORT_PLAYER_FIELDS)

        val request = TeleportPlayerRequest(
            JsonSupport.requireFiniteDouble(objectValue, "x"),
            JsonSupport.requireFiniteDouble(objectValue, "y"),
            JsonSupport.requireFiniteDouble(objectValue, "z"),
            JsonSupport.optionalString(objectValue, "dimension"),
            JsonSupport.optionalFiniteDouble(objectValue, "yaw"),
            JsonSupport.optionalFiniteDouble(objectValue, "pitch"),
        )
        val playerPosition = operations.await(minecraft.teleportPlayer(username, request))
        sendPlayerPosition(exchange, playerPosition)
    }

    private fun requireUsername(parameters: RouteParameters): String {
        val username = HttpExchangeSupport.decodePathSegment(parameters["username"], "username")
        if (!USERNAME.matcher(username).matches()) {
            throw JsonSupport.invalid(
                "The username must contain 3-16 letters, numbers, or underscores",
            )
        }
        return username
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

        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    companion object {
        private val USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$")
        private val TELEPORT_PLAYER_FIELDS =
            setOf("x", "y", "z", "dimension", "yaw", "pitch")
    }
}
