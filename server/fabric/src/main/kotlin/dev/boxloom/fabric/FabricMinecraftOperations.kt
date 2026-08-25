package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.MinecraftOperations
import dev.boxloom.server.core.Player
import dev.boxloom.server.core.PlayerPosition
import dev.boxloom.server.core.SayRequest
import dev.boxloom.server.core.SayResult
import dev.boxloom.server.core.SetBlockRequest
import dev.boxloom.server.core.SetBlockResult
import kotlinx.coroutines.suspendCancellableCoroutine
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
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
