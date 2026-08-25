package dev.boxloom.server.core

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

class BoxloomHttpServer(
    private val config: BoxloomConfig,
    minecraft: MinecraftOperations,
    private val events: BoxloomEventBroker,
) : AutoCloseable {
    private val server = embeddedServer(
        factory = CIO,
        host = config.displayHost,
        port = config.port,
    ) {
        configureBoxloom(config, minecraft, events)
    }

    @Volatile
    private var resolvedPort = config.port

    internal val boundPort: Int
        get() = resolvedPort

    fun start() {
        server.start(wait = false)
        resolvedPort = runBlocking {
            server.engine.resolvedConnectors().single().port
        }
        LOGGER.log(
            System.Logger.Level.INFO,
            "boxloom HTTP server listening on http://${config.displayHost}:$resolvedPort",
        )
    }

    override fun close() {
        events.close()
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
    }

    companion object {
        private val LOGGER = System.getLogger("boxloom-http")
    }
}
