package dev.boxloom.server.core

import kotlinx.serialization.Serializable

interface MinecraftOperations {
    suspend fun say(request: SayRequest): SayResult

    suspend fun players(): List<Player>

    suspend fun playerPosition(username: String): PlayerPosition

    suspend fun setBlock(request: SetBlockRequest): SetBlockResult

    suspend fun summon(request: SummonRequest): SummonResult
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

data class SummonRequest(
    val dimension: String,
    val entity: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val nbt: NbtValue.Compound? = null,
)

@Serializable
data class SummonResult(
    val uuid: String,
    val entity: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

sealed interface NbtValue {
    data class Compound(val values: Map<String, NbtValue>) : NbtValue

    data class ListValue(val values: List<NbtValue>) : NbtValue

    data class StringValue(val value: String) : NbtValue

    data class BooleanValue(val value: Boolean) : NbtValue

    data class IntValue(val value: Int) : NbtValue

    data class LongValue(val value: Long) : NbtValue

    data class DoubleValue(val value: Double) : NbtValue
}
