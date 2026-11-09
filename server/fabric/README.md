# boxloom Fabric mod

This directory contains the initial Kotlin-based, server-side Fabric mod PoC. It connects Minecraft and Fabric to the platform-independent [`../core`](../core) module and is designed to work in both a Fabric Dedicated Server and an integrated server.

The mod exposes Minecraft operations and a live player event stream:

- Broadcast a system message to connected players
- List connected players
- Read a connected player's position and look direction
- Read the block under a player's server-side crosshair, within interaction reach
- Perform one native block or entity attack or miss, including a swing sent to the player and observers
- Use the main hand on the aimed block or entity, or in the air (including bone meal)
- Start and release continuous main-hand item use for food, drink, bows and shields
- Give registered items or block items using vanilla `/give` stacking and overflow rules
- Select a hotbar slot and synchronize the selection to the player's client
- Start and stop continuous held mining with native speed, drops and durability
- Teleport a connected player to absolute coordinates
- Set a connected player's game mode to Creative or Survival
- Get one block and its complete state properties from a loaded dimension
- Set one block with optional string state properties
- Fill an inclusive block region
- Summon one entity with optional NBT
- List loaded entities with type and sphere filters, distance sorting, and a result limit
- Kill entities by UUID or by type and optional sphere
- Read, set, and add ticks to a world clock, including `/time set day` markers
- Stream player chat, death, attack swing, block attack, block use, and item use events as resumable Server-Sent Events

Arbitrary command execution is intentionally outside this PoC.

`POST /v1/players/{username}/give` accepts `{ "item": "minecraft:stone", "count": 64 }`.
`count` defaults to 1 and is limited to 100 stacks of the item. The response
contains `username`, `uuid`, canonical `item`, and the requested `count`, plus
`slots` entries with each destination's inventory index and added count. Indices
are 0–8 for the hotbar, 9–35 for the main inventory, and 40 for the offhand.
`slot` is the lowest hotbar index that received items, or null if none did.
Dropped or discarded overflow has no slot. Registered
mod items and blocks with an item form are supported; air, unknown IDs, and
world-only blocks are rejected with `400 INVALID_ITEM`. Excessive counts return
`400 INVALID_COUNT`. Matching stacks and free inventory slots are used automatically,
without replacing other items or selecting a hotbar slot. Survival overflow drops
at the player; creative overflow follows vanilla and may be discarded. The response
count does not promise that all items remain in inventory. Components and NBT are
not accepted. No player OP permission is required.

`POST /v1/players/{username}/select-slot` accepts `{ "slot": 2 }` and returns
`username`, `uuid`, and the selected `slot`. Only integer indices 0–8 are accepted;
other values return `400 INVALID_REQUEST`. Empty hotbar slots may be selected.
The server sends `ClientboundSetHeldSlotPacket` to synchronize the client.
Changing slots cancels API mining and main-hand item use without release effects;
selecting the current slot preserves ongoing actions. It does not move items
from the main inventory or offhand. No player OP permission is required.

`GET /v1/players/{username}/targeted-block` returns `{ "target": null }` or a
snapshot with dimension, block coordinates, block ID, face and eye-to-surface
distance. Entities occlude blocks and fluids are ignored. Targeting uses the
server's latest received view, so it can lag behind the client's crosshair.

`POST /v1/players/{username}/attack` accepts an empty JSON body, `{}`, and returns
`swung`, `target`, and `broken`, plus `entity` after an entity attack attempt. It
recalculates the target, cancels API mining, and invokes native block or entity
attack processing. Native entity reach, damage cooldowns, PvP and Fabric callbacks
apply. Entity snapshots do not guarantee damage. Creative and instant-mine blocks
can break; other survival blocks need sustained mining. Piercing weapons return
`409 UNSUPPORTED_ATTACK_TARGET`; dead, spectator or busy players return
`409 PLAYER_CANNOT_ATTACK`.

`POST /v1/players/{username}/use` accepts `{}` and performs one main-hand use
on the aimed block/entity, or in the air. Returns `used`, `swung`, `target` and
`entity` (both targets are nullable). Bone meal growth and consumption, creative
rules, cooldowns, reach, spawn protection and Fabric cancellation use native
processing. `used` means the action was accepted, not guaranteed growth or
consumption. No automatic offhand fallback. Dead, spectator or already-using
players return `409 PLAYER_CANNOT_USE`. API mining is cancelled. Long-duration
items start native use but a vanilla client can interrupt them; use the item-use
endpoints for controlled continuous use. Block/item
attempts emit existing use events; use swings do not emit attack-only `player.swing`.

