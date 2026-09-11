package dev.boxloom.server.core.api.world

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.JsonSupport
import dev.boxloom.server.core.SetBlockRequest
import dev.boxloom.server.core.SummonRequest
import dev.boxloom.server.core.WorldOperations
import dev.boxloom.server.core.http.HttpExchangeSupport
import dev.boxloom.server.core.http.MinecraftOperationRunner
import dev.boxloom.server.core.http.Router
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class WorldRoutes(
    private val minecraft: WorldOperations,
    private val operations: MinecraftOperationRunner,
) {
    fun register(router: Router) {
        router.post("/world/blocks") { exchange, _ -> setBlock(exchange) }
        router.post("/world/entities") { exchange, _ -> summon(exchange) }
    }

    private fun setBlock(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, SET_BLOCK_FIELDS)

        val request = SetBlockRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            JsonSupport.requireInteger(objectValue, "x"),
            JsonSupport.requireInteger(objectValue, "y"),
            JsonSupport.requireInteger(objectValue, "z"),
            JsonSupport.requireString(objectValue, "block"),
        )
        val result = operations.await(minecraft.setBlock(request))
        val response = buildJsonObject {
            put("changed", result.changed)
            put("dimension", result.dimension)
            put("x", result.x)
            put("y", result.y)
            put("z", result.z)
            put("block", result.block)
        }.toString()

        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    private fun summon(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, SUMMON_FIELDS)

        val request = SummonRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            JsonSupport.requireString(objectValue, "entity"),
            JsonSupport.requireFiniteDouble(objectValue, "x"),
            JsonSupport.requireFiniteDouble(objectValue, "y"),
            JsonSupport.requireFiniteDouble(objectValue, "z"),
            JsonSupport.optionalNbt(objectValue, "nbt"),
        )
        val result = operations.await(minecraft.summon(request))
        val response = buildJsonObject {
            put("uuid", result.uuid)
            put("entity", result.entity)
            put("dimension", result.dimension)
            put("x", result.x)
            put("y", result.y)
            put("z", result.z)
        }.toString()

        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    companion object {
        private val SET_BLOCK_FIELDS = setOf("dimension", "x", "y", "z", "block")
        private val SUMMON_FIELDS = setOf("dimension", "entity", "x", "y", "z", "nbt")
    }
}
