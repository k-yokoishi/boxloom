package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface MinecraftOperations {
    fun say(request: SayRequest): CompletableFuture<SayResult>

    fun players(): CompletableFuture<List<Player>>

    fun playerPosition(username: String): CompletableFuture<PlayerPosition>

    fun teleportPlayer(
        username: String,
        request: TeleportPlayerRequest,
    ): CompletableFuture<PlayerPosition>

    fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult>

    fun summon(request: SummonRequest): CompletableFuture<SummonResult>
}

data class SayRequest(
    val message: String,
)

data class SayResult(
    val message: String,
    val recipients: Int,
)

data class Player(
    val username: String,
    val uuid: String,
)

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

data class TeleportPlayerRequest(
    val x: Double,
    val y: Double,
    val z: Double,
    val dimension: String? = null,
    val yaw: Double? = null,
    val pitch: Double? = null,
)

data class SetBlockRequest(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
)

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
