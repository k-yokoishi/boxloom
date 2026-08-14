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

    @Test
    fun `accepts a summon request with nested nbt`() {
        var capturedRequest: SummonRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun summon(request: SummonRequest): CompletableFuture<SummonResult> {
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    SummonResult(
                        "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                        "minecraft:arrow",
                        "minecraft:overworld",
                        1.5,
                        72.0,
                        -2.25,
                    ),
                )
            }
        }

        withServer(authToken = null, minecraft = minecraft) { server ->
            val response = post(
                server,
                "/v1/world/entities",
                """{
                    "dimension":"minecraft:overworld",
                    "entity":"minecraft:arrow",
                    "x":1.5,
                    "y":72,
                    "z":-2.25,
                    "nbt":{
                        "Motion":[0.0,-1.5,0.0],
                        "Rotation":[0.0,90.0],
                        "NoGravity":true,
                        "CustomName":"test arrow",
                        "Life":3,
                        "Seed":2147483648
                    }
                }""".trimIndent(),
            )

            assertEquals(200, response.statusCode())
            assertEquals(
                """{"uuid":"58f6e634-15d9-4d4c-8ca0-8a4b23fe38af","entity":"minecraft:arrow","dimension":"minecraft:overworld","x":1.5,"y":72.0,"z":-2.25}""",
                response.body(),
            )
        }

        assertEquals(
            SummonRequest(
                "minecraft:overworld",
                "minecraft:arrow",
                1.5,
                72.0,
                -2.25,
                NbtValue.Compound(
                    mapOf(
                        "Motion" to NbtValue.ListValue(
                            listOf(
                                NbtValue.DoubleValue(0.0),
                                NbtValue.DoubleValue(-1.5),
                                NbtValue.DoubleValue(0.0),
                            ),
                        ),
                        "Rotation" to NbtValue.ListValue(
                            listOf(
                                NbtValue.DoubleValue(0.0),
                                NbtValue.DoubleValue(90.0),
                            ),
                        ),
                        "NoGravity" to NbtValue.BooleanValue(true),
                        "CustomName" to NbtValue.StringValue("test arrow"),
                        "Life" to NbtValue.IntValue(3),
                        "Seed" to NbtValue.LongValue(2_147_483_648),
                    ),
                ),
            ),
            capturedRequest,
        )
    }

    @Test
    fun `rejects null inside summon nbt`() {
        withServer(authToken = null) { server ->
            val response = post(
                server,
                "/v1/world/entities",
                """{"dimension":"minecraft:overworld","entity":"minecraft:pig","x":0,"y":64,"z":0,"nbt":{"CustomName":null}}""",
            )

            assertEquals(400, response.statusCode())
        }
    }

    private fun withServer(
        authToken: String?,
        minecraft: MinecraftOperations = TestMinecraftOperations,
        block: (BoxloomHttpServer) -> Unit,
    ) {
        val config = BoxloomConfig(
            InetAddress.getLoopbackAddress(),
            0,
            authToken,
            Duration.ofSeconds(1),
        )
        val server = BoxloomHttpServer(config, minecraft)

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

    private fun post(
        server: BoxloomHttpServer,
        path: String,
        body: String,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return HttpClient.newHttpClient().send(
            request,
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

        override fun summon(request: SummonRequest): CompletableFuture<SummonResult> =
            CompletableFuture.failedFuture(AssertionError("Unexpected summon request"))
    }
}
