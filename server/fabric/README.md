# boxloom Fabric mod

This directory contains the initial Kotlin-based, server-side Fabric mod PoC. It connects Minecraft and Fabric to the platform-independent [`../core`](../core) module and is designed to work in both a Fabric Dedicated Server and an integrated server.

The PoC implements four Minecraft operations and one event stream:

- Broadcast a system message to connected players
- List connected players
- Read a connected player's position and look direction
- Set one block
- Stream player chat messages as resumable Server-Sent Events

Arbitrary command execution and entity operations are intentionally outside this PoC.

## Fixed compatibility versions

| Component | Version |
| --- | --- |
| Minecraft Java Edition | `26.2` |
| Java | `25` |
| Kotlin | `2.4.10` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.156.0+26.2` |
| Fabric Language Kotlin | `1.13.13+kotlin.2.4.10` |
| Fabric Loom | `1.17.17` |
| Gradle Wrapper | `9.5.1` |
| Docker image | `itzg/minecraft-server:2026.5.4-java25` |

These versions were validated by the original Codorie Learn PoC and are kept fixed during the initial move to boxloom. Upgrade them only as a tested compatibility set.

## Runtime design

The mod uses a common `main` entrypoint and does not reference `net.minecraft.client.*`. `ServerLifecycleEvents.SERVER_STARTED` and `SERVER_STOPPED` track the current server, allowing one JAR to work with dedicated and integrated servers.

`server/core` owns HTTP, optional Bearer authentication, input validation, JSON, errors, and shared operation types. This adapter implements `MinecraftOperations`; all player and world access is handed to `MinecraftServer#execute`, and a `CompletableFuture` returns the result to the core HTTP worker. Operations time out after five seconds by default.

Fabric's `ServerMessageEvents.CHAT_MESSAGE` callback publishes signed player chat content to an in-memory history containing the most recent 1,024 events. `GET /v1/events` holds an HTTP response open and emits SSE frames as messages arrive. Event IDs combine a per-server-session identity and a monotonic sequence. Clients reconnect with `Last-Event-ID`; retained messages after that cursor are replayed, while a cursor from another session or evicted history returns `410 EVENT_CURSOR_EXPIRED`. Cursors are provided by boxloom, not persisted by Fabric or Minecraft.

The distributable Fabric JAR embeds the core JAR, so installation still requires only one file.

The HTTP API is described independently in [`../../protocol/openapi.yaml`](../../protocol/openapi.yaml).

## Configuration

The first launch creates `config/boxloom.json` inside the Minecraft instance:

```json
{
  "bindHost": "127.0.0.1",
  "port": 28886,
  "authToken": "",
  "requestTimeoutMs": 5000
}
```

Environment variables remain available and take precedence over the matching file settings.

| JSON setting | Environment variable | Default | Purpose |
| --- | --- | --- | --- |
| `authToken` | `BOXLOOM_AUTH_TOKEN` | Empty | Optional Bearer token accepted by the internal API |
| `bindHost` | `BOXLOOM_BIND_HOST` | `127.0.0.1` | Address on which the internal API listens |
| `port` | `BOXLOOM_PORT` | `28886` | Internal API port |
| `requestTimeoutMs` | `BOXLOOM_REQUEST_TIMEOUT_MS` | `5000` | Minecraft server-thread timeout, from `100` to `60000` ms |

An empty token enables unauthenticated local access and logs a warning. This mode is restricted to a loopback bind address. A non-loopback bind address requires a non-empty token.

## Build

JDK 25 is required.

```bash
cd server
./gradlew :fabric:clean :fabric:build
```

The mod JAR is written to `fabric/build/libs/boxloom-0.1.0-alpha.1.jar`.

## Docker PoC

Build the mod before starting the server:

```bash
cd server
./gradlew :fabric:build
cd fabric
docker compose up -d
docker compose logs -f minecraft
```

The included Compose configuration exposes the game at `localhost:25566` and the API at `127.0.0.1:28886`. It uses `boxloom-local-poc-token` only for local testing. The API port remains bound to host loopback even though the process listens on the container network.

Stop the server while preserving its world volume with:

```bash
docker compose down
```

## Try the API

Broadcast a message:

```bash
curl --fail-with-body \
  -X POST \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  -H 'Content-Type: application/json' \
  --data '{"message":"Hello from boxloom!"}' \
  http://127.0.0.1:28886/v1/chat/messages
```

After joining the server with Minecraft Java Edition 26.2, get a player position:

```bash
curl --fail-with-body \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  http://127.0.0.1:28886/v1/players/example/position
```

List connected players:

```bash
curl --fail-with-body \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  http://127.0.0.1:28886/v1/players
```

Stream new player chat messages without polling:

```bash
curl --no-buffer --fail-with-body \
  -H 'Accept: text/event-stream' \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  http://127.0.0.1:28886/v1/events
```

To resume a disconnected stream, send the last completely processed SSE `id` value as the `Last-Event-ID` header. The response uses SSE application framing; HTTP/1.1 may carry it with chunked transfer encoding, but clients should parse SSE fields rather than relying on transport chunk boundaries.

Set a block:

```bash
curl --fail-with-body \
  -X POST \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  -H 'Content-Type: application/json' \
  --data '{
    "dimension": "minecraft:overworld",
    "x": 0,
    "y": 100,
    "z": 0,
    "block": "minecraft:diamond_block"
  }' \
  http://127.0.0.1:28886/v1/world/blocks
```

## PoC security constraints

- Bearer authentication is optional for loopback-only use and required for non-loopback listeners.
- The default listener is loopback only.
- The server accepts no arbitrary Minecraft commands.
- Request bodies are limited to 16 KiB.
- The server validates JSON shape, usernames, dimensions, block IDs, and integer coordinates.
- Production deployments must configure an authentication token and add operation, range, quantity, and rate limits.
