package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.MinecraftOperations
import dev.boxloom.server.core.PlayerPosition
import dev.boxloom.server.core.SayRequest
import dev.boxloom.server.core.SayResult
import dev.boxloom.server.core.SetBlockRequest
import dev.boxloom.server.core.SetBlockResult
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
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
                if (future.isDone || currentServer.get() !== server) {
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
