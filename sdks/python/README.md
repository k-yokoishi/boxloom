# boxloom Python SDK

The boxloom Python SDK connects Python programs to a Minecraft server running the boxloom Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API and supported versions may change.

## Installation

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a4
```

Configure the connection to the Fabric server with environment variables:

```bash
export BOXLOOM_BASE_URL=http://127.0.0.1:28886
export BOXLOOM_AUTH_TOKEN=replace-me
```

## Samples

Place a block:

```python
from boxloom import set_block

set_block(0, 100, 0, "minecraft:diamond_block")
```

Fill an inclusive block region (up to 32,768 blocks per call):

```python
from boxloom import fill

result = fill(0, 100, 0, 4, 100, 4, "minecraft:gold_block")
print(result.changed_blocks)
```

Listen for player chat messages:

```python
from boxloom import watch_chat

with watch_chat() as events:
    for event in events:
        print(f"<{event.player.username}> {event.message}")
```

More runnable examples are available in [`../../examples/python`](../../examples/python).

Handle chat and player deaths through one event stream:

```python
from boxloom import watch_event

with watch_event() as events:
    for event in events:
        if event.type == "chat.message":
            print(f"<{event.player.username}> {event.message}")
        elif event.type == "player.death":
            print(f"{event.player.username} died: {event.message}")
            print(event.dimension, event.x, event.y, event.z)
```

`watch_event("player.death")` receives only player deaths;
`watch_event("chat.message")` receives only chat. `watch_event` also exists on
`BoxloomClient` and accepts `last_event_id` and `reconnect`, like `watch_chat`.
Filtering happens in the SDK; both types share the `GET /v1/events` stream and
its combined history of 1,024 events. Unknown event names are skipped. Existing
`watch_chat()` callers continue to receive only chat messages.

`EventType` is `Literal["chat.message", "player.death"]`. Type checkers reject
unknown event type strings. `watch_event()` yields `Event`, a union of `ChatEvent`
and `PlayerDeathEvent`, and checking `event.type` narrows it to the matching class.
Overloads make `watch_event("player.death")` yield `PlayerDeathEvent` directly,
including inside a `with` block. The distribution includes `py.typed` so editors
and type checkers can use these annotations after installation.

Receive callbacks while game logic continues on the main thread:

```python
from threading import Event as Signal
from boxloom import PlayerDeathEvent, subscribe_event

game_over = Signal()

def on_death(event: PlayerDeathEvent) -> None:
    if event.player.username == "Steve":
        game_over.set()

def on_error(error: Exception) -> None:
    print(f"Event monitoring failed: {error}")
    game_over.set()

with subscribe_event(
    "player.death", callback=on_death, on_error=on_error, reconnect=False,
) as subscription:
    while not game_over.wait(1.0):
        subscription.raise_if_failed()
        # Advance the game, e.g. summon TNT above the participant.
    subscription.raise_if_failed()
```

`subscribe_event` is also a `BoxloomClient` method. It confirms `stream.ready`
before returning; initial failures are raised synchronously. Annotated callbacks
are checked against the selected event type. Initialize shared state before
subscribing, since callbacks may start before registration returns.

Each subscription owns an SSE connection and one daemon thread. Callbacks run
serially on that thread and can use the SDK; long callbacks delay later events.
Use synchronous `def` callbacks and thread-safe signals for shared game state.
Receive or callback failures stop the subscription, notify `on_error` once, and
remain available through `raise_if_failed()` and normal context exit. Without an
error handler, background failures are logged. An error handler's own failure is
logged without replacing the original error. A context's existing exception is
preserved during cleanup.

`stop()` requests cooperative shutdown and is safe inside a callback. `join()`
waits for termination; `join(timeout=...)` raises `TimeoutError` if it is still
running. Calling `join()` on the callback's own thread raises `RuntimeError`.
An idle network read may take up to the client timeout to finish; active callbacks
and previously sent Minecraft commands are not forcibly interrupted. Always use a
`with` block or pair `stop()` with `join()` before releasing game state.

`last_event_id` advances after successful callbacks, or after skipping filtered
events. Callback failures leave the failed event unacknowledged. Reconnection
uses the shared cursor history; expired cursors stop the subscription. Make
side effects idempotent when persisting cursors across process restarts.
For games that should stop on a dropped connection, use `reconnect=False` and
signal the game loop from `on_error`, as above.

Control the world clock:

```python
from boxloom import add_time, get_time, set_time

set_time("day")
set_time("night")
set_time(6000)
add_time(1000)
print(get_time().ticks)
```

These functions also exist on `BoxloomClient`. They default to the
`minecraft:overworld` clock; pass `clock="minecraft:the_end"` or a custom clock ID
to select another clock. A marker advances to its next occurrence, while an integer
sets total elapsed ticks. `add_time` accepts signed ticks and clamps at zero.

Kill entities by UUID or by type and optional range:

```python
from boxloom import kill_entities, kill_entity, summon

zombie = summon("minecraft:zombie", 0, 64, 0)
print(kill_entity(zombie.uuid).killed_entities)

result = kill_entities("minecraft:zombie", x=0, y=64, z=0, radius=20)
print(result.killed_entities)
```

Both functions also exist on `BoxloomClient` and default to
`dimension="minecraft:overworld"`. A missing or already dead entity reports zero.
`kill_entities` matches an exact registered type ID. Supply `x`, `y`, `z`, and a
finite non-negative `radius` together for an inclusive sphere; omit all four to
select every loaded entity of that type in the dimension. No chunks are loaded.
Summoning zombies requires a non-peaceful difficulty.

## Development

From the repository root:

```bash
mise install
mise run python-sync
mise run python-test
mise run python-typecheck
mise run python-build
```

Detailed API documentation will be maintained separately from this README.
