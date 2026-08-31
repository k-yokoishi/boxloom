package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.MinecraftOperations
import dev.boxloom.server.core.NbtValue
import dev.boxloom.server.core.Player
import dev.boxloom.server.core.PlayerPosition
import dev.boxloom.server.core.SayRequest
import dev.boxloom.server.core.SayResult
import dev.boxloom.server.core.SetBlockRequest
import dev.boxloom.server.core.SetBlockResult
import dev.boxloom.server.core.SummonRequest
import dev.boxloom.server.core.SummonResult
import dev.boxloom.server.core.TeleportPlayerRequest
import net.minecraft.core.BlockPos
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
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.Level
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

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
            val changed = level.setBlock(position, block.defaultBlockState(), 3)

            SetBlockResult(
                changed,
                dimensionKey.identifier().toString(),
                request.x,
                request.y,
                request.z,
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