`POST /v1/players/{username}/item-use/start` and `/item-use/stop` accept `{}` and
return `active`, nullable `item`, and nonnegative `remaining_ticks`. Start keeps
the main-hand item in native use; food/drink completes one item, bows/shields
continue until release. Repeating start preserves progress. Native hunger,
consumption, cooldowns, creative behavior and Fabric UseItemCallback apply. Empty
or instant-use items return `ITEM_NOT_CONTINUOUSLY_USABLE`; native refusals return
inactive. Stop releases only valid API-owned use (interrupt food or fire a charged
bow), otherwise it is an idempotent no-op and leaves native client use alone.
The packet listener suppresses client release only while the API owns that use,
after vanilla's server-thread dispatch. No client mod is required; a vanilla
client may stop its local first-person animation while the server continues use.
Slot/stack, world/mode changes, death, disconnect, shutdown and competing API
attack/use/mining cancel without release effects. Item-use start cancels mining.

`POST /v1/players/{username}/mining/start` and `/mining/stop` accept `{}` and return
`active`, `target`, and `broken`. Start returns immediately and the server advances
native mining like holding the mining button. It waits without a target and
continues to the next block after a five-tick break delay. Repeated start preserves
progress. Target, block-state, tool or slot changes reset progress; item use pauses
mining. Protected or unbreakable targets wait for the aim, block or tool to change.
The mode ends on stop, attack, use, item-use start, death, disconnect, world or game-mode changes, or
a competing native mining action. Stop is idempotent and cancels pending native
delayed breaks. `active` remains true while waiting and after an instant break;
`target` can be null. This mode does not attack entities. The controlled player
receives cracks and swings as well as observers. Native protection events, tool
durability and drops apply.

Block reads and targeting snapshots include `properties`, a map of canonical
state names to string values. `POST /v1/world/blocks` accepts optional properties,
such as `{"age":"3"}` for wheat, applied to the block default state. Unspecified
properties reset to defaults. Unknown names or invalid values return
`400 INVALID_BLOCK_PROPERTY` before changing the world. Read and set responses
include every current property, or `{}` for blocks without state properties.
Set responses report the actual block and state after the attempt; property-only
changes count as changed, while an identical state is a no-op.

## Fixed compatibility versions

| Component | Version |
| --- | --- |
| Minecraft Java Edition | `26.3` |
| Java | `25` |
| Kotlin | `2.4.20` |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.161.0+26.3` |
| Fabric Language Kotlin | `1.14.1+kotlin.2.4.20` |
| Fabric Loom | `1.17.17` |
| Gradle Wrapper | `9.5.1` |
| Docker image | `itzg/minecraft-server:2026.5.4-java25` |

These versions were validated by the original Codorie Learn PoC and are kept fixed during the initial move to boxloom. Upgrade them only as a tested compatibility set.

## Runtime design

The mod uses a common `main` entrypoint and does not reference `net.minecraft.client.*`. `ServerLifecycleEvents.SERVER_STARTED` and `SERVER_STOPPED` track the current server, allowing one JAR to work with dedicated and integrated servers.

When a dedicated server stops, boxloom closes its HTTP server so the JVM can exit
after saving the world. Integrated servers keep the HTTP server available between
world sessions and reset event cursors when a world closes. A JVM shutdown hook
also closes the HTTP server when the application exits.

`server/core` owns the JDK `HttpServer`, optional Bearer authentication, input validation, JSON serialization, errors, and shared operation types. This adapter implements `MinecraftOperations`; all player and world access is handed to `MinecraftServer#execute`, and a `CompletableFuture` returns the result to a virtual HTTP worker thread. Operations time out after five seconds by default, and timed-out futures prevent queued work from running if it has not reached the server thread yet.

