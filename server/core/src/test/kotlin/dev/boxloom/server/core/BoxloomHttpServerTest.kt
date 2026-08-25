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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
    fun `deserializes and serializes JSON through Ktor`() {
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

    private fun withServer(
        authToken: String?,
        events: BoxloomEventBroker = BoxloomEventBroker(),
        minecraft: MinecraftOperations = TestMinecraftOperations,
        block: (BoxloomHttpServer, BoxloomEventBroker) -> Unit,
    ) {
        val config = BoxloomConfig(
            InetAddress.getLoopbackAddress(),
            0,
            authToken,
            Duration.ofSeconds(1),
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

    private object TestMinecraftOperations : MinecraftOperations {
        override suspend fun players(): List<Player> = emptyList()

        override suspend fun say(request: SayRequest): SayResult =
            throw AssertionError("Unexpected say request")

        override suspend fun playerPosition(username: String): PlayerPosition =
            throw AssertionError("Unexpected position request")

        override suspend fun setBlock(request: SetBlockRequest): SetBlockResult =
            throw AssertionError("Unexpected set-block request")
    }

    private object SayMinecraftOperations : MinecraftOperations {
        override suspend fun say(request: SayRequest): SayResult =
            SayResult(request.message, 2)

        override suspend fun players(): List<Player> =
            throw AssertionError("Unexpected players request")

        override suspend fun playerPosition(username: String): PlayerPosition =
            throw AssertionError("Unexpected position request")

        override suspend fun setBlock(request: SetBlockRequest): SetBlockResult =
            throw AssertionError("Unexpected set-block request")
    }

    companion object {
        private const val TEST_UUID = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        private const val OTHER_UUID = "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7"
    }
}
