package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface PlayerOperations {
    fun players(): CompletableFuture<List<Player>>

    fun playerPosition(username: String): CompletableFuture<PlayerPosition>

    fun teleportPlayer(
        username: String,
        request: TeleportPlayerRequest,
    ): CompletableFuture<PlayerPosition>
}

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