Fabric's `ServerMessageEvents.CHAT_MESSAGE` callback publishes signed player chat
content, and `ServerLivingEntityEvents.AFTER_DEATH` publishes confirmed player
deaths with a death message and location. `AttackBlockCallback`, `UseBlockCallback`,
and `UseItemCallback` publish action attempts without cancelling vanilla behavior.
A common server mixin observes `ServerGamePacketListenerImpl.handlePunch` after
server-thread dispatch to include attack swings that miss. All action snapshots
are captured on the server thread and exclude spectators. No client mod is needed.
See [`../../protocol/events.md`](../../protocol/events.md) for fields and semantics.
All six application events share an in-memory history of the
most recent 1,024 events. `GET /v1/events` holds an HTTP response open and emits
SSE frames as events arrive. Event IDs combine a per-server-session identity and
a monotonic sequence. Clients reconnect with `Last-Event-ID`; retained events
after that cursor are replayed, while a cursor from another session or evicted
history returns `410 EVENT_CURSOR_EXPIRED`. Cursors are provided by boxloom, not
persisted by Fabric or Minecraft.

The distributable Fabric JAR embeds the core JAR, so installation still requires only one top-level file. The HTTP transport uses only JDK classes and therefore adds no HTTP-framework runtime JARs. Kotlin and kotlinx serialization remain supplied by the required Fabric Language Kotlin dependency.

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

The mod JAR is written to `fabric/build/libs/boxloom-0.1.0-alpha.9.jar`.

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

The server uses Creative mode and Normal difficulty, allowing zombies and other
hostile mobs to be summoned. Set `MINECRAFT_OPS=YourJavaName` in `server/fabric/.env`
before starting it to grant your Minecraft Java profile OP level 4 and access to all
commands. Multiple names or UUIDs can be separated by commas. Run
`docker compose up -d` again after changing the setting.

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

After joining the server with Minecraft Java Edition 26.3, get a player position:

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

Teleport a connected player while preserving their current dimension and look direction:

```bash
curl --fail-with-body \
  -X POST \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  -H 'Content-Type: application/json' \
  --data '{"x":100,"y":64,"z":-20}' \
  http://127.0.0.1:28886/v1/players/example/teleport
```

The optional `dimension`, `yaw`, and `pitch` fields replace the corresponding current player values. Coordinates are always absolute.

Set a connected player's game mode:

```bash
curl --fail-with-body \
  -X POST \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  -H 'Content-Type: application/json' \
  --data '{"mode":"survival"}' \
  http://127.0.0.1:28886/v1/players/example/gamemode
```

`mode` must be exactly `creative` or `survival`. The response contains `username`,
`uuid`, and the resulting `mode`. Repeating the current mode is a successful
no-op. The change runs on the server thread and does not require player OP
permissions or a server restart. It uses the existing API authentication and
does not change the world's default game mode. A disconnected player returns
`404 PLAYER_NOT_CONNECTED`.

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

Get a block:

```bash
curl --get --fail-with-body \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  --data-urlencode 'dimension=minecraft:overworld' \
  --data-urlencode 'x=0' \
  --data-urlencode 'y=100' \
  --data-urlencode 'z=0' \
  http://127.0.0.1:28886/v1/world/blocks
```

Fill a block region:

```bash
curl --fail-with-body \
  -X POST \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  -H 'Content-Type: application/json' \
  --data '{
    "dimension": "minecraft:overworld",
    "x1": 0,
    "y1": 100,
    "z1": 0,
    "x2": 4,
    "y2": 100,
    "z2": 4,
    "block": "minecraft:gold_block"
  }' \
  http://127.0.0.1:28886/v1/world/blocks/fill
```

Both corners are included and may be supplied in either order. A single request can change a region containing at most 32,768 blocks.

Summon an entity with optional NBT:

```bash
curl --fail-with-body \
  -X POST \
  -H 'Authorization: Bearer boxloom-local-poc-token' \
  -H 'Content-Type: application/json' \
  --data '{
    "dimension": "minecraft:overworld",
    "entity": "minecraft:arrow",
    "x": 0.0,
    "y": 100.0,
    "z": 0.0,
    "nbt": {
      "Motion": [0.0, -1.5, 0.0],
      "Rotation": [0.0, 90.0]
    }
  }' \
  http://127.0.0.1:28886/v1/world/entities
```

## PoC security constraints

- Bearer authentication is optional for loopback-only use and required for non-loopback listeners.
- The default listener is loopback only.
- The server accepts no arbitrary Minecraft commands.
- Request bodies are limited to 16 KiB.
- The server validates JSON shape, usernames, dimensions, block and entity IDs, coordinates, and NBT-compatible values.
- Production deployments must configure an authentication token and add operation, range, quantity, and rate limits.
