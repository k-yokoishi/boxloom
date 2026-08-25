# boxloom Python SDK

The initial SDK provides `say`, `get_players`, `get_player_position`, `set_block` (`setblock` is also available as an alias), `summon`, and the `watch_chat` player-chat event stream for a running boxloom Fabric server.

```python
from boxloom import get_player_position, get_players, init, say, set_block, summon

init(
    base_url="http://127.0.0.1:28886",
    auth_token="replace-me",
)

say("Hello from Python!")
players = get_players()
position = get_player_position(players[0].username)
x, y, z = position.block_coordinates()
set_block(x + 1, y - 1, z, "minecraft:diamond_block", dimension=position.dimension)
summon(
    "minecraft:arrow",
    x,
    y + 10,
    z,
    nbt={"Motion": [0.0, -1.5, 0.0], "Rotation": [0.0, 90.0]},
    dimension=position.dimension,
)
```

Position lookup and block placement are separate requests. The example uses the sampled position even if the player moves before `set_block` reaches the server.

`summon` accepts an optional plain Python `dict` for NBT. Nested dictionaries, lists, strings, booleans, signed 64-bit integers, and finite floats are supported; `None` has no NBT representation and is rejected. Integers become `IntTag` or `LongTag`, floats become `DoubleTag`, and booleans become `ByteTag`. The entity ID and position arguments take precedence over `id` and `Pos` supplied in the dictionary, matching Minecraft's `summon` command behavior.

Player chat can be consumed as a context-managed iterator:

```python
from boxloom import EventCursorExpiredError, watch_chat

try:
    with watch_chat() as events:
        for event in events:
            print(f"<{event.player.username}> {event.message}")
except EventCursorExpiredError:
    # The server restarted or the bounded replay history no longer has this cursor.
    # Calling watch_chat() again without a cursor starts at the new live position.
    pass
```

`watch_chat()` opens `GET /v1/events` as a Server-Sent Events response and does not poll. It reconnects after transport interruptions by sending the most recently received ID in `Last-Event-ID`; the server then replays retained events after that cursor without duplicating the already received event. Use `watch_chat(last_event_id=...)` to resume from a cursor saved by the application, `stream.last_event_id` to read the latest cursor, or `watch_chat(reconnect=False)` to surface a disconnect immediately.

Event cursors are opaque, are stored only in the mod's bounded in-memory history, and do not survive a Minecraft server session. An unavailable cursor produces `EventCursorExpiredError` instead of silently skipping messages.

Explicit `init()` is optional. Without it, the SDK reads `BOXLOOM_BASE_URL` (default: `http://127.0.0.1:28886`) and the optional `BOXLOOM_AUTH_TOKEN` environment variable. When no token is configured, the SDK omits the Authorization header for a loopback-only boxloom server. The default request timeout is 10 seconds and can be changed with `BOXLOOM_TIMEOUT_SECONDS` or `init(timeout=...)`.

The Fabric mod requires a non-empty authentication token when it binds to a non-loopback address. Python SDK `0.1.0a2` fixes the initialization error in the published `0.1.0a1` package when `BOXLOOM_AUTH_TOKEN` was unset or empty.

The SDK uses only the Python standard library at runtime and supports Python 3.9 or newer.

The project uses uv for its Python interpreter, virtual environment, dependency lock, and package build. From the repository root, install the mise-managed tools and synchronize the SDK environment with:

```bash
mise install
mise run python-sync
```

Run the tests or build the package with:

```bash
mise run python-test
mise run python-build
```

TestPyPI releases are performed manually through GitHub Actions after configuring the `TEST_PYPI_API_TOKEN` repository secret. See the [TestPyPI release guide](../../docs/python-testpypi-release.md) for the one-time setup and release procedure.
