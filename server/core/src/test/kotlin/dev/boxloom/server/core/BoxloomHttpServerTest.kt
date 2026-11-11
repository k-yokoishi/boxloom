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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

class BoxloomHttpServerTest {
    @Test
    fun `selects hotbar slots with authentication and serializes selection`() {
        val calls = mutableListOf<Int>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun selectPlayerSlot(username: String, request: SelectSlotRequest): CompletableFuture<SelectSlotResult> {
                assertEquals("Steve", username)
                calls += request.slot
                return CompletableFuture.completedFuture(SelectSlotResult(username, TEST_UUID, request.slot))
            }
        }
        withServer(authToken = "test-secret", minecraft = minecraft) { server, _ ->
            val path = "/v1/players/Steve/select-slot"
            assertEquals(401, post(server, path, """{"slot":0}""").statusCode())
            assertEquals(403, post(server, path, """{"slot":0}""", "wrong").statusCode())
            assertEquals(405, get(server, path, "test-secret").statusCode())
            val wrongType = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
                .header("Authorization", "Bearer test-secret").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("""{"slot":0}""")).build()
            assertEquals(415, HttpClient.newHttpClient().send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode())
            for (slot in listOf(0, 8, 8)) {
                val response = post(server, path, """{"slot":$slot}""", "test-secret")
                assertEquals(200, response.statusCode())
                assertEquals("""{"username":"Steve","uuid":"$TEST_UUID","slot":$slot}""", response.body())
            }
        }
        assertEquals(listOf(0, 8, 8), calls)
    }

    @ParameterizedTest
    @MethodSource("invalidSelectSlotRequests")
    fun `rejects invalid select-slot requests before invoking Minecraft`(path: String, body: String) {
        withServer(authToken = null) { server, _ ->
            val response = post(server, path, body)
            assertEquals(400, response.statusCode(), "$path: $body")
            assertContains(response.body(), "INVALID_REQUEST")
        }
    }

    @Test
    fun `select-slot preserves errors and cancels timed out futures`() {
        for ((status, code) in listOf(404 to "PLAYER_NOT_CONNECTED", 503 to "WORLD_NOT_LOADED")) {
            val minecraft = object : MinecraftOperations by TestMinecraftOperations {
                override fun selectPlayerSlot(username: String, request: SelectSlotRequest): CompletableFuture<SelectSlotResult> =
                    CompletableFuture.failedFuture(ApiException(status, code, "Cannot select slot"))
            }
            withServer(authToken = null, minecraft = minecraft) { server, _ ->
                val response = post(server, "/v1/players/Steve/select-slot", """{"slot":0}""")
                assertEquals(status, response.statusCode())
                assertContains(response.body(), code)
            }
        }
        val pending = CompletableFuture<SelectSlotResult>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun selectPlayerSlot(username: String, request: SelectSlotRequest): CompletableFuture<SelectSlotResult> = pending
        }
        withServer(authToken = null, minecraft = minecraft, requestTimeout = Duration.ofMillis(100)) { server, _ ->
            assertEquals(504, post(server, "/v1/players/Steve/select-slot", """{"slot":0}""").statusCode())
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun `starts and releases API item use with authentication and native state snapshots`() {
        var result = PlayerItemUseResult(true, "minecraft:apple", 32)
        val calls = mutableListOf<String>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> {
                calls += "start:$username"
                return CompletableFuture.completedFuture(result)
            }
            override fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> {
                calls += "stop:$username"
                return CompletableFuture.completedFuture(PlayerItemUseResult(false, null, 0))
            }
        }
        withServer(authToken = "test-secret", minecraft = minecraft) { server, _ ->
            for (action in listOf("start", "stop")) {
                val path = "/v1/players/Steve/item-use/$action"
                assertEquals(401, post(server, path, "{}").statusCode())
                assertEquals(403, post(server, path, "{}", "wrong").statusCode())
                assertEquals(405, get(server, path, "test-secret").statusCode())
                val request = HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
                    .header("Authorization", "Bearer test-secret").header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                assertEquals(415, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
            }
            val response = post(server, "/v1/players/Steve/item-use/start", "{}", "test-secret")
            assertEquals(200, response.statusCode())
            assertEquals("{\"active\":true,\"item\":\"minecraft:apple\",\"remaining_ticks\":32}", response.body())
            result = PlayerItemUseResult(false, null, 0)
            for (action in listOf("start", "stop")) {
                val inactive = post(server, "/v1/players/Steve/item-use/$action", "{}", "test-secret")
                assertEquals(200, inactive.statusCode())
                assertEquals("{\"active\":false,\"item\":null,\"remaining_ticks\":0}", inactive.body())
            }
        }
        assertEquals(listOf("start:Steve", "start:Steve", "stop:Steve"), calls)
    }

    @Test
    fun `item use endpoints preserve errors and cancel timed out futures`() {
        for ((status, code) in listOf(404 to "PLAYER_NOT_CONNECTED", 409 to "PLAYER_CANNOT_USE",
            409 to "ITEM_NOT_CONTINUOUSLY_USABLE", 503 to "WORLD_NOT_LOADED")) {
            val minecraft = object : MinecraftOperations by TestMinecraftOperations {
                override fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
                    CompletableFuture.failedFuture(ApiException(status, code, "Cannot use"))
                override fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
                    CompletableFuture.failedFuture(ApiException(status, code, "Cannot use"))
            }
            withServer(authToken = null, minecraft = minecraft) { server, _ ->
                for (action in listOf("start", "stop")) {
                    val response = post(server, "/v1/players/Steve/item-use/$action", "{}")
                    assertEquals(status, response.statusCode())
                    assertContains(response.body(), code)
                }
            }
        }
        for (action in listOf("start", "stop")) {
            val pending = CompletableFuture<PlayerItemUseResult>()
            val minecraft = object : MinecraftOperations by TestMinecraftOperations {
                override fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> = pending
                override fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> = pending
            }
            withServer(authToken = null, minecraft = minecraft, requestTimeout = Duration.ofMillis(100)) { server, _ ->
                assertEquals(504, post(server, "/v1/players/Steve/item-use/$action", "{}").statusCode())
                assertTrue(pending.isCancelled)
            }
        }
    }


    @ParameterizedTest
    @MethodSource("giveRequests")
    fun `gives an item with an optional count`(body: String, count: Int) {
        var captured: Pair<String, GiveItemRequest>? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun givePlayerItem(username: String, request: GiveItemRequest): CompletableFuture<GiveItemResult> {
                captured = username to request
                val slots = if (request.count == 1) emptyList() else listOf(GivenItemSlot(2, 4), GivenItemSlot(9, 61))
                return CompletableFuture.completedFuture(GiveItemResult(username, TEST_UUID, "minecraft:stone", request.count, slots))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(server, "/v1/players/Steve/give", body)
            assertEquals(200, response.statusCode())
            assertEquals("Steve" to GiveItemRequest("stone", count), captured)
            val slotsJson = if (count == 1) "\"slot\":null,\"slots\":[]" else
                "\"slot\":2,\"slots\":[{\"slot\":2,\"count\":4},{\"slot\":9,\"count\":61}]"
            assertEquals("""{"username":"Steve","uuid":"$TEST_UUID","item":"minecraft:stone","count":$count,$slotsJson}""", response.body())
        }
    }

    @ParameterizedTest
    @MethodSource("invalidGiveRequests")
    fun `validates give requests before invoking Minecraft`(path: String, body: String) {
        withServer(authToken = null) { server, _ ->
            val response = post(server, path, body)
            assertEquals(400, response.statusCode(), "$path: $body")
            assertContains(response.body(), "INVALID_REQUEST")
        }
    }

    @ParameterizedTest
    @MethodSource("giveErrors")
    fun `propagates give errors without changing their codes`(status: Int, code: String) {
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun givePlayerItem(username: String, request: GiveItemRequest): CompletableFuture<GiveItemResult> =
                CompletableFuture.failedFuture(ApiException(status, code, "Cannot give item"))
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(server, "/v1/players/Steve/give", """{"item":"stone"}""")
            assertEquals(status, response.statusCode())
            assertContains(response.body(), code)
        }
    }

    @Test
    fun `uses a main hand item and serializes block entity and miss results`() {
        val target = TargetedBlock("minecraft:overworld", 1, 64, 3, "minecraft:wheat", "north", 2.5)
        val entity = EntitySnapshot(TEST_UUID, "minecraft:cow", "minecraft:overworld", 1.5, 64.0, 3.0, 0f, 0f, "Cow", 10f)
        var result = PlayerUseResult(true, true, target)
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult> {
                assertEquals("Steve", username)
                return CompletableFuture.completedFuture(result)
            }
        }
        withServer(authToken = "test-secret", minecraft = minecraft) { server, _ ->
            val path = "/v1/players/Steve/use"
            assertEquals(401, post(server, path, "{}").statusCode())
            assertEquals(403, post(server, path, "{}", "wrong").statusCode())
            assertEquals(405, get(server, path, "test-secret").statusCode())
            val used = post(server, path, "{}", "test-secret")
            assertEquals(200, used.statusCode())
            assertContains(used.body(), "\"used\":true")
            assertContains(used.body(), "minecraft:wheat")
            assertContains(used.body(), "\"entity\":null")
            result = PlayerUseResult(false, false, null)
            assertEquals("{\"used\":false,\"swung\":false,\"target\":null,\"entity\":null}",
                post(server, path, "{}", "test-secret").body())
            result = PlayerUseResult(true, false, null, entity)
            assertContains(post(server, path, "{}", "test-secret").body(), "minecraft:cow")
            val wrongType = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
                .header("Authorization", "Bearer test-secret")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
            assertEquals(415, HttpClient.newHttpClient().send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode())
        }
    }

    @Test
    fun `preserves use errors and cancels a timed out use`() {
        for ((status, code) in listOf(404 to "PLAYER_NOT_CONNECTED", 409 to "PLAYER_CANNOT_USE", 503 to "WORLD_NOT_LOADED")) {
            val minecraft = object : MinecraftOperations by TestMinecraftOperations {
                override fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult> =
                    CompletableFuture.failedFuture(ApiException(status, code, "Cannot use"))
            }
            withServer(authToken = null, minecraft = minecraft) { server, _ ->
                val response = post(server, "/v1/players/Steve/use", "{}")
                assertEquals(status, response.statusCode())
                assertContains(response.body(), code)
            }
        }
        val pending = CompletableFuture<PlayerUseResult>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult> = pending
        }
        withServer(authToken = null, minecraft = minecraft, requestTimeout = Duration.ofMillis(100)) { server, _ ->
            assertEquals(504, post(server, "/v1/players/Steve/use", "{}").statusCode())
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun `starts and stops asynchronous mining and serializes entity attacks`() {
        val target = TargetedBlock("minecraft:overworld", 1, 64, 3, "minecraft:dirt", "north", 2.5)
        var miningResult = PlayerMiningResult(true, target, false)
        val calls = mutableListOf<String>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun startMining(username: String): CompletableFuture<PlayerMiningResult> {
                calls.add("start:$username")
                return CompletableFuture.completedFuture(miningResult)
            }
            override fun stopMining(username: String): CompletableFuture<PlayerMiningResult> {
                calls.add("stop:$username")
                return CompletableFuture.completedFuture(PlayerMiningResult(false, null, false))
            }
            override fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult> =
                CompletableFuture.completedFuture(PlayerAttackResult(true, null, false, EntitySnapshot(
                    TEST_UUID, "minecraft:zombie", "minecraft:overworld", 1.5, 64.0, 3.0, 0f, 0f, "Zombie", 19f,
                )))
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val start = post(server, "/v1/players/Steve/mining/start", "{}")
            assertEquals(200, start.statusCode())
            assertContains(start.body(), "\"active\":true")
            assertContains(start.body(), "minecraft:dirt")
            miningResult = PlayerMiningResult(true, null, false)
            assertEquals("{\"active\":true,\"target\":null,\"broken\":false}",
                post(server, "/v1/players/Steve/mining/start", "{}").body())
            miningResult = PlayerMiningResult(true, target, true)
            val instant = post(server, "/v1/players/Steve/mining/start", "{}")
            assertContains(instant.body(), "\"active\":true")
            assertContains(instant.body(), "\"broken\":true")
            val stop = post(server, "/v1/players/Steve/mining/stop", "{}")
            assertEquals(200, stop.statusCode())
            assertEquals("{\"active\":false,\"target\":null,\"broken\":false}", stop.body())
            val attack = post(server, "/v1/players/Steve/attack", "{}")
            assertEquals(200, attack.statusCode())
            val entity = Json.parseToJsonElement(attack.body()).jsonObject["entity"]!!.jsonObject
            assertEquals("minecraft:zombie", entity["entity"]!!.jsonPrimitive.content)
            assertEquals("19.0", entity["health"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf("start:Steve", "start:Steve", "start:Steve", "stop:Steve"), calls)
    }

    @Test
    fun `reads a targeted block or null and performs one attack`() {
        val target = TargetedBlock("minecraft:overworld", -1, 64, 3, "minecraft:stone", "north", 2.5)
        var selected: TargetedBlock? = target
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun targetedBlock(username: String): CompletableFuture<TargetedBlock?> {
                assertEquals("Steve", username)
                return CompletableFuture.completedFuture(selected)
            }
            override fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult> {
                assertEquals("Steve", username)
                return CompletableFuture.completedFuture(PlayerAttackResult(true, selected, false))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val read = get(server, "/v1/players/Steve/targeted-block")
            assertEquals(200, read.statusCode())
            val json = Json.parseToJsonElement(read.body()).jsonObject["target"]!!.jsonObject
            assertEquals("north", json["face"]!!.jsonPrimitive.content)
            assertEquals("-1", json["x"]!!.jsonPrimitive.content)
            assertEquals("2.5", json["distance"]!!.jsonPrimitive.content)
            val attack = post(server, "/v1/players/Steve/attack", "{}")
            assertEquals(200, attack.statusCode())
            assertContains(attack.body(), "\"swung\":true")
            assertContains(attack.body(), "\"broken\":false")
            selected = null
            assertEquals("{\"target\":null}", get(server, "/v1/players/Steve/targeted-block").body())
            assertEquals("{\"swung\":true,\"target\":null,\"broken\":false}",
                post(server, "/v1/players/Steve/attack", "{}").body())
        }
    }

    @Test
    fun `validates attack requests before invoking Minecraft`() {
        withServer(authToken = null) { server, _ ->
            for (path in listOf("/targeted-block", "/attack", "/use", "/mining/start", "/mining/stop", "/item-use/start", "/item-use/stop")) {
                val response = if (path == "/targeted-block") get(server, "/v1/players/ab$path")
                    else post(server, "/v1/players/ab$path", "{}")
                assertEquals(400, response.statusCode())
            }
            for (path in listOf("attack", "use", "mining/start", "mining/stop", "item-use/start", "item-use/stop")) {
                for (body in listOf("{\"x\":0}", "null", "[]", "")) {
                    assertEquals(400, post(server, "/v1/players/Steve/$path", body).statusCode())
                }
                assertEquals(400, post(server, "/v1/players/Steve/$path?x=1", "{}").statusCode())
            }
            assertEquals(400, get(server, "/v1/players/Steve/targeted-block?distance=100").statusCode())
            assertEquals(400, post(server, "/v1/players/Steve/attack?x=1", "{}").statusCode())
        }
    }

    @Test
    fun `propagates player lookup and attack state failures`() {
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun targetedBlock(username: String): CompletableFuture<TargetedBlock?> =
                CompletableFuture.failedFuture(ApiException(404, "PLAYER_NOT_CONNECTED", "offline"))
            override fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult> =
                CompletableFuture.failedFuture(ApiException(409, "PLAYER_CANNOT_ATTACK", "spectator"))
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val missing = get(server, "/v1/players/Steve/targeted-block")
            assertEquals(404, missing.statusCode())
            assertContains(missing.body(), "PLAYER_NOT_CONNECTED")
            val invalid = post(server, "/v1/players/Steve/attack", "{}")
            assertEquals(409, invalid.statusCode())
            assertContains(invalid.body(), "PLAYER_CANNOT_ATTACK")
        }
    }

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
    fun `reads a connected player location with authentication and rotation`() {
        val usernames = mutableListOf<String>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun playerLocation(username: String): CompletableFuture<PlayerLocation> {
                usernames += username
                return CompletableFuture.completedFuture(
                    PlayerLocation("Steve", TEST_UUID, "minecraft:the_nether", -0.2, 70.25, -3.2, 90f, -12.5f),
                )
            }
        }
        withServer(authToken = "test-secret", minecraft = minecraft) { server, _ ->
            val path = "/v1/players/Steve/location"
            assertEquals(401, get(server, path).statusCode())
            assertTrue(usernames.isEmpty())

            val response = get(server, path, "test-secret")
            assertEquals(200, response.statusCode())
            assertEquals(
                """{"username":"Steve","uuid":"$TEST_UUID","dimension":"minecraft:the_nether","x":-0.2,"y":70.25,"z":-3.2,"yaw":90.0,"pitch":-12.5}""",
                response.body(),
            )
            assertEquals(listOf("Steve"), usernames)

            assertEquals(400, get(server, "/v1/players/ab/location", "test-secret").statusCode())
            assertEquals(listOf("Steve"), usernames)
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
            ): CompletableFuture<PlayerLocation> {
                capturedUsername = username
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    PlayerLocation(
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
            ): CompletableFuture<PlayerLocation> {
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    PlayerLocation(
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

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `sets either player game mode and returns the player snapshot`(mode: GameMode) {
        val requests = mutableListOf<Pair<String, SetPlayerGameModeRequest>>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun setPlayerGameMode(
                username: String,
                request: SetPlayerGameModeRequest,
            ): CompletableFuture<PlayerGameMode> {
                requests += username to request
                return CompletableFuture.completedFuture(PlayerGameMode("Steve", TEST_UUID, request.mode))
            }
        }
        withServer(authToken = "test-secret", minecraft = minecraft) { server, _ ->
            val response = post(
                server, "/v1/players/steve/gamemode", """{"mode":"${mode.value}"}""", "test-secret",
            )
            assertEquals(200, response.statusCode())
            assertEquals("steve" to SetPlayerGameModeRequest(mode), requests.last())
            assertEquals(
                """{"username":"Steve","uuid":"$TEST_UUID","mode":"${mode.value}"}""",
                response.body(),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("invalidGameModeBodies")
    fun `rejects invalid game mode bodies before calling Minecraft`(body: String) {
        withServer(authToken = null) { server, _ ->
            val response = post(server, "/v1/players/Steve/gamemode", body)
            assertEquals(400, response.statusCode(), body)
            assertContains(response.body(), "INVALID_REQUEST")
        }
    }

    @ParameterizedTest
    @MethodSource("invalidGameModeUsernames")
    fun `rejects invalid game mode usernames before calling Minecraft`(username: String) {
        withServer(authToken = null) { server, _ ->
            val response = post(server, "/v1/players/$username/gamemode", """{"mode":"creative"}""")
            assertEquals(400, response.statusCode(), username)
            assertContains(response.body(), "INVALID_REQUEST")
        }
    }

    @Test
    fun `game mode changes require authentication and a JSON POST`() {
        withServer(authToken = "test-secret") { server, _ ->
            val path = "/v1/players/Steve/gamemode"
            assertEquals(401, post(server, path, """{"mode":"creative"}""").statusCode())
            assertEquals(403, post(server, path, """{"mode":"creative"}""", "wrong").statusCode())
            val wrongMethod = get(server, path, "test-secret")
            assertEquals(405, wrongMethod.statusCode())
            assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow())

            val wrongContentType = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
                .header("Authorization", "Bearer test-secret")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("""{"mode":"creative"}"""))
                .build()
            val response = HttpClient.newHttpClient().send(wrongContentType, HttpResponse.BodyHandlers.ofString())
            assertEquals(415, response.statusCode())
            assertContains(response.body(), "UNSUPPORTED_MEDIA_TYPE")
        }
    }

    @ParameterizedTest
    @MethodSource("gameModeErrors")
    fun `preserves structured game mode operation errors`(status: Int, code: String) {
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun setPlayerGameMode(
                username: String,
                request: SetPlayerGameModeRequest,
            ): CompletableFuture<PlayerGameMode> =
                CompletableFuture.failedFuture(ApiException(status, code, "Cannot change mode"))
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(server, "/v1/players/Steve/gamemode", """{"mode":"survival"}""")
            assertEquals(status, response.statusCode())
            assertContains(response.body(), code)
        }
    }

    @Test
    fun `cancels a timed out game mode operation`() {
        val pending = CompletableFuture<PlayerGameMode>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun setPlayerGameMode(
                username: String,
                request: SetPlayerGameModeRequest,
            ): CompletableFuture<PlayerGameMode> = pending
        }
        withServer(authToken = null, minecraft = minecraft, requestTimeout = Duration.ofMillis(100)) { server, _ ->
            val response = post(server, "/v1/players/Steve/gamemode", """{"mode":"creative"}""")
            assertEquals(504, response.statusCode())
            assertContains(response.body(), "TIMEOUT")
            assertTrue(pending.isCancelled)
        }
    }

    @ParameterizedTest
    @MethodSource("clockQueries")
    fun `reads a world clock with default or explicit clock and 64 bit ticks`(path: String, clock: String) {
        val requests = mutableListOf<GetTimeRequest>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun getTime(request: GetTimeRequest): CompletableFuture<TimeResult> {
                requests += request
                return CompletableFuture.completedFuture(TimeResult(request.clock, 3_000_000_000L))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = get(server, path)
            assertEquals(200, response.statusCode())
            assertEquals(
                """{"clock":"$clock","ticks":3000000000}""",
                response.body(),
            )
            assertEquals(GetTimeRequest(clock), requests.last())
        }
    }

    @ParameterizedTest
    @MethodSource("setTimeRequests")
    fun `sets time from a marker or total ticks`(body: String, expected: SetTimeRequest) {
        val requests = mutableListOf<SetTimeRequest>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun setTime(request: SetTimeRequest): CompletableFuture<TimeResult> {
                requests += request
                return CompletableFuture.completedFuture(TimeResult(request.clock, 25_000L))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(server, "/v1/world/time", body)
            assertEquals(200, response.statusCode())
            assertEquals(expected, requests.last())
            assertEquals(
                """{"clock":"${expected.clock}","ticks":25000}""",
                response.body(),
            )
        }
    }

    @Test
    fun `adds signed ticks to the requested clock`() {
        val requests = mutableListOf<AddTimeRequest>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun addTime(request: AddTimeRequest): CompletableFuture<TimeResult> {
                requests += request
                return CompletableFuture.completedFuture(TimeResult(request.clock, 0L))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            for (ticks in listOf(Int.MIN_VALUE, -20, 0, 6000, Int.MAX_VALUE)) {
                val response = post(server, "/v1/world/time/add", """{"ticks":$ticks}""")
                assertEquals(200, response.statusCode())
                assertEquals(AddTimeRequest("minecraft:overworld", ticks), requests.last())
            }
            val response = post(
                server, "/v1/world/time/add", """{"clock":"minecraft:the_end","ticks":20}""",
            )
            assertEquals(200, response.statusCode())
            assertEquals(AddTimeRequest("minecraft:the_end", 20), requests.last())
        }
    }

    @Test
    fun `rejects malformed time requests before scheduling a world change`() {
        withServer(authToken = null) { server, _ ->
            for (body in listOf(
                "{}", """{"time":null}""", """{"time":true}""", """{"time":1.5}""",
                """{"time":1e3}""", """{"time":{}}""", """{"time":[]}""",
                """{"time":-1}""", """{"time":2147483648}""", """{"time":" "}""",
                """{"time":"day","clock":null}""", """{"time":"day","clock":""}""",
                """{"time":"day","extra":1}""",
            )) {
                val response = post(server, "/v1/world/time", body)
                assertEquals(400, response.statusCode(), body)
                assertContains(response.body(), "INVALID_REQUEST")
            }
            for (body in listOf(
                "{}", """{"ticks":null}""", """{"ticks":true}""", """{"ticks":1.5}""",
                """{"ticks":"20"}""", """{"ticks":2147483648}""", """{"ticks":-2147483649}""",
                """{"ticks":20,"clock":false}""", """{"ticks":20,"extra":1}""",
            )) {
                val response = post(server, "/v1/world/time/add", body)
                assertEquals(400, response.statusCode(), body)
                assertContains(response.body(), "INVALID_REQUEST")
            }
            for (query in listOf("clock=", "clock=a&clock=b", "unknown=1")) {
                val response = get(server, "/v1/world/time?$query")
                assertEquals(400, response.statusCode(), query)
                assertContains(response.body(), "INVALID_REQUEST")
            }
        }
    }

    @Test
    fun `time operations require authentication and preserve platform errors`() {
        withServer(authToken = "secret") { server, _ ->
            assertEquals(401, get(server, "/v1/world/time").statusCode())
            assertEquals(401, post(server, "/v1/world/time", """{"time":"day"}""").statusCode())
            assertEquals(401, post(server, "/v1/world/time/add", """{"ticks":20}""").statusCode())
        }
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun setTime(request: SetTimeRequest): CompletableFuture<TimeResult> {
                val error = if (request.time == TimeValue.Marker("day")) {
                    ApiException(409, "TIME_ALREADY_AT_MARKER", "The clock is already at this marker")
                } else {
                    ApiException(404, "TIME_MARKER_NOT_FOUND", "The requested marker does not exist")
                }
                return CompletableFuture.failedFuture(error)
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(server, "/v1/world/time", """{"time":"missing"}""")
            assertEquals(404, response.statusCode())
            assertContains(response.body(), "TIME_MARKER_NOT_FOUND")
            val alreadyAtMarker = post(server, "/v1/world/time", """{"time":"day"}""")
            assertEquals(409, alreadyAtMarker.statusCode())
            assertContains(alreadyAtMarker.body(), "TIME_ALREADY_AT_MARKER")
        }
    }

    @Test
    fun `reads and writes block properties as strings`() {
        var captured: SetBlockRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> {
                captured = request
                return CompletableFuture.completedFuture(SetBlockResult(
                    true, request.dimension, request.x, request.y, request.z, request.block, request.properties,
                ))
            }
            override fun getBlock(request: GetBlockRequest): CompletableFuture<GetBlockResult> =
                CompletableFuture.completedFuture(GetBlockResult(
                    request.dimension, request.x, request.y, request.z, "minecraft:wheat", mapOf("age" to "3"),
                ))
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            for (properties in listOf(null, emptyMap(), mapOf("age" to "3"))) {
                val body = buildJsonObject {
                    put("dimension", JsonPrimitive("minecraft:overworld"))
                    put("x", JsonPrimitive(1))
                    put("y", JsonPrimitive(64))
                    put("z", JsonPrimitive(2))
                    put("block", JsonPrimitive("minecraft:wheat"))
                    if (properties != null) put("properties", buildJsonObject {
                        properties.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
                    })
                }.toString()
                val response = post(server, "/v1/world/blocks", body)
                assertEquals(200, response.statusCode(), response.body())
                assertEquals(properties ?: emptyMap(), captured!!.properties)
                assertEquals(properties ?: emptyMap(), Json.parseToJsonElement(response.body()).jsonObject["properties"]!!
                    .jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content })
            }
            val response = get(server, "/v1/world/blocks?dimension=minecraft:overworld&x=1&y=64&z=2")
            assertEquals(200, response.statusCode())
            val age = Json.parseToJsonElement(response.body()).jsonObject["properties"]!!.jsonObject["age"]!!.jsonPrimitive
            assertTrue(age.isString)
            assertEquals("3", age.content)
        }
    }

    @ParameterizedTest
    @MethodSource("invalidBlockProperties")
    fun `rejects malformed block properties before invoking Minecraft`(properties: String) {
        withServer(authToken = null) { server, _ ->
            val body = """{"dimension":"minecraft:overworld","x":1,"y":64,"z":2,"block":"minecraft:wheat","properties":$properties}"""
            val response = post(server, "/v1/world/blocks", body)
            assertEquals(400, response.statusCode(), body)
            assertContains(response.body(), "INVALID_REQUEST")
        }
    }

    @Test
    fun `gets a block from query parameters`() {
        var capturedRequest: GetBlockRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun getBlock(request: GetBlockRequest): CompletableFuture<GetBlockResult> {
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    GetBlockResult(
                        request.dimension,
                        request.x,
                        request.y,
                        request.z,
                        "minecraft:stone",
                    ),
                )
            }
        }

        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = get(
                server,
                "/v1/world/blocks?dimension=minecraft%3Athe_nether&x=1&y=64&z=-2",
            )

            assertEquals(200, response.statusCode())
            assertEquals(
                """{"dimension":"minecraft:the_nether","x":1,"y":64,"z":-2,"block":"minecraft:stone","properties":{}}""",
                response.body(),
            )
        }

        assertEquals(
            GetBlockRequest("minecraft:the_nether", 1, 64, -2),
            capturedRequest,
        )
    }

    @Test
    fun `rejects invalid get block query parameters`() {
        withServer(authToken = null) { server, _ ->
            val missing = get(
                server,
                "/v1/world/blocks?dimension=minecraft%3Aoverworld&x=1&y=64",
            )
            val repeated = get(
                server,
                "/v1/world/blocks?dimension=minecraft%3Aoverworld&x=1&x=2&y=64&z=0",
            )
            val unknown = get(
                server,
                "/v1/world/blocks?dimension=minecraft%3Aoverworld&x=1&y=64&z=0&state=true",
            )

            assertEquals(400, missing.statusCode())
            assertContains(missing.body(), "Query parameter 'z' must be an integer")
            assertEquals(400, repeated.statusCode())
            assertContains(repeated.body(), "Query parameter 'x' must not be repeated")
            assertEquals(400, unknown.statusCode())
            assertContains(unknown.body(), "Unknown query parameter 'state'")
        }
    }

    @Test
    fun `fills a block region`() {
        var capturedRequest: FillRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun fill(request: FillRequest): CompletableFuture<FillResult> {
                capturedRequest = request
                return CompletableFuture.completedFuture(
                    FillResult(
                        8,
                        request.dimension,
                        request.x1,
                        request.y1,
                        request.z1,
                        request.x2,
                        request.y2,
                        request.z2,
                        request.block,
                    ),
                )
            }
        }

        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(
                server,
                "/v1/world/blocks/fill",
                """{
                    "dimension":"minecraft:overworld",
                    "x1":1,
                    "y1":64,
                    "z1":-2,
                    "x2":2,
                    "y2":65,
                    "z2":-1,
                    "block":"minecraft:stone"
                }""".trimIndent(),
            )

            assertEquals(200, response.statusCode())
            assertEquals(
                """{"changedBlocks":8,"dimension":"minecraft:overworld","x1":1,"y1":64,"z1":-2,"x2":2,"y2":65,"z2":-1,"block":"minecraft:stone"}""",
                response.body(),
            )
        }

        assertEquals(
            FillRequest(
                "minecraft:overworld",
                1,
                64,
                -2,
                2,
                65,
                -1,
                "minecraft:stone",
            ),
            capturedRequest,
        )
    }

    @Test
    fun `rejects a fill region larger than the operation limit`() {
        withServer(authToken = null) { server, _ ->
            val response = post(
                server,
                "/v1/world/blocks/fill",
                """{
                    "dimension":"minecraft:overworld",
                    "x1":0,
                    "y1":0,
                    "z1":0,
                    "x2":32,
                    "y2":32,
                    "z2":30,
                    "block":"minecraft:stone"
                }""".trimIndent(),
            )

            assertEquals(400, response.statusCode())
            assertContains(response.body(), "Fill region must contain at most 32768 blocks")
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
    fun `lists entity snapshots with nullable health and captures query options`() {
        val captured = mutableListOf<GetEntitiesRequest>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>> {
                captured += request
                return CompletableFuture.completedFuture(listOf(
                    EntitySnapshot(TEST_UUID, "minecraft:item", request.dimension, 1.5, 64.0, -2.25, 90f, -12.5f, "Diamond", null),
                    EntitySnapshot(OTHER_UUID, "minecraft:zombie", request.dimension, 2.0, 64.0, 0.0, 0f, 0f, "Zombie", 20f),
                ))
            }
        }
        withServer(authToken = "test-secret", minecraft = minecraft) { server, _ ->
            val path = "/v1/world/entities?dimension=minecraft%3Athe_nether&entity=minecraft%3Azombie&x=1.5&y=64&z=-2.25&radius=0&sort=nearest&limit=2"
            assertEquals(401, get(server, path).statusCode())
            val response = get(server, path, "test-secret")
            assertEquals(200, response.statusCode())
            assertEquals(
                """{"entities":[{"uuid":"$TEST_UUID","entity":"minecraft:item","dimension":"minecraft:the_nether","x":1.5,"y":64.0,"z":-2.25,"yaw":90.0,"pitch":-12.5,"name":"Diamond","health":null},{"uuid":"$OTHER_UUID","entity":"minecraft:zombie","dimension":"minecraft:the_nether","x":2.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0,"name":"Zombie","health":20.0}]}""",
                response.body(),
            )
        }
        assertEquals(listOf(GetEntitiesRequest(
            "minecraft:the_nether", "minecraft:zombie", EntityQueryCenter(1.5, 64.0, -2.25),
            0.0, EntitySort.NEAREST, 2,
        )), captured)
    }

    @Test
    fun `lists all types by default and supports distance sorting without a radius`() {
        val captured = mutableListOf<GetEntitiesRequest>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>> {
                captured += request
                return CompletableFuture.completedFuture(emptyList())
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            for (query in listOf("", "&x=0&y=64&z=0&sort=furthest&limit=1")) {
                val response = get(server, "/v1/world/entities?dimension=minecraft:overworld$query")
                assertEquals(200, response.statusCode())
                assertEquals("""{"entities":[]}""", response.body())
            }
        }
        assertEquals(listOf(
            GetEntitiesRequest("minecraft:overworld"),
            GetEntitiesRequest("minecraft:overworld", center = EntityQueryCenter(0.0, 64.0, 0.0), sort = EntitySort.FURTHEST, limit = 1),
        ), captured)
    }

    @ParameterizedTest
    @MethodSource("invalidEntityQueries")
    fun `rejects malformed entity queries before calling Minecraft`(query: String) {
        withServer(authToken = null) { server, _ ->
            val response = get(server, "/v1/world/entities?$query")
            assertEquals(400, response.statusCode(), query)
            assertContains(response.body(), "INVALID_REQUEST")
        }
    }

    @Test
    fun `entity listing preserves platform errors and times out pending operations`() {
        for ((status, code) in listOf(400 to "INVALID_ENTITY", 400 to "INVALID_DIMENSION", 404 to "DIMENSION_NOT_FOUND")) {
            val minecraft = object : MinecraftOperations by TestMinecraftOperations {
                override fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>> =
                    CompletableFuture.failedFuture(ApiException(status, code, "Invalid query"))
            }
            withServer(authToken = null, minecraft = minecraft) { server, _ ->
                val response = get(server, "/v1/world/entities?dimension=minecraft:overworld")
                assertEquals(status, response.statusCode())
                assertContains(response.body(), code)
            }
        }
        val pending = CompletableFuture<List<EntitySnapshot>>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun getEntities(request: GetEntitiesRequest) = pending
        }
        withServer(authToken = null, minecraft = minecraft, requestTimeout = Duration.ofMillis(100)) { server, _ ->
            assertEquals(504, get(server, "/v1/world/entities?dimension=minecraft:overworld").statusCode())
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun `kills one entity by uuid and returns its count`() {
        var captured: KillEntityRequest? = null
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun killEntity(request: KillEntityRequest): CompletableFuture<KillResult> {
                captured = request
                return CompletableFuture.completedFuture(KillResult(request.dimension, 1))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val response = post(
                server, "/v1/world/entities/$TEST_UUID/kill",
                """{"dimension":"minecraft:the_nether"}""",
            )
            assertEquals(200, response.statusCode())
            assertEquals("""{"dimension":"minecraft:the_nether","killedEntities":1}""", response.body())
        }
        assertEquals(KillEntityRequest("minecraft:the_nether", UUID.fromString(TEST_UUID)), captured)
    }

    @Test
    fun `kills entities by type with an optional complete range`() {
        val captured = mutableListOf<KillEntitiesRequest>()
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun killEntities(request: KillEntitiesRequest): CompletableFuture<KillResult> {
                captured += request
                return CompletableFuture.completedFuture(KillResult(request.dimension, 3))
            }
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            for (range in listOf("", """, "x":1.5,"y":64,"z":-2.25,"radius":0""")) {
                val response = post(
                    server, "/v1/world/entities/kill",
                    """{"dimension":"minecraft:overworld","entity":"minecraft:zombie"$range}""",
                )
                assertEquals(200, response.statusCode())
                assertEquals("""{"dimension":"minecraft:overworld","killedEntities":3}""", response.body())
            }
        }
        assertEquals(
            listOf(
                KillEntitiesRequest("minecraft:overworld", "minecraft:zombie"),
                KillEntitiesRequest("minecraft:overworld", "minecraft:zombie", EntityKillRange(1.5, 64.0, -2.25, 0.0)),
            ),
            captured,
        )
    }

    @Test
    fun `rejects incomplete and invalid entity kill requests before running them`() {
        withServer(authToken = null) { server, _ ->
            for (uuid in listOf("not-a-uuid", "1-1-1-1-1", "@e")) {
                val response = post(server, "/v1/world/entities/$uuid/kill", """{"dimension":"minecraft:overworld"}""")
                assertEquals(400, response.statusCode(), uuid)
                assertContains(response.body(), "INVALID_REQUEST")
            }
            for (body in listOf("{}", """{"dimension":"minecraft:overworld","entity":"minecraft:zombie"}""")) {
                assertEquals(400, post(server, "/v1/world/entities/$TEST_UUID/kill", body).statusCode())
            }
            val base = """"dimension":"minecraft:overworld","entity":"minecraft:zombie""""
            val invalidBodies = listOf(
                """{"entity":"minecraft:zombie"}""",
                """{"dimension":"minecraft:overworld"}""",
                """{$base,"x":0}""",
                """{$base,"radius":20}""",
                """{$base,"x":0,"y":64,"z":0}""",
                """{$base,"x":0,"y":64,"z":0,"radius":-1}""",
                """{$base,"x":true,"y":64,"z":0,"radius":20}""",
                """{$base,"x":0,"y":64,"z":0,"radius":1e309}""",
                """{$base,"x":0,"y":64,"z":0,"radius":null}""",
                """{$base,"selector":"@e"}""",
            )
            for (body in invalidBodies) {
                val response = post(server, "/v1/world/entities/kill", body)
                assertEquals(400, response.statusCode(), body)
                assertContains(response.body(), "INVALID_REQUEST")
            }
        }
    }

    @Test
    fun `entity kill operations return zero and preserve structured dimension errors`() {
        val minecraft = object : MinecraftOperations by TestMinecraftOperations {
            override fun killEntity(request: KillEntityRequest): CompletableFuture<KillResult> =
                CompletableFuture.completedFuture(KillResult(request.dimension, 0))

            override fun killEntities(request: KillEntitiesRequest): CompletableFuture<KillResult> =
                CompletableFuture.failedFuture(ApiException(404, "DIMENSION_NOT_FOUND", "Dimension is not loaded"))
        }
        withServer(authToken = null, minecraft = minecraft) { server, _ ->
            val missing = post(server, "/v1/world/entities/$TEST_UUID/kill", """{"dimension":"minecraft:overworld"}""")
            assertEquals(200, missing.statusCode())
            assertContains(missing.body(), "\"killedEntities\":0")
            val dimension = post(server, "/v1/world/entities/kill", """{"dimension":"missing:world","entity":"minecraft:zombie"}""")
            assertEquals(404, dimension.statusCode())
            assertContains(dimension.body(), "DIMENSION_NOT_FOUND")
            assertEquals(405, get(server, "/v1/world/entities/kill").statusCode())
            assertEquals(405, get(server, "/v1/world/entities/$TEST_UUID/kill").statusCode())
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
    fun `streams chat messages and player deaths as server sent events`() {
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

                val publishedDeath = events.publishPlayerDeath(
                    "Steve fell from a high place", "Steve", TEST_UUID,
                    "minecraft:the_nether", 1.5, 64.0, -2.25,
                )
                val death = readSseEvent(reader)
                assertEquals("player.death", death["event"])
                assertEquals(publishedDeath.id, death["id"])
                assertEquals(
                    """{"type":"player.death","id":"${publishedDeath.id}","timestamp":"${publishedDeath.timestamp}","message":"Steve fell from a high place","player":{"username":"Steve","uuid":"$TEST_UUID"},"dimension":"minecraft:the_nether","x":1.5,"y":64.0,"z":-2.25}""",
                    death["data"],
                )
            }
        }
    }

    @Test
    fun `streams and replays player actions with item and target snapshots`() {
        val events = BoxloomEventBroker()
        val acknowledged = events.publishChatMessage("before actions", "Steve", TEST_UUID)
        val context = PlayerActionContext(
            "Steve", TEST_UUID, "minecraft:overworld", 1.5, 64.0, -2.25,
            "main_hand", "minecraft:diamond_sword",
        )
        val target = BlockTarget("minecraft:stone", -2, 63, 4, "up")
        val replayed = listOf(
            events.publishPlayerSwing(context),
            events.publishBlockAttack(context, target),
            events.publishBlockUse(context.copy(hand = "off_hand", item = null), target),
            events.publishItemUse(context.copy(hand = "off_hand", item = "minecraft:apple")),
        )
        withServer(authToken = null, events = events) { server, _ ->
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort}/v1/events"))
                    .header("Last-Event-ID", acknowledged.id)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            response.body().use { body ->
                val reader = BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8))
                assertEquals("stream.ready", readSseEvent(reader)["event"])
                // The final action is live, following the four retained events.
                val live = events.publishPlayerSwing(context.copy(item = null))
                for (expected in replayed + live) {
                    val frame = readSseEvent(reader)
                    assertEquals(expected.type, frame["event"])
                    assertEquals(expected.id, frame["id"])
                    val payload = Json.parseToJsonElement(frame.getValue("data")).jsonObject
                    assertEquals(expected.type, payload.getValue("type").jsonPrimitive.content)
                    assertEquals(expected.id, payload.getValue("id").jsonPrimitive.content)
                    assertEquals("Steve", payload.getValue("player").jsonObject.getValue("username").jsonPrimitive.content)
                    assertEquals("1.5", payload.getValue("x").jsonPrimitive.content)
                    assertEquals(expected.context.hand, payload.getValue("hand").jsonPrimitive.content)
                    if (expected.context.item == null) {
                        assertEquals(JsonNull, payload["item"])
                    } else {
                        assertEquals(expected.context.item, payload.getValue("item").jsonPrimitive.content)
                    }
                    if (expected is BlockActionEvent) {
                        assertEquals("minecraft:stone", payload.getValue("block").jsonPrimitive.content)
                        assertEquals("-2", payload.getValue("block_x").jsonPrimitive.content)
                        assertEquals("63", payload.getValue("block_y").jsonPrimitive.content)
                        assertEquals("4", payload.getValue("block_z").jsonPrimitive.content)
                        assertEquals("up", payload.getValue("face").jsonPrimitive.content)
                    }
                    assertTrue("message" !in payload)
                }
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
    fun `replays retained player deaths and chat messages after last event id`() {
        val events = BoxloomEventBroker()
        val acknowledged = events.publishChatMessage("one", "Steve", TEST_UUID)
        val replayedDeath = events.publishPlayerDeath(
            "Steve died", "Steve", TEST_UUID, "minecraft:overworld", 0.0, 64.0, 0.0,
        )
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

                val death = readSseEvent(reader)
                assertEquals("player.death", death["event"])
                assertEquals(replayedDeath.id, death["id"])
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
        authToken: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
            .header("Content-Type", "application/json")
            .apply { authToken?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun get(
        server: BoxloomHttpServer,
        path: String,
        authToken: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:${server.boundPort}$path"))
            .apply { authToken?.let { header("Authorization", "Bearer $it") } }
            .GET()
            .build()

        return HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private object TestMinecraftOperations : MinecraftOperations {
        override fun selectPlayerSlot(username: String, request: SelectSlotRequest): CompletableFuture<SelectSlotResult> =
            throw AssertionError("Unexpected select-slot request")

        override fun givePlayerItem(username: String, request: GiveItemRequest): CompletableFuture<GiveItemResult> =
            throw AssertionError("Unexpected give request")

        override fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>> =
            throw AssertionError("Unexpected get-entities request")

        override fun getTime(request: GetTimeRequest): CompletableFuture<TimeResult> =
            throw AssertionError("Unexpected get-time request")

        override fun setTime(request: SetTimeRequest): CompletableFuture<TimeResult> =
            throw AssertionError("Unexpected set-time request")

        override fun addTime(request: AddTimeRequest): CompletableFuture<TimeResult> =
            throw AssertionError("Unexpected add-time request")

        override fun players(): CompletableFuture<List<Player>> =
            CompletableFuture.completedFuture(emptyList())

        override fun say(request: SayRequest): CompletableFuture<SayResult> =
            throw AssertionError("Unexpected say request")

        override fun targetedBlock(username: String): CompletableFuture<TargetedBlock?> =
            throw AssertionError("Unexpected targeted-block request")

        override fun startMining(username: String): CompletableFuture<PlayerMiningResult> =
            throw AssertionError("Unexpected start-mining request")

        override fun stopMining(username: String): CompletableFuture<PlayerMiningResult> =
            throw AssertionError("Unexpected stop-mining request")

        override fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
            throw AssertionError("Unexpected start-item-use request")

        override fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
            throw AssertionError("Unexpected stop-item-use request")

        override fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult> =
            throw AssertionError("Unexpected use request")

        override fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult> =
            throw AssertionError("Unexpected attack request")

        override fun playerLocation(username: String): CompletableFuture<PlayerLocation> =
            throw AssertionError("Unexpected location request")

        override fun setPlayerGameMode(
            username: String,
            request: SetPlayerGameModeRequest,
        ): CompletableFuture<PlayerGameMode> =
            throw AssertionError("Unexpected game-mode request")

        override fun teleportPlayer(
            username: String,
            request: TeleportPlayerRequest,
        ): CompletableFuture<PlayerLocation> =
            throw AssertionError("Unexpected teleport request")

        override fun getBlock(request: GetBlockRequest): CompletableFuture<GetBlockResult> =
            throw AssertionError("Unexpected get-block request")

        override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> =
            throw AssertionError("Unexpected set-block request")

        override fun fill(request: FillRequest): CompletableFuture<FillResult> =
            throw AssertionError("Unexpected fill request")

        override fun summon(request: SummonRequest): CompletableFuture<SummonResult> =
            throw AssertionError("Unexpected summon request")

        override fun killEntity(request: KillEntityRequest): CompletableFuture<KillResult> =
            throw AssertionError("Unexpected kill-entity request")

        override fun killEntities(request: KillEntitiesRequest): CompletableFuture<KillResult> =
            throw AssertionError("Unexpected kill-entities request")
    }

    private object SayMinecraftOperations : MinecraftOperations {
        override fun selectPlayerSlot(username: String, request: SelectSlotRequest): CompletableFuture<SelectSlotResult> =
            throw AssertionError("Unexpected select-slot request")

        override fun givePlayerItem(username: String, request: GiveItemRequest): CompletableFuture<GiveItemResult> =
            throw AssertionError("Unexpected give request")

        override fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>> =
            throw AssertionError("Unexpected get-entities request")

        override fun getTime(request: GetTimeRequest): CompletableFuture<TimeResult> =
            throw AssertionError("Unexpected get-time request")

        override fun setTime(request: SetTimeRequest): CompletableFuture<TimeResult> =
            throw AssertionError("Unexpected set-time request")

        override fun addTime(request: AddTimeRequest): CompletableFuture<TimeResult> =
            throw AssertionError("Unexpected add-time request")

        override fun say(request: SayRequest): CompletableFuture<SayResult> =
            CompletableFuture.completedFuture(SayResult(request.message, 2))

        override fun players(): CompletableFuture<List<Player>> =
            throw AssertionError("Unexpected players request")

        override fun targetedBlock(username: String): CompletableFuture<TargetedBlock?> =
            throw AssertionError("Unexpected targeted-block request")

        override fun startMining(username: String): CompletableFuture<PlayerMiningResult> =
            throw AssertionError("Unexpected start-mining request")

        override fun stopMining(username: String): CompletableFuture<PlayerMiningResult> =
            throw AssertionError("Unexpected stop-mining request")

        override fun startUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
            throw AssertionError("Unexpected start-item-use request")

        override fun stopUsingPlayerItem(username: String): CompletableFuture<PlayerItemUseResult> =
            throw AssertionError("Unexpected stop-item-use request")

        override fun usePlayerItem(username: String): CompletableFuture<PlayerUseResult> =
            throw AssertionError("Unexpected use request")

        override fun attackPlayer(username: String): CompletableFuture<PlayerAttackResult> =
            throw AssertionError("Unexpected attack request")

        override fun playerLocation(username: String): CompletableFuture<PlayerLocation> =
            throw AssertionError("Unexpected location request")

        override fun setPlayerGameMode(
            username: String,
            request: SetPlayerGameModeRequest,
        ): CompletableFuture<PlayerGameMode> =
            throw AssertionError("Unexpected game-mode request")

        override fun teleportPlayer(
            username: String,
            request: TeleportPlayerRequest,
        ): CompletableFuture<PlayerLocation> =
            throw AssertionError("Unexpected teleport request")

        override fun getBlock(request: GetBlockRequest): CompletableFuture<GetBlockResult> =
            throw AssertionError("Unexpected get-block request")

        override fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult> =
            throw AssertionError("Unexpected set-block request")

        override fun fill(request: FillRequest): CompletableFuture<FillResult> =
            throw AssertionError("Unexpected fill request")

        override fun summon(request: SummonRequest): CompletableFuture<SummonResult> =
            throw AssertionError("Unexpected summon request")

        override fun killEntity(request: KillEntityRequest): CompletableFuture<KillResult> =
            throw AssertionError("Unexpected kill-entity request")

        override fun killEntities(request: KillEntitiesRequest): CompletableFuture<KillResult> =
            throw AssertionError("Unexpected kill-entities request")
    }

    companion object {
        @JvmStatic
        fun invalidSelectSlotRequests(): List<Arguments> {
            val bodies = listOf("{}", "[]", "null", "", """{"slot":0,"item":"stone"}""") +
                listOf("-1", "9", "40", "true", "null", "1.5", "0.0", "\"1\"", "2147483648").map {
                    """{"slot":$it}"""
                }
            return bodies.map { Arguments.of("/v1/players/Steve/select-slot", it) } + listOf(
                Arguments.of("/v1/players/ab/select-slot", """{"slot":0}"""),
                Arguments.of("/v1/players/Steve/select-slot?slot=1", """{"slot":0}"""),
            )
        }

        @JvmStatic
        fun giveRequests(): List<Arguments> = listOf(
            Arguments.of("""{"item":"stone"}""", 1),
            Arguments.of("""{"item":"stone","count":65}""", 65),
        )

        @JvmStatic
        fun invalidGiveRequests(): List<Arguments> {
            val bodies = listOf(
                "{}", "null", "[]", "", """{"item":null}""", """{"item":1}""", """{"item":" "}""",
                """{"item":"stone","slot":0}""",
            ) + listOf("0", "-1", "1.5", "true", "null", "\"2\"", "2147483648").map {
                """{"item":"stone","count":$it}"""
            }
            return bodies.map { Arguments.of("/v1/players/Steve/give", it) } + listOf(
                Arguments.of("/v1/players/ab/give", """{"item":"stone"}"""),
                Arguments.of("/v1/players/Steve/give?slot=0", """{"item":"stone"}"""),
            )
        }

        @JvmStatic
        fun giveErrors(): List<Arguments> = listOf(
            Arguments.of(400, "INVALID_ITEM"),
            Arguments.of(400, "INVALID_COUNT"),
            Arguments.of(404, "PLAYER_NOT_CONNECTED"),
        )

        @JvmStatic
        fun invalidGameModeBodies(): List<String> = listOf(
            "", "{", "[]", "null", "{}", """{"mode":null}""", """{"mode":1}""",
            """{"mode":true}""", """{"mode":[]}""", """{"mode":""}""",
            """{"mode":"CREATIVE"}""", """{"mode":" survival"}""",
            """{"mode":"spectator"}""", """{"mode":"adventure"}""",
            """{"mode":"creative","extra":true}""",
        )

        @JvmStatic
        fun invalidGameModeUsernames(): List<String> =
            listOf("ab", "a".repeat(17), "has-dash", "%40a", "Steve%2Fkill")

        @JvmStatic
        fun gameModeErrors(): List<Arguments> = listOf(
            Arguments.of(404, "PLAYER_NOT_CONNECTED"),
            Arguments.of(409, "GAME_MODE_CHANGE_FAILED"),
            Arguments.of(503, "WORLD_NOT_LOADED"),
        )

        @JvmStatic
        fun clockQueries(): List<Arguments> = listOf(
            Arguments.of("/v1/world/time", "minecraft:overworld"),
            Arguments.of("/v1/world/time?clock=minecraft%3Athe_end", "minecraft:the_end"),
        )

        @JvmStatic
        fun setTimeRequests(): List<Arguments> = listOf(
            Arguments.of("""{"time":"day"}""", SetTimeRequest("minecraft:overworld", TimeValue.Marker("day"))),
            Arguments.of(
                """{"clock":"example:clock","time":"example:dawn"}""",
                SetTimeRequest("example:clock", TimeValue.Marker("example:dawn")),
            ),
            Arguments.of("""{"time":0}""", SetTimeRequest("minecraft:overworld", TimeValue.Ticks(0))),
            Arguments.of(
                """{"time":2147483647}""",
                SetTimeRequest("minecraft:overworld", TimeValue.Ticks(Int.MAX_VALUE)),
            ),
        )

        @JvmStatic
        fun invalidBlockProperties(): List<String> = listOf(
            "null", "[]", "3", "true", "\"age=3\"",
            """{"age":3}""", """{"lit":true}""", """{"age":null}""", """{"age":{}}""",
            """{"age":[]}""", """{"":"3"}""", """{"age":""}""",
        )

        @JvmStatic
        fun invalidEntityQueries(): List<String> = listOf(
            "", "dimension=", "dimension=minecraft:overworld&dimension=minecraft:the_nether",
            "dimension=minecraft:overworld&unknown=1", "dimension=minecraft:overworld&entity=",
        ) + listOf(
            "x=0", "x=0&y=64", "radius=20", "sort=nearest", "sort=furthest", "sort=", "sort=random",
            "x=0&y=64&z=0&radius=-1", "x=NaN&y=64&z=0", "x=Infinity&y=64&z=0",
            "x=0&y=64&z=0&radius=1e309", "x=true&y=64&z=0", "x=&y=64&z=0",
            "limit=0", "limit=-1", "limit=1.5", "limit=true", "limit=2147483648", "limit=",
        ).map { "dimension=minecraft:overworld&$it" }

        private const val TEST_UUID = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        private const val OTHER_UUID = "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7"
    }
}
