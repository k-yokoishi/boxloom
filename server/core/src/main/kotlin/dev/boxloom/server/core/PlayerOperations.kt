package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface PlayerOperations {
    fun players(): CompletableFuture<List<Player>>

    fun playerPosition(username: String): CompletableFuture<PlayerPosition>

    fun targetedBlock(username: String): CompletableFuture<TargetedBlock?>

    fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult>

    fun teleportPlayer(
        username: String,
        request: TeleportPlayerRequest,
    ): CompletableFuture<PlayerPosition>

    fun setPlayerGameMode(
        username: String,
        request: SetPlayerGameModeRequest,
    ): CompletableFuture<PlayerGameMode>
}

data class Player(
    val username: String,
    val uuid: String,
)

data class TargetedBlock(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
    val face: String,
    val distance: Double,
)

data class PlayerAttackResult(
    val swung: Boolean,
    val target: TargetedBlock?,
    val broken: Boolean,
)

enum class GameMode(val value: String) {
    CREATIVE("creative"),
    SURVIVAL("survival"),
}

data class SetPlayerGameModeRequest(val mode: GameMode)

data class PlayerGameMode(
    val username: String,
    val uuid: String,
    val mode: GameMode,
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
