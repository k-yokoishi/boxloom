package dev.boxloom.fabric

import dev.boxloom.server.core.AddTimeRequest
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.EntitySnapshot
import dev.boxloom.server.core.GetEntitiesRequest
import dev.boxloom.server.core.GameMode
import dev.boxloom.server.core.GiveItemRequest
import dev.boxloom.server.core.GiveItemResult
import dev.boxloom.server.core.SelectSlotRequest
import dev.boxloom.server.core.SelectSlotResult
import dev.boxloom.server.core.FillRequest
import dev.boxloom.server.core.FillResult
import dev.boxloom.server.core.GetBlockRequest
import dev.boxloom.server.core.GetBlockResult
import dev.boxloom.server.core.GetTimeRequest
import dev.boxloom.server.core.KillEntitiesRequest
import dev.boxloom.server.core.KillEntityRequest
import dev.boxloom.server.core.KillResult
import dev.boxloom.server.core.MinecraftOperations
import dev.boxloom.server.core.NbtValue
import dev.boxloom.server.core.Player
import dev.boxloom.server.core.PlayerGameMode
import dev.boxloom.server.core.PlayerPosition
import dev.boxloom.server.core.TargetedBlock
import dev.boxloom.server.core.PlayerAttackResult
import dev.boxloom.server.core.PlayerUseResult
import dev.boxloom.server.core.PlayerItemUseResult
import dev.boxloom.server.core.PlayerMiningResult
import dev.boxloom.server.core.SayRequest
import dev.boxloom.server.core.SayResult
import dev.boxloom.server.core.SetBlockRequest
import dev.boxloom.server.core.SetBlockResult
import dev.boxloom.server.core.SetPlayerGameModeRequest
import dev.boxloom.server.core.SetTimeRequest
import dev.boxloom.server.core.SummonRequest
import dev.boxloom.server.core.SummonResult
import dev.boxloom.server.core.TeleportPlayerRequest
import dev.boxloom.server.core.TimeResult
import dev.boxloom.server.core.TimeValue
import net.minecraft.commands.arguments.blocks.BlockInput
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.clock.ClockTimeMarkers
import net.minecraft.world.clock.ServerClockManager
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.Level
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

