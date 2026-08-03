package dev.boxloom.server.core

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class BoxloomHttpServerTest {
    @Test
    fun `accepts a loopback request when authentication is disabled`() {
        withServer(authToken = null) { server ->
            val response = request(server)

            assertEquals(200, response.statusCode())
            assertEquals("{\"players\":[]}", response.body())
        }
    }

    @Test
    fun `requires the configured bearer token`() {
        withServer(authToken = "test-secret") { server ->
            assertEquals(401, request(server).statusCode())
            assertEquals(200, request(server, "test-secret").statusCode())
        }
    }

    private fun withServer(
        authToken: String?,
        block: (BoxloomHttpServer) -> Unit,
    ) {
        val config = BoxloomConfig(
            InetAddress.getLoopbackAddress(),
            0,
            authToken,
            Duration.ofSeconds(1),
        )
        val server = BoxloomHttpServer(config, TestMinecraftOperations)

        server.use {
            it.start()
            block(it)
        }
    }

    private fun request(
        server: BoxloomHttpServer,
        authToken: String? = null,
    ): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:${server.boundPort}/v1/players"))
            .GET()
        authToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

        return HttpClient.newHttpClient().send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private object TestMinecraftOperations : MinecraftOperations {
        override fun players(): CompletableFuture<List<Player>> =
            CompletableFuture.completedFuture(emptyList())

        override fun say(request: SayRequest): CompletableFuture<SayResult> =
            CompletableFuture.failedFuture(AssertionError("Unexpected say request"))

        override fun playerPosition(username: String): CompletableFuture<PlayerPosition> =
            CompletableFuture.failedFuture(AssertionError("Unexpected position request"))

        override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> =
            CompletableFuture.failedFuture(AssertionError("Unexpected set-block request"))
    }
}
