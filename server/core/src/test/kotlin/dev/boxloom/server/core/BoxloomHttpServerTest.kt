package dev.boxloom.server.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxloomHttpServerTest {
    @Test
    fun `accepts a loopback request when authentication is disabled`() {
        withServer(authToken = null) { server, _ ->
            val response = request(server)

            assertEquals(200, response.statusCode())
            assertEquals("{\"players\":[]}", response.body())
        }
    }

    @Test
    fun `requires the configured bearer token`() {
        withServer(authToken = "test-secret") { server, _ ->
            val missing = request(server)
            assertEquals(401, missing.statusCode())
            assertContains(missing.body(), "UNAUTHORIZED")
            assertEquals(200, request(server, "test-secret").statusCode())
        }
    }

    @Test
    fun `rejects an invalid bearer token as forbidden`() {
        withServer(authToken = "test-secret") { server, _ ->
            val response = request(server, "wrong-secret")

            assertEquals(403, response.statusCode())
            assertContains(response.body(), "FORBIDDEN")
        }
    }

    @Test
    fun `deserializes and serializes JSON`() {
        withServer(authToken = null, minecraft = SayMinecraftOperations) { server, _ ->
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/chat/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hello\"}"))
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertEquals("{\"message\":\"hello\",\"recipients\":2}", response.body())
        }
    }

    @Test
    fun `times out a Minecraft server thread operation`() {
        val pending = CompletableFuture<List<Player>>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun players(): CompletableFuture<List<Player>> = pending
        }

        withServer(
            authToken = null,
            minecraft = minecraft,
            requestTimeout = Duration.ofMillis(100),
        ) { server, _ ->
            val response = request(server)

            assertEquals(504, response.statusCode())
            assertContains(response.body(), "TIMEOUT")
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun `rejects a JSON request body larger than 16 KiB`() {
        withServer(authToken = null, minecraft = SayMinecraftOperations) { server, _ ->
            val response = post(
                server,
                "/v1/chat/messages",
                "{\"message\":\"${"a".repeat(16 * 1_024)}\"}",
            )

            assertEquals(413, response.statusCode())
            assertContains(response.body(), "REQUEST_TOO_LARGE")
        }
    }

    @Test
    fun `teleports a player with optional destination fields`() {
        var capturedUsername: String? = null
        var capturedRequest: TeleportPlayerRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun teleportPlayer(
                username: String,
                request: TeleportPlayerRequest,
            ): CompletableFuture<PlayerPosition> {
                capturedUsername = username
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    PlayerPosition(
                        "Player_123",
                        TEST_UUID,
                        "minecraft:the_nether",
                        1.5,
                        72.0,
                        -2.25,
                        90.0f,
                        -12.5f,
                    ),
                )
            }
        }

        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(
                server,
                "/v1/players/Player_123/teleport",
                """{
                    "x":1.5,
                    "y":72,
                    "z":-2.25,
                    "dimension":"minecraft:the_nether",
                    "yaw":90
                }""".trimIndent(),
            )

            assertEquals(200, response.statusCode())
            assertEquals(
                """{"username":"Player_123","uuid":"$TEST_UUID","dimension":"minecraft:the_nether","x":1.5,"y":72.0,"z":-2.25,"yaw":90.0,"pitch":-12.5}""",
                response.body(),
            )
        }

        assertEquals("Player_123", capturedUsername)
        assertEquals(
            TeleportPlayerRequest(
                1.5,
                72.0,
                -2.25,
                dimension = "minecraft:the_nether",
                yaw = 90.0,
                pitch = null,
            ),
            capturedRequest,
        )
    }

    @Test
    fun `teleport preserves optional fields when they are omitted`() {
        var capturedRequest: TeleportPlayerRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun teleportPlayer(
                username: String,
                request: TeleportPlayerRequest,
            ): CompletableFuture<PlayerPosition> {
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    PlayerPosition(
                        username,
                        TEST_UUID,
                        "minecraft:overworld",
                        request.x,
                        request.y,
                        request.z,
                        0.0f,
                        0.0f,
                    ),
                )
            }
        }

        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(
                server,
                "/v1/players/Steve/teleport",
                """{"x":100,"y":64,"z":-20}""",
            )

            assertEquals(200, response.statusCode())
        }

        assertEquals(
            TeleportPlayerRequest(100.0, 64.0, -20.0),
            capturedRequest,
        )
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

        withServer(authToken = null, minecraft = minecraft) { server, _ ->
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
        withServer(authToken = null) { server, _ ->
            val response = post(
                server,
                "/v1/world/entities",
                """{"dimension":"minecraft:overworld","entity":"minecraft:pig","x":0,"y":64,"z":0,"nbt":{"CustomName":null}}""",
            )

            assertEquals(400, response.statusCode())
            assertContains(response.body(), "NBT values cannot be null")
        }
    }

    @Test
    fun `returns structured method and route errors`() {
        withServer(authToken = null) { server, _ ->
            val wrongMethod = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/chat/messages"))
                .GET()
                .build()
            val methodResponse = HttpClient.newHttpClient().send(
                wrongMethod,
                HttpResponse.BodyHandlers.ofString(),
            )
            val unknownRoute = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/missing"))
                .GET()
                .build()
            val routeResponse = HttpClient.newHttpClient().send(
                unknownRoute,
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(405, methodResponse.statusCode())
            assertEquals("POST", methodResponse.headers().firstValue("Allow").orElseThrow())
            assertContains(methodResponse.body(), "METHOD_NOT_ALLOWED")
            assertEquals(404, routeResponse.statusCode())
            assertContains(routeResponse.body(), "ROUTE_NOT_FOUND")
        }
    }

    @Test
    fun `requires an event stream compatible accept header`() {
        withServer(authToken = null) { server, _ ->
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(406, response.statusCode())
            assertContains(response.body(), "NOT_ACCEPTABLE")
        }
    }

    @Test
    fun `streams chat messages as server sent events`() {
        withServer(authToken = null) { server, events ->
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
            )

            assertEquals(200, response.statusCode())
            assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElseThrow(),
            )

            response.body().use { body ->
                val reader = BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8))
                val ready = readSseEvent(reader)
                assertEquals("stream.ready", ready["event"])

                val published = events.publishChatMessage("hello", "Steve", TEST_UUID)
                val chat = readSseEvent(reader)

                assertEquals("chat.message", chat["event"])
                assertEquals(published.id, chat["id"])
                assertContains(chat.getValue("data"), "\"message\":\"hello\"")
                assertContains(chat.getValue("data"), "\"username\":\"Steve\"")
            }
        }
    }

    @Test
    fun `rejects an event cursor whose retained history was evicted`() {
        val events = BoxloomEventBroker(capacity = 1)
        val cursor = events.openCursor(null)
        events.publishChatMessage("one", "Steve", TEST_UUID)
        events.publishChatMessage("two", "Steve", TEST_UUID)

        withServer(authToken = null, events = events) { server, _ ->
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", cursor.toString())
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(410, response.statusCode())
            assertContains(response.body(), "EVENT_CURSOR_EXPIRED")
        }
    }

    @Test
    fun `replays retained chat messages after last event id`() {
        val events = BoxloomEventBroker()
        val acknowledged = events.publishChatMessage("one", "Steve", TEST_UUID)
        val replayed = events.publishChatMessage("two", "Alex", OTHER_UUID)

        withServer(authToken = null, events = events) { server, _ ->
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", acknowledged.id)
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
            )

            response.body().use { body ->
                val reader = BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8))
                assertEquals("stream.ready", readSseEvent(reader)["event"])

                val chat = readSseEvent(reader)
                assertEquals("chat.message", chat["event"])
                assertEquals(replayed.id, chat["id"])
                assertContains(chat.getValue("data"), "\"message\":\"two\"")
            }
        }
    }

    @Test
    fun `resets an open stream when the server session changes`() {
        withServer(authToken = null) { server, events ->
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
            )

            response.body().use { body ->
                val reader = BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8))
                assertEquals("stream.ready", readSseEvent(reader)["event"])

                events.reset()
                val reset = readSseEvent(reader)

                assertEquals("stream.reset", reset["event"])
                assertContains(reset.getValue("data"), "EVENT_CURSOR_EXPIRED")
            }
        }
    }

    @Test
    fun `closing the server terminates an open event stream`() {
        val events = BoxloomEventBroker()
        val config = BoxloomConfig(
            InetAddress.getLoopbackAddress(),
            0,
            null,
            Duration.ofSeconds(1),
        )
        val server = BoxloomHttpServer(config, TestMinecraftOperations, events)
        server.start()

        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofInputStream(),
        )

        response.body().use { body ->
            val reader = BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8))
            assertEquals("stream.ready", readSseEvent(reader)["event"])

            server.close()

            assertEquals(null, reader.readLine())
        }
    }

    private fun withServer(
        authToken: String?,
        events: BoxloomEventBroker = BoxloomEventBroker(),
        minecraft: MinecraftOperations = TestMinecraftOperations,
        requestTimeout: Duration = Duration.ofSeconds(1),
        block: (BoxloomHttpServer, BoxloomEventBroker) -> Unit,
    ) {
        val config = BoxloomConfig(
            InetAddress.getLoopbackAddress(),
            0,
            authToken,
            requestTimeout,
        )
        val server = BoxloomHttpServer(config, minecraft, events)

        server.use {
            it.start()
            block(it, events)
        }
    }

    private fun readSseEvent(reader: BufferedReader): Map<String, String> {
        while (true) {
            val fields = mutableMapOf<String, String>()
            val data = mutableListOf<String>()

            while (true) {
                val line = reader.readLine() ?: error("The event stream ended unexpectedly")
                if (line.isEmpty()) break
                if (line.startsWith(':')) continue

                val separator = line.indexOf(':')
                val name = if (separator == -1) line else line.substring(0, separator)
                val value = if (separator == -1) "" else line.substring(separator + 1).trimStart()
                if (name == "data") {
                    data.add(value)
                } else {
                    fields[name] = value
                }
            }

            if (data.isNotEmpty()) {
                fields["data"] = data.joinToString("\n")
                return fields
            }
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
            throw AssertionError("Unexpected say request")

        override fun playerPosition(username: String): CompletableFuture<PlayerPosition> =
            throw AssertionError("Unexpected position request")

        override fun teleportPlayer(
            username: String,
            request: TeleportPlayerRequest,
        ): CompletableFuture<PlayerPosition> =
            throw AssertionError("Unexpected teleport request")

        override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> =
            throw AssertionError("Unexpected set-block request")

        override fun summon(request: SummonRequest): CompletableFuture<SummonResult> =
            throw AssertionError("Unexpected summon request")
    }

    private object SayMinecraftOperations : MinecraftOperations {
        override fun say(request: SayRequest): CompletableFuture<SayResult> =
            CompletableFuture.completedFuture(SayResult(request.message, 2))

        override fun players(): CompletableFuture<List<Player>> =
            throw AssertionError("Unexpected players request")

        override fun playerPosition(username: String): CompletableFuture<PlayerPosition> =
            throw AssertionError("Unexpected position request")

        override fun teleportPlayer(
            username: String,
            request: TeleportPlayerRequest,
        ): CompletableFuture<PlayerPosition> =
            throw AssertionError("Unexpected teleport request")

        override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> =
            throw AssertionError("Unexpected set-block request")

        override fun summon(request: SummonRequest): CompletableFuture<SummonResult> =
            throw AssertionError("Unexpected summon request")
    }

    companion object {
        private const val TEST_UUID = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        private const val OTHER_UUID = "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7"
    }
}
