package dev.boxloom.server.core

import kotlinx.serialization.Serializable

interface MinecraftOperations {
    suspend fun say(request: SayRequest): SayResult

    suspend fun players(): List<Player>

    suspend fun playerPosition(username: String): PlayerPosition

    suspend fun setBlock(request: SetBlockRequest): SetBlockResult
}

@Serializable
data class SayRequest(
    val message: String,
)

@Serializable
data class SayResult(
    val message: String,
    val recipients: Int,
)

@Serializable
data class Player(
    val username: String,
    val uuid: String,
)

@Serializable
data class PlayerPosition(
    val username: String,
    val uuid: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

@Serializable
data class SetBlockRequest(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
)

@Serializable
data class SetBlockResult(
    val changed: Boolean,
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
)
