package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface PlayerOperations {
    fun players(): CompletableFuture<List<Player>>

    fun playerPosition(username: String): CompletableFuture<PlayerPosition>

    fun targetedBlock(username: String): CompletableFuture<TargetedBlock?>

    fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult>

    fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult>

    fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult>

    fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult>

    fun givePlayerItem(username: String, request: GiveItemRequest): CompletableFuture<GiveItemResult>

    fun startMining(username: String): CompletableFuture<PlayerMiningResult>

    fun stopMining(username: String): CompletableFuture<PlayerMiningResult>

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
    val properties: Map<String, String> = emptyMap(),
)

data class PlayerAttackResult(
    val swung: Boolean,
    val target: TargetedBlock?,
    val broken: Boolean,
    val entity: EntitySnapshot? = null,
)

data class PlayerMiningResult(
    val active: Boolean,
    val target: TargetedBlock?,
    val broken: Boolean,
)

data class PlayerUseResult(
    val used: Boolean,
    val swung: Boolean,
    val target: TargetedBlock?,
    val entity: EntitySnapshot? = null,
)

data class PlayerItemUseResult(
    val active: Boolean,
    val item: String?,
    val remainingTicks: Int,
)

data class GiveItemRequest(val item: String, val count: Int = 1)

data class GiveItemResult(
    val username: String,
    val uuid: String,
    val item: String,
    val count: Int,
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
