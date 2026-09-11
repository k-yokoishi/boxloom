package dev.boxloom.server.core

import com.sun.net.httpserver.HttpServer
import dev.boxloom.server.core.http.BoxloomHttpApplication
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoxloomHttpServer(
    private val config: BoxloomConfig,
    minecraft: MinecraftOperations,
    private val events: BoxloomEventBroker,
) : AutoCloseable {
    private val application = BoxloomHttpApplication(config, minecraft, events)
    private val server = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0)
    private val executor: ExecutorService =
        Executors.newVirtualThreadPerTaskExecutor()

    init {
        server.createContext("/", application::handle)
        server.executor = executor
    }

    internal val boundPort: Int
        get() = server.address.port

    fun start() {
        server.start()
        LOGGER.log(
            System.Logger.Level.INFO,
            "boxloom HTTP server listening on http://${config.displayHost}:$boundPort",
        )
    }

    override fun close() {
        events.close()
        server.stop(1)
        executor.shutdown()

        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }

    companion object {
        private val LOGGER = System.getLogger("boxloom-http")
    }
}
