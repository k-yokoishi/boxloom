package dev.boxloom.fabric

import dev.boxloom.server.core.BoxloomConfig
import dev.boxloom.server.core.BoxloomHttpServer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

object BoxloomMod : ModInitializer {
    const val MOD_ID = "boxloom"

    private val logger = LoggerFactory.getLogger(MOD_ID)
    private val currentServer = AtomicReference<MinecraftServer?>(null)
    private val httpServer = AtomicReference<BoxloomHttpServer?>(null)

    override fun onInitialize() {
        logger.info("Initializing boxloom")

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            currentServer.set(server)
            logger.info("boxloom attached to a started Minecraft server")
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            currentServer.compareAndSet(server, null)
            logger.info("boxloom detached from the stopped Minecraft server")
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
        httpServer.getAndSet(null)?.close()
    }
}
