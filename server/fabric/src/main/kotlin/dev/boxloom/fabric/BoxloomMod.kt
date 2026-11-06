package dev.boxloom.fabric

import dev.boxloom.server.core.BoxloomConfig
import dev.boxloom.server.core.BoxloomEventBroker
import dev.boxloom.server.core.BoxloomHttpServer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

object BoxloomMod : ModInitializer {
    const val MOD_ID = "boxloom"

    private val logger = LoggerFactory.getLogger(MOD_ID)
    private val currentServer = AtomicReference<MinecraftServer?>(null)
    private val httpServer = AtomicReference<BoxloomHttpServer?>(null)
    private val events = BoxloomEventBroker()
    private val playerActions = PlayerActionEvents(events)

    override fun onInitialize() {
        logger.info("Initializing boxloom")
        playerActions.register()
        ServerTickEvents.START_SERVER_TICK.register { server -> PlayerMiningActions.tick(server) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> PlayerMiningActions.stop(handler.player) }
        ServerLifecycleEvents.SERVER_STOPPING.register { PlayerMiningActions.clear() }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            currentServer.set(server)
            logger.info("boxloom attached to a started Minecraft server")
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            currentServer.compareAndSet(server, null)
            events.reset()
            if (server.isDedicatedServer) {
                // The HTTP dispatcher otherwise keeps the JVM alive before shutdown hooks run.
                stopHttpServer()
            }
            logger.info("boxloom detached from the stopped Minecraft server")
        }
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            events.publishChatMessage(
                message.signedContent(),
                sender.name.string,
                sender.uuid.toString(),
            )
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            if (entity is ServerPlayer) {
                events.publishPlayerDeath(
                    // The combat tracker has already been cleared by this callback.
                    source.getLocalizedDeathMessage(entity).string,
                    entity.name.string,
                    entity.uuid.toString(),
                    entity.level().dimension().identifier().toString(),
                    entity.x,
                    entity.y,
                    entity.z,
                )
            }
        }

        val configPath = FabricLoader.getInstance().configDir.resolve("boxloom.json")
        val config = BoxloomConfig.load(configPath, System.getenv())

        if (!config.authenticationEnabled) {
            logger.warn(
                "boxloom authentication is disabled; unauthenticated access is limited to " +
                    "this computer. Set authToken in {} to enable authentication.",
                configPath,
            )
        }

        try {
            val boxloomHttpServer = BoxloomHttpServer(
                config,
                FabricMinecraftOperations(currentServer),
                events,
            )

            if (!httpServer.compareAndSet(null, boxloomHttpServer)) {
                boxloomHttpServer.close()
                error("boxloom HTTP server is already initialized")
            }

            boxloomHttpServer.start()
            Runtime.getRuntime().addShutdownHook(
                Thread(::stopHttpServer, "boxloom-shutdown"),
            )
        } catch (exception: IOException) {
            throw IllegalStateException("Could not start the boxloom HTTP server", exception)
        }
    }

    private fun stopHttpServer() {
        httpServer.getAndSet(null)?.let { server ->
            server.close()
            logger.info("boxloom HTTP server stopped")
        }
    }

    @JvmStatic
    fun publishPlayerSwing(player: ServerPlayer) {
        playerActions.swing(player)
    }
}
