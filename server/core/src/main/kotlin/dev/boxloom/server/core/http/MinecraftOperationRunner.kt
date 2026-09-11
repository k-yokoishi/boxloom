package dev.boxloom.server.core.http

import dev.boxloom.server.core.ApiException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class MinecraftOperationRunner(
    private val timeout: Duration,
) {
    fun <T> await(future: CompletableFuture<T>): T {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            future.cancel(false)
            throw ApiException(504, "TIMEOUT", "The Minecraft server did not respond in time")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiException(500, "INTERNAL_ERROR", "The boxloom request was interrupted")
        } catch (exception: ExecutionException) {
            val cause = exception.cause

            if (cause is ApiException) {
                throw cause
            }

            LOGGER.log(
                System.Logger.Level.ERROR,
                "Minecraft server-thread operation failed",
                cause ?: exception,
            )
            throw ApiException(500, "INTERNAL_ERROR", "The Minecraft operation failed")
        }
    }

    companion object {
        private val LOGGER = System.getLogger("boxloom-http")
    }
}
