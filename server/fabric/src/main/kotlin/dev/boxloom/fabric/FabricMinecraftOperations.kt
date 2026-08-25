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
import kotlinx.coroutines.suspendCancellableCoroutine
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
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.Level
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class FabricMinecraftOperations(
    private val currentServer: AtomicReference<MinecraftServer?>,
) : MinecraftOperations {
    override suspend fun say(request: SayRequest): SayResult =
        onServerThread { server ->
            val recipients = server.playerList.playerCount
            server.playerList.broadcastSystemMessage(Component.literal(request.message), false)
            SayResult(request.message, recipients)
        }

    override suspend fun players(): List<Player> =
        onServerThread { server ->
            server.playerList.players.map { player ->
                Player(
                    player.name.string,
                    player.uuid.toString(),
                )
            }
        }

    override suspend fun playerPosition(username: String): PlayerPosition =
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

    override suspend fun setBlock(request: SetBlockRequest): SetBlockResult =
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

    override suspend fun summon(request: SummonRequest): SummonResult =
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

    private suspend fun <T> onServerThread(operation: (MinecraftServer) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val server = currentServer.get()
            if (server == null) {
                continuation.resumeWithException(
                    ApiException(
                        503,
                        "WORLD_NOT_LOADED",
                        "No Minecraft server world is currently loaded",
                    ),
                )
                return@suspendCancellableCoroutine
            }

            try {
                server.execute(Runnable {
                    if (!continuation.isActive) {
                        return@Runnable
                    }
                    if (currentServer.get() !== server) {
                        continuation.resumeWithException(
                            ApiException(
                                503,
                                "WORLD_NOT_LOADED",
                                "No Minecraft server world is currently loaded",
                            ),
                        )
                        return@Runnable
                    }

                    try {
                        continuation.resume(operation(server))
                    } catch (throwable: Throwable) {
                        continuation.resumeWithException(throwable)
                    }
                })
            } catch (throwable: Throwable) {
                continuation.resumeWithException(throwable)
            }
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
