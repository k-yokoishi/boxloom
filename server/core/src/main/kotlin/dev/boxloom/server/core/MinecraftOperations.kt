package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface MinecraftOperations {
    fun say(request: SayRequest): CompletableFuture<SayResult>

    fun players(): CompletableFuture<List<Player>>

    fun playerPosition(username: String): CompletableFuture<PlayerPosition>

    fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult>
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