internal class FabricMinecraftOperations(
    private val currentServer: AtomicReference<MinecraftServer?>,
) : MinecraftOperations {
    override fun say(request: SayRequest): CompletableFuture<SayResult> =
        onServerThread { server ->
            val recipients = server.playerList.playerCount
            server.playerList.broadcastSystemMessage(Component.literal(request.message), false)
            SayResult(request.message, recipients)
        }

    override fun players(): CompletableFuture<List<Player>> =
        onServerThread { server ->
            server.playerList.players.map { player ->
                Player(
                    player.name.string,
                    player.uuid.toString(),
                )
            }
        }

    override fun playerPosition(username: String): CompletableFuture<PlayerPosition> =
        onServerThread { server ->
            val player = server.playerList.getPlayerByName(username)
                ?: throw ApiException(
                    404,
                    "PLAYER_NOT_CONNECTED",
                    "Player '$username' is not connected",
                )

            PlayerPosition(
                player.name.string,
                player.uuid.toString(),
                player.level().dimension().identifier().toString(),
                player.x,
                player.y,
                player.z,
                player.yRot,
                player.xRot,
            )
        }

    override fun targetedBlock(username: String): CompletableFuture<TargetedBlock?> =
        onServerThread { server -> PlayerBlockActions.targetedBlock(connectedPlayer(server, username)) }

    override fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult> =
        onServerThread { server -> PlayerBlockActions.attack(connectedPlayer(server, username)) }

    override fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult> =
        onServerThread { server -> PlayerUseActions.use(connectedPlayer(server, username)) }

    override fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
        onServerThread { server -> PlayerItemUseActions.start(connectedPlayer(server, username)) }

    override fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
        onServerThread { server -> PlayerItemUseActions.stop(connectedPlayer(server, username)) }

    override fun givePlayerItem(username: String, request: GiveItemRequest): CompletableFuture<GiveItemResult> =
        onServerThread { server -> PlayerInventoryActions.give(connectedPlayer(server, username), request) }

    override fun selectPlayerSlot(username: String, request: SelectSlotRequest): CompletableFuture<SelectSlotResult> =
        onServerThread { server -> PlayerInventoryActions.selectSlot(connectedPlayer(server, username), request) }

    override fun startMining(username: String): CompletableFuture<PlayerMiningResult> =
        onServerThread { server -> PlayerMiningActions.start(connectedPlayer(server, username)) }

    override fun stopMining(username: String): CompletableFuture<PlayerMiningResult> =
        onServerThread { server -> PlayerMiningActions.stop(connectedPlayer(server, username)) }

    private fun connectedPlayer(server: MinecraftServer, username: String): ServerPlayer =
        server.playerList.getPlayerByName(username)
            ?: throw ApiException(404, "PLAYER_NOT_CONNECTED", "Player '$username' is not connected")

    override fun teleportPlayer(
        username: String,
        request: TeleportPlayerRequest,
    ): CompletableFuture<PlayerPosition> =
        onServerThread { server ->
            val player = server.playerList.getPlayerByName(username)
                ?: throw ApiException(
                    404,
                    "PLAYER_NOT_CONNECTED",
                    "Player '$username' is not connected",
                )
            val targetLevel = request.dimension?.let { dimension ->
                val dimensionId = parseIdentifier(dimension, "dimension")
                val dimensionKey: ResourceKey<Level> =
                    ResourceKey.create(Registries.DIMENSION, dimensionId)
                server.getLevel(dimensionKey)
                    ?: throw ApiException(
                        404,
                        "DIMENSION_NOT_FOUND",
                        "Dimension '$dimension' is not loaded",
                    )
            } ?: player.level()

            val position = BlockPos.containing(request.x, request.y, request.z)
            if (!Level.isInSpawnableBounds(position)) {
                throw ApiException(
                    400,
                    "INVALID_POSITION",
                    "The requested position is outside Minecraft's spawnable bounds",
                )
            }

            val yaw = request.yaw?.let { Mth.wrapDegrees(it).toFloat() } ?: player.yRot
            val pitch = request.pitch?.let { Mth.wrapDegrees(it).toFloat() } ?: player.xRot
            val teleported = player.teleportTo(
                targetLevel,
                request.x,
                request.y,
                request.z,
                emptySet<Relative>(),
                yaw,
                pitch,
                true,
            )
            if (!teleported) {
                throw ApiException(
                    409,
                    "TELEPORT_FAILED",
                    "Player '$username' could not be teleported",
                )
            }

            if (!player.isFallFlying) {
                val movement = player.deltaMovement
                player.setDeltaMovement(movement.x, 0.0, movement.z)
                player.setOnGround(true)
            }

            PlayerPosition(
                player.name.string,
                player.uuid.toString(),
                player.level().dimension().identifier().toString(),
                player.x,
                player.y,
                player.z,
                player.yRot,
                player.xRot,
            )
        }

    override fun setPlayerGameMode(
        username: String,
        request: SetPlayerGameModeRequest,
    ): CompletableFuture<PlayerGameMode> =
        onServerThread { server ->
            val player = server.playerList.getPlayerByName(username)
                ?: throw ApiException(
                    404,
                    "PLAYER_NOT_CONNECTED",
                    "Player '$username' is not connected",
                )
            val targetMode = when (request.mode) {
                GameMode.CREATIVE -> GameType.CREATIVE
                GameMode.SURVIVAL -> GameType.SURVIVAL
            }
            // Minecraft returns false when the player is already in the requested mode.
            player.setGameMode(targetMode)
            if (player.gameMode() != targetMode) {
                throw ApiException(
                    409,
                    "GAME_MODE_CHANGE_FAILED",
                    "Player '$username' could not change game mode",
                )
            }
            PlayerGameMode(player.name.string, player.uuid.toString(), request.mode)
        }

    override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> =
        onServerThread { server ->
            val dimensionId = parseIdentifier(request.dimension, "dimension")
            val blockId = parseIdentifier(request.block, "block")
            val dimensionKey: ResourceKey<Level> =
                ResourceKey.create(Registries.DIMENSION, dimensionId)
            val level = server.getLevel(dimensionKey)
                ?: throw ApiException(
                    404,
                    "DIMENSION_NOT_FOUND",
                    "Dimension '${request.dimension}' is not loaded",
                )
            val block = BuiltInRegistries.BLOCK.getOptional(blockId).orElseThrow {
                ApiException(
                    400,
                    "INVALID_BLOCK",
                    "Block '${request.block}' does not exist",
                )
            }
            val position = BlockPos(request.x, request.y, request.z)
            val state = BlockStates.resolve(block, request.properties)
            val changed = level.setBlock(position, state, 3)
            val after = level.getBlockState(position)

            SetBlockResult(
                changed,
                dimensionKey.identifier().toString(),
                request.x,
                request.y,
                request.z,
                BuiltInRegistries.BLOCK.getKey(after.block).toString(),
                BlockStates.properties(after),
            )
        }

    override fun getBlock(request: GetBlockRequest): CompletableFuture<GetBlockResult> =
        onServerThread { server ->
            val dimensionId = parseIdentifier(request.dimension, "dimension")
            val dimensionKey: ResourceKey<Level> =
                ResourceKey.create(Registries.DIMENSION, dimensionId)
            val level = server.getLevel(dimensionKey)
                ?: throw ApiException(
                    404,
                    "DIMENSION_NOT_FOUND",
                    "Dimension '${request.dimension}' is not loaded",
                )
            val position = BlockPos(request.x, request.y, request.z)
            val state = level.getBlockState(position)
            val blockId = BuiltInRegistries.BLOCK.getKey(state.block)

            GetBlockResult(
                dimensionKey.identifier().toString(),
                request.x,
                request.y,
                request.z,
                blockId.toString(),
                BlockStates.properties(state),
            )
        }

    override fun fill(request: FillRequest): CompletableFuture<FillResult> =
        onServerThread { server ->
            val dimensionId = parseIdentifier(request.dimension, "dimension")
            val blockId = parseIdentifier(request.block, "block")
            val dimensionKey: ResourceKey<Level> =
                ResourceKey.create(Registries.DIMENSION, dimensionId)
            val level = server.getLevel(dimensionKey)
                ?: throw ApiException(
                    404,
                    "DIMENSION_NOT_FOUND",
                    "Dimension '${request.dimension}' is not loaded",
                )
            val block = BuiltInRegistries.BLOCK.getOptional(blockId).orElseThrow {
                ApiException(
                    400,
                    "INVALID_BLOCK",
                    "Block '${request.block}' does not exist",
                )
            }

            val first = BlockPos(request.x1, request.y1, request.z1)
            val second = BlockPos(request.x2, request.y2, request.z2)
            if (
                !Level.isInSpawnableBounds(first) ||
                !Level.isInSpawnableBounds(second) ||
                level.isOutsideBuildHeight(first) ||
                level.isOutsideBuildHeight(second)
            ) {
                throw ApiException(
                    400,
                    "INVALID_POSITION",
                    "The fill region is outside Minecraft's buildable bounds",
                )
            }

            val blockInput = BlockInput(block.defaultBlockState(), emptySet(), null)
            val changedPositions = mutableListOf<Pair<BlockPos, BlockState>>()
            for (position in BlockPos.betweenClosed(first, second)) {
                val oldState = level.getBlockState(position)
                if (
                    blockInput.place(
                        level,
                        position,
                        Block.UPDATE_CLIENTS or Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS,
                    )
                ) {
                    changedPositions.add(position.immutable() to oldState)
                }
            }
            for ((position, oldState) in changedPositions) {
                level.updateNeighboursOnBlockSet(position, oldState)
            }

            FillResult(
                changedPositions.size,
                dimensionKey.identifier().toString(),
                request.x1,
                request.y1,
                request.z1,
                request.x2,
                request.y2,
                request.z2,
                blockId.toString(),
            )
        }

    override fun summon(request: SummonRequest): CompletableFuture<SummonResult> =
        onServerThread { server ->
            val dimensionId = parseIdentifier(request.dimension, "dimension")
            val entityId = parseIdentifier(request.entity, "entity")
            val dimensionKey: ResourceKey<Level> =
                ResourceKey.create(Registries.DIMENSION, dimensionId)
            val level = server.getLevel(dimensionKey)
                ?: throw ApiException(
                    404,
                    "DIMENSION_NOT_FOUND",
                    "Dimension '${request.dimension}' is not loaded",
                )
            val entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId).orElseThrow {
                ApiException(
                    400,
                    "INVALID_ENTITY",
                    "Entity '${request.entity}' does not exist",
                )
            }

            if (!entityType.canSummon()) {
                throw ApiException(
                    400,
                    "INVALID_ENTITY",
                    "Entity '${request.entity}' cannot be summoned",
                )
            }

            val position = BlockPos.containing(request.x, request.y, request.z)
            if (!Level.isInSpawnableBounds(position)) {
                throw ApiException(
                    400,
                    "INVALID_POSITION",
                    "The requested position is outside Minecraft's spawnable bounds",
                )
            }

            if (level.difficulty == Difficulty.PEACEFUL && !entityType.isAllowedInPeaceful) {
                throw ApiException(
                    400,
                    "ENTITY_NOT_ALLOWED_IN_PEACEFUL",
                    "Entity '${request.entity}' cannot be summoned in peaceful difficulty",
                )
            }

            val tag = request.nbt?.toCompoundTag() ?: CompoundTag()
            tag.putString("id", entityId.toString())
            val entity = EntityType.loadEntityRecursive(
                tag,
                level,
                EntitySpawnRequest(EntitySpawnReason.COMMAND, false),
            ) { loaded ->
                loaded.snapTo(
                    request.x,
                    request.y,
                    request.z,
                    loaded.yRot,
                    loaded.xRot,
                )
                loaded
            } ?: throw ApiException(
                400,
                "INVALID_ENTITY_NBT",
                "The supplied NBT could not be applied to '${request.entity}'",
            )

            if (request.nbt == null && entity is Mob) {
                entity.finalizeSpawn(
                    level,
                    level.getCurrentDifficultyAt(entity.blockPosition()),
                    EntitySpawnReason.COMMAND,
                    null,
                )
            }

            if (!level.tryAddFreshEntityWithPassengers(entity)) {
                throw ApiException(
                    409,
                    "DUPLICATE_ENTITY_UUID",
                    "An entity with the supplied UUID already exists",
                )
            }

            SummonResult(
                entity.uuid.toString(),
                EntityType.getKey(entity.type).toString(),
                dimensionKey.identifier().toString(),
                entity.x,
                entity.y,
                entity.z,
            )
        }

    override fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>> =
        onServerThread { server ->
            val level = resolveLevel(server, request.dimension)
            val entityId = request.entity?.let { value ->
                parseIdentifier(value, "entity").also { id ->
                    if (BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty) {
                        throw ApiException(400, "INVALID_ENTITY", "Entity '$value' does not exist")
                    }
                }
            }
            val dimension = level.dimension().identifier().toString()
            request.copy(entity = entityId?.toString()).select(
                level.allEntities.asSequence().filter { it.isAlive }.map { entity ->
                    EntitySnapshot(
                        entity.uuid.toString(),
                        EntityType.getKey(entity.type).toString(),
                        dimension,
                        entity.x,
                        entity.y,
                        entity.z,
                        entity.yRot,
                        entity.xRot,
                        entity.name.string,
                        (entity as? LivingEntity)?.health,
                    )
                },
            )
        }

    override fun killEntity(request: KillEntityRequest): CompletableFuture<KillResult> =
        onServerThread { server ->
            val level = resolveLevel(server, request.dimension)
            KillResult(
                level.dimension().identifier().toString(),
                killIfAlive(level, level.getEntity(request.uuid)),
            )
        }

    override fun killEntities(request: KillEntitiesRequest): CompletableFuture<KillResult> =
        onServerThread { server ->
            val level = resolveLevel(server, request.dimension)
            val entityId = parseIdentifier(request.entity, "entity")
            val entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId).orElseThrow {
                ApiException(400, "INVALID_ENTITY", "Entity '${request.entity}' does not exist")
            }
            // Killing can remove entities and spawn drops or smaller mobs. Select a snapshot first.
            val targets = level.allEntities.filter { entity ->
                entity.type == entityType && entity.isAlive && request.range.let { range ->
                    range == null || hypot(
                        hypot(entity.x - range.x, entity.y - range.y),
                        entity.z - range.z,
                    ) <= range.radius
                }
            }
            KillResult(
                level.dimension().identifier().toString(),
                targets.sumOf { entity -> killIfAlive(level, entity) },
            )
        }

    private fun killIfAlive(level: ServerLevel, entity: Entity?): Int {
        if (entity == null || !entity.isAlive) return 0
        entity.kill(level)
        return if (entity.isAlive) 0 else 1
    }

    private fun resolveLevel(server: MinecraftServer, name: String): ServerLevel {
        val dimensionId = parseIdentifier(name, "dimension")
        val dimensionKey: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, dimensionId)
        return server.getLevel(dimensionKey)
            ?: throw ApiException(404, "DIMENSION_NOT_FOUND", "Dimension '$name' is not loaded")
    }

    override fun getTime(request: GetTimeRequest): CompletableFuture<TimeResult> =
        onServerThread { server ->
            timeResult(server, resolveClock(server, request.clock))
        }

    override fun setTime(request: SetTimeRequest): CompletableFuture<TimeResult> =
        onServerThread { server ->
            val clock = resolveClock(server, request.clock)
            val manager = server.clockManager()
            when (val time = request.time) {
                is TimeValue.Ticks -> manager.setTotalTicks(clock, time.ticks.toLong())
                is TimeValue.Marker -> {
                    val markerId = parseIdentifier(time.marker, "time_marker")
                    val marker = ResourceKey.create(ClockTimeMarkers.ROOT_ID, markerId)
                    when (manager.moveToTimeMarker(clock, marker)) {
                        ServerClockManager.MoveResult.NO_TIME_MARKER_FOUND -> throw ApiException(
                            404,
                            "TIME_MARKER_NOT_FOUND",
                            "Time marker '${time.marker}' does not exist for clock '${request.clock}'",
                        )
                        ServerClockManager.MoveResult.NOT_MOVED -> throw ApiException(
                            409,
                            "TIME_ALREADY_AT_MARKER",
                            "Clock '${request.clock}' is already at time marker '${time.marker}'",
                        )
                        ServerClockManager.MoveResult.MOVED -> Unit
                    }
                }
            }
            timeResult(server, clock)
        }

    override fun addTime(request: AddTimeRequest): CompletableFuture<TimeResult> =
        onServerThread { server ->
            val clock = resolveClock(server, request.clock)
            server.clockManager().addTicks(clock, request.ticks)
            timeResult(server, clock)
        }

    private fun resolveClock(server: MinecraftServer, name: String): Holder<WorldClock> {
        val clockId = parseIdentifier(name, "clock")
        val clockKey = ResourceKey.create(Registries.WORLD_CLOCK, clockId)
        return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(clockKey)
            .orElseThrow {
                ApiException(404, "CLOCK_NOT_FOUND", "World clock '$name' does not exist")
            }
    }

    private fun timeResult(server: MinecraftServer, clock: Holder<WorldClock>): TimeResult =
        TimeResult(clock.registeredName, server.clockManager().getInstance(clock).totalTicks())

    private fun parseIdentifier(value: String, field: String): Identifier =
        Identifier.tryParse(value)
            ?: throw ApiException(
                400,
                "INVALID_${field.uppercase(Locale.ROOT)}",
                "Field '$field' is not a valid namespaced ID",
            )

    private fun <T> onServerThread(operation: (MinecraftServer) -> T): CompletableFuture<T> {
        val server = currentServer.get()
            ?: return CompletableFuture.failedFuture(
                ApiException(
                    503,
                    "WORLD_NOT_LOADED",
                    "No Minecraft server world is currently loaded",
                ),
            )
        val future = CompletableFuture<T>()

        try {
            server.execute(Runnable {
                if (future.isDone) {
                    return@Runnable
                }
                if (currentServer.get() !== server) {
                    future.completeExceptionally(
                        ApiException(
                            503,
                            "WORLD_NOT_LOADED",
                            "No Minecraft server world is currently loaded",
                        ),
                    )
                    return@Runnable
                }

                try {
                    future.complete(operation(server))
                } catch (throwable: Throwable) {
                    future.completeExceptionally(throwable)
                }
            })
        } catch (throwable: Throwable) {
            future.completeExceptionally(throwable)
        }

        return future
    }

}

private fun NbtValue.Compound.toCompoundTag(): CompoundTag =
    CompoundTag().also { compound ->
        values.forEach { (key, value) -> compound.put(key, value.toTag()) }
    }

private fun NbtValue.toTag(): Tag = when (this) {
    is NbtValue.Compound -> toCompoundTag()
    is NbtValue.ListValue -> ListTag().also { list ->
        values.forEach { value -> list.add(value.toTag()) }
    }
    is NbtValue.StringValue -> StringTag.valueOf(value)
    is NbtValue.BooleanValue -> ByteTag.valueOf(value)
    is NbtValue.IntValue -> IntTag.valueOf(value)
    is NbtValue.LongValue -> LongTag.valueOf(value)
    is NbtValue.DoubleValue -> DoubleTag.valueOf(value)
}
