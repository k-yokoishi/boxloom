package dev.boxloom.server.core.api.world

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.EntityKillRange
import dev.boxloom.server.core.EntityQueryCenter
import dev.boxloom.server.core.EntitySort
import dev.boxloom.server.core.FillRequest
import dev.boxloom.server.core.FillMode
import dev.boxloom.server.core.GetBlockRequest
import dev.boxloom.server.core.GetBlockResult
import dev.boxloom.server.core.GetBlocksRequest
import dev.boxloom.server.core.GetEntitiesRequest
import dev.boxloom.server.core.JsonSupport
import dev.boxloom.server.core.KillEntitiesRequest
import dev.boxloom.server.core.KillEntityRequest
import dev.boxloom.server.core.KillResult
import dev.boxloom.server.core.SetBlockRequest
import dev.boxloom.server.core.SummonRequest
import dev.boxloom.server.core.WorldOperations
import dev.boxloom.server.core.http.HttpExchangeSupport
import dev.boxloom.server.core.http.MinecraftOperationRunner
import dev.boxloom.server.core.http.Router
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

internal class WorldRoutes(
    private val minecraft: WorldOperations,
    private val operations: MinecraftOperationRunner,
) {
    fun register(router: Router) {
        router.get("/world/blocks") { exchange, _ -> getBlock(exchange) }
        router.get("/world/blocks/region") { exchange, _ -> getBlocks(exchange) }
        router.post("/world/blocks") { exchange, _ -> setBlock(exchange) }
        router.post("/world/blocks/fill") { exchange, _ -> fill(exchange) }
        router.get("/world/entities") { exchange, _ -> getEntities(exchange) }
        router.post("/world/entities") { exchange, _ -> summon(exchange) }
        router.post("/world/entities/{uuid}/kill") { exchange, parameters ->
            killEntity(exchange, HttpExchangeSupport.decodePathSegment(parameters["uuid"], "uuid"))
        }
        router.post("/world/entities/kill") { exchange, _ -> killEntities(exchange) }
    }

    private fun getEntities(exchange: HttpExchange) {
        val parameters = HttpExchangeSupport.queryParameters(exchange)
        HttpExchangeSupport.requireOnlyQueryParameters(parameters, GET_ENTITIES_FIELDS)
        val dimension = HttpExchangeSupport.requireQueryString(parameters, "dimension")
        val entity = parameters["entity"]?.let {
            HttpExchangeSupport.requireQueryString(parameters, "entity")
        }
        val center = if (ENTITY_CENTER_FIELDS.any { it in parameters }) {
            EntityQueryCenter(
                HttpExchangeSupport.requireQueryFiniteDouble(parameters, "x"),
                HttpExchangeSupport.requireQueryFiniteDouble(parameters, "y"),
                HttpExchangeSupport.requireQueryFiniteDouble(parameters, "z"),
            )
        } else {
            null
        }
        val radius = parameters["radius"]?.let {
            HttpExchangeSupport.requireQueryFiniteDouble(parameters, "radius").also { radius ->
                if (radius < 0) throw JsonSupport.invalid("Query parameter 'radius' must not be negative")
            }
        }
        val sort = parameters["sort"]?.let { value ->
            EntitySort.entries.firstOrNull { it.value == value }
                ?: throw JsonSupport.invalid("Query parameter 'sort' must be arbitrary, nearest, or furthest")
        } ?: EntitySort.ARBITRARY
        if (center == null && (radius != null || sort != EntitySort.ARBITRARY)) {
            throw JsonSupport.invalid("Query parameters 'x', 'y', and 'z' are required for radius or distance sorting")
        }
        val limit = parameters["limit"]?.let {
            HttpExchangeSupport.requireQueryInteger(parameters, "limit").also { limit ->
                if (limit <= 0) throw JsonSupport.invalid("Query parameter 'limit' must be positive")
            }
        }
        val request = GetEntitiesRequest(dimension, entity, center, radius, sort, limit)
        val entities = operations.await(minecraft.getEntities(request))
        val response = buildJsonObject {
            put("entities", buildJsonArray {
                entities.forEach { entity ->
                    add(buildJsonObject {
                        put("uuid", entity.uuid)
                        put("entity", entity.entity)
                        put("dimension", entity.dimension)
                        put("x", entity.x)
                        put("y", entity.y)
                        put("z", entity.z)
                        put("yaw", entity.yaw)
                        put("pitch", entity.pitch)
                        put("name", entity.name)
                        put("health", entity.health?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
        }.toString()
        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    private fun getBlock(exchange: HttpExchange) {
        val parameters = HttpExchangeSupport.queryParameters(exchange)
        HttpExchangeSupport.requireOnlyQueryParameters(parameters, GET_BLOCK_FIELDS)

        val request = GetBlockRequest(
            HttpExchangeSupport.requireQueryString(parameters, "dimension"),
            HttpExchangeSupport.requireQueryInteger(parameters, "x"),
            HttpExchangeSupport.requireQueryInteger(parameters, "y"),
            HttpExchangeSupport.requireQueryInteger(parameters, "z"),
        )
        val result = operations.await(minecraft.getBlock(request))
        HttpExchangeSupport.sendJson(exchange, 200, blockJson(result).toString())
    }

    private fun getBlocks(exchange: HttpExchange) {
        val parameters = HttpExchangeSupport.queryParameters(exchange)
        HttpExchangeSupport.requireOnlyQueryParameters(parameters, GET_BLOCKS_FIELDS)
        val request = GetBlocksRequest(
            HttpExchangeSupport.requireQueryString(parameters, "dimension"),
            HttpExchangeSupport.requireQueryInteger(parameters, "x1"),
            HttpExchangeSupport.requireQueryInteger(parameters, "y1"),
            HttpExchangeSupport.requireQueryInteger(parameters, "z1"),
            HttpExchangeSupport.requireQueryInteger(parameters, "x2"),
            HttpExchangeSupport.requireQueryInteger(parameters, "y2"),
            HttpExchangeSupport.requireQueryInteger(parameters, "z2"),
        )
        val blocks = operations.await(minecraft.getBlocks(request))
        val response = buildJsonObject {
            put("blocks", buildJsonArray {
                blocks.forEach { add(blockJson(it)) }
            })
        }
        HttpExchangeSupport.sendJson(exchange, 200, response.toString())
    }

    private fun blockJson(result: GetBlockResult) =
        buildJsonObject {
            put("dimension", result.dimension)
            put("x", result.x)
            put("y", result.y)
            put("z", result.z)
            put("block", result.block)
            put("properties", buildJsonObject {
                result.properties.forEach { (name, value) -> put(name, value) }
            })
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
            JsonSupport.optionalStringMap(objectValue, "properties"),
        )
        val result = operations.await(minecraft.setBlock(request))
        val response = buildJsonObject {
            put("changed", result.changed)
            put("dimension", result.dimension)
            put("x", result.x)
            put("y", result.y)
            put("z", result.z)
            put("block", result.block)
            put("properties", buildJsonObject {
                result.properties.forEach { (name, value) -> put(name, value) }
            })
        }.toString()

        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    private fun fill(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, FILL_FIELDS)

        val mode = JsonSupport.optionalString(objectValue, "mode")?.let { value ->
            FillMode.entries.firstOrNull { it.value == value }
                ?: throw JsonSupport.invalid("Field 'mode' must be replace, destroy, keep, outline, hollow, or strict")
        } ?: FillMode.REPLACE
        if ("replaceProperties" in objectValue && "replace" !in objectValue) {
            throw JsonSupport.invalid("Field 'replaceProperties' requires 'replace'")
        }

        val request = FillRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            JsonSupport.requireInteger(objectValue, "x1"),
            JsonSupport.requireInteger(objectValue, "y1"),
            JsonSupport.requireInteger(objectValue, "z1"),
            JsonSupport.requireInteger(objectValue, "x2"),
            JsonSupport.requireInteger(objectValue, "y2"),
            JsonSupport.requireInteger(objectValue, "z2"),
            JsonSupport.requireString(objectValue, "block"),
            mode,
            JsonSupport.optionalString(objectValue, "replace"),
            JsonSupport.optionalStringMap(objectValue, "properties"),
            JsonSupport.optionalStringMap(objectValue, "replaceProperties"),
        )
        requireFillWithinLimit(request)

        val result = operations.await(minecraft.fill(request))
        val response = buildJsonObject {
            put("changedBlocks", result.changedBlocks)
            put("dimension", result.dimension)
            put("x1", result.x1)
            put("y1", result.y1)
            put("z1", result.z1)
            put("x2", result.x2)
            put("y2", result.y2)
            put("z2", result.z2)
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

    private fun killEntity(exchange: HttpExchange, uuid: String) {
        val entityUuid = try {
            UUID.fromString(uuid).also {
                if (!it.toString().equals(uuid, ignoreCase = true)) {
                    throw IllegalArgumentException()
                }
            }
        } catch (_: IllegalArgumentException) {
            throw JsonSupport.invalid("Path parameter 'uuid' must be a UUID in hyphenated form")
        }
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, KILL_ENTITY_FIELDS)
        val request = KillEntityRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            entityUuid,
        )
        sendKillResult(exchange, operations.await(minecraft.killEntity(request)))
    }

    private fun killEntities(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, KILL_ENTITIES_FIELDS)
        val range = if (KILL_RANGE_FIELDS.any { it in objectValue }) {
            val x = JsonSupport.requireFiniteDouble(objectValue, "x")
            val y = JsonSupport.requireFiniteDouble(objectValue, "y")
            val z = JsonSupport.requireFiniteDouble(objectValue, "z")
            val radius = JsonSupport.requireFiniteDouble(objectValue, "radius")
            if (radius < 0) {
                throw JsonSupport.invalid("Field 'radius' must not be negative")
            }
            EntityKillRange(x, y, z, radius)
        } else {
            null
        }
        val request = KillEntitiesRequest(
            JsonSupport.requireString(objectValue, "dimension"),
            JsonSupport.requireString(objectValue, "entity"),
            range,
        )
        sendKillResult(exchange, operations.await(minecraft.killEntities(request)))
    }

    private fun sendKillResult(exchange: HttpExchange, result: KillResult) {
        val response = buildJsonObject {
            put("dimension", result.dimension)
            put("killedEntities", result.killedEntities)
        }.toString()
        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    companion object {
        private val ENTITY_CENTER_FIELDS = setOf("x", "y", "z")
        private val GET_ENTITIES_FIELDS =
            setOf("dimension", "entity", "radius", "sort", "limit") + ENTITY_CENTER_FIELDS
        private val GET_BLOCK_FIELDS = setOf("dimension", "x", "y", "z")
        private val GET_BLOCKS_FIELDS = setOf("dimension", "x1", "y1", "z1", "x2", "y2", "z2")
        private val SET_BLOCK_FIELDS = setOf("dimension", "x", "y", "z", "block", "properties")
        private val FILL_FIELDS = setOf(
            "dimension",
            "x1",
            "y1",
            "z1",
            "x2",
            "y2",
            "z2",
            "block",
            "mode", "replace", "properties", "replaceProperties",
        )
        private val SUMMON_FIELDS = setOf("dimension", "entity", "x", "y", "z", "nbt")
        private val KILL_ENTITY_FIELDS = setOf("dimension")
        private val KILL_RANGE_FIELDS = setOf("x", "y", "z", "radius")
        private val KILL_ENTITIES_FIELDS = setOf("dimension", "entity") + KILL_RANGE_FIELDS
        private const val MAX_FILL_BLOCKS = 32_768

        private fun requireFillWithinLimit(request: FillRequest) {
            var volume = 1L
            val spans = listOf(
                kotlin.math.abs(request.x2.toLong() - request.x1.toLong()) + 1L,
                kotlin.math.abs(request.y2.toLong() - request.y1.toLong()) + 1L,
                kotlin.math.abs(request.z2.toLong() - request.z1.toLong()) + 1L,
            )

            for (span in spans) {
                if (span > MAX_FILL_BLOCKS / volume) {
                    throw JsonSupport.invalid(
                        "Fill region must contain at most $MAX_FILL_BLOCKS blocks",
                    )
                }
                volume *= span
            }
        }
    }
}
