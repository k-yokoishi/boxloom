package dev.boxloom.server.core.api.time

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.AddTimeRequest
import dev.boxloom.server.core.GetTimeRequest
import dev.boxloom.server.core.JsonSupport
import dev.boxloom.server.core.SetTimeRequest
import dev.boxloom.server.core.TimeOperations
import dev.boxloom.server.core.TimeResult
import dev.boxloom.server.core.TimeValue
import dev.boxloom.server.core.http.HttpExchangeSupport
import dev.boxloom.server.core.http.MinecraftOperationRunner
import dev.boxloom.server.core.http.Router
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class TimeRoutes(
    private val minecraft: TimeOperations,
    private val operations: MinecraftOperationRunner,
) {
    fun register(router: Router) {
        router.get("/world/time") { exchange, _ -> getTime(exchange) }
        router.post("/world/time") { exchange, _ -> setTime(exchange) }
        router.post("/world/time/add") { exchange, _ -> addTime(exchange) }
    }

    private fun getTime(exchange: HttpExchange) {
        val parameters = HttpExchangeSupport.queryParameters(exchange)
        HttpExchangeSupport.requireOnlyQueryParameters(parameters, setOf("clock"))
        val clock = if ("clock" in parameters) {
            HttpExchangeSupport.requireQueryString(parameters, "clock")
        } else {
            DEFAULT_CLOCK
        }
        sendResult(exchange, operations.await(minecraft.getTime(GetTimeRequest(clock))))
    }

    private fun setTime(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, setOf("clock", "time"))
        val clock = JsonSupport.optionalString(objectValue, "clock") ?: DEFAULT_CLOCK
        val time = if ((objectValue["time"] as? JsonPrimitive)?.isString == true) {
            TimeValue.Marker(JsonSupport.requireString(objectValue, "time"))
        } else {
            val ticks = JsonSupport.requireInteger(objectValue, "time")
            if (ticks < 0) throw JsonSupport.invalid("Field 'time' must not be negative")
            TimeValue.Ticks(ticks)
        }
        sendResult(exchange, operations.await(minecraft.setTime(SetTimeRequest(clock, time))))
    }

    private fun addTime(exchange: HttpExchange) {
        HttpExchangeSupport.requireJsonContentType(exchange)
        val objectValue = JsonSupport.parseObject(HttpExchangeSupport.readRequestBody(exchange))
        JsonSupport.requireOnlyFields(objectValue, setOf("clock", "ticks"))
        val request = AddTimeRequest(
            JsonSupport.optionalString(objectValue, "clock") ?: DEFAULT_CLOCK,
            JsonSupport.requireInteger(objectValue, "ticks"),
        )
        sendResult(exchange, operations.await(minecraft.addTime(request)))
    }

    private fun sendResult(exchange: HttpExchange, result: TimeResult) {
        val response = buildJsonObject {
            put("clock", result.clock)
            put("ticks", result.ticks)
        }.toString()
        HttpExchangeSupport.sendJson(exchange, 200, response)
    }

    companion object {
        private const val DEFAULT_CLOCK = "minecraft:overworld"
    }
}
