package dev.boxloom.server.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class PlayersResponse(
    val players: List<Player>,
)

@Serializable
internal data class ErrorResponse(
    val error: ApiErrorBody,
)

@Serializable
internal data class ApiErrorBody(
    val code: String,
    val message: String,
)

@Serializable
internal data class StreamReadyPayload(
    val type: String,
    val cursor: String,
)

@Serializable
internal data class ChatMessagePayload(
    val type: String,
    val id: String,
    val timestamp: String,
    val message: String,
    val player: Player,
)

@Serializable
internal data class StreamFailurePayload(
    val type: String,
    val code: String,
    val message: String,
)

@Serializable
internal data class SummonRequestBody(
    val dimension: String,
    val entity: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val nbt: JsonObject? = null,
)
