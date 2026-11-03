# boxloom Python SDK

The boxloom Python SDK connects Python programs to a Minecraft server running the boxloom Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API and supported versions may change.

## Installation

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a5
```

Configure the connection to the Fabric server with environment variables:

```bash
export BOXLOOM_BASE_URL=http://127.0.0.1:28886
export BOXLOOM_AUTH_TOKEN=replace-me
```

## Samples

Place a block:

```python
from boxloom import blocks, set_block

set_block(0, 100, 0, blocks.DIAMOND_BLOCK)
```

Fill an inclusive block region (up to 32,768 blocks per call):

```python
from boxloom import blocks, fill

result = fill(0, 100, 0, 4, 100, 4, blocks.GOLD_BLOCK)
print(result.changed_blocks)
```

### Block and entity constants

Import `blocks` and `entities`, then type `blocks.` or `entities.` to browse IDs
with editor completion. They contain vanilla IDs for Minecraft 26.3, exposed as
ordinary strings with `Final[str]` annotations. For example,
`blocks.DIAMOND_BLOCK == "minecraft:diamond_block"` and
`entities.ZOMBIE == "minecraft:zombie"`.

Use block constants with `set_block` and `fill`, and entity constants with `summon`
and `kill_entities`, including their `BoxloomClient` methods. Namespaced strings
are still accepted, including IDs from installed Mods. The server checks whether
an ID exists and whether an entity type can be summoned; registry constants such
as `entities.PLAYER` do not imply that the type is summonable.

`blocks.MINECRAFT_VERSION` and `entities.MINECRAFT_VERSION` identify the source
version. The constants are bundled with the SDK, so editor completion does not
require a running Minecraft server.

### More samples

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

List entities, or find the nearest zombie within 20 blocks:

```python
from boxloom import get_entities

for entity in get_entities():
    print(entity.uuid, entity.name, entity.entity, entity.health)

nearest = get_entities(
    "minecraft:zombie", x=0, y=64, z=0,
    radius=20, sort="nearest", limit=1,
)
```

`get_entities` also exists on `BoxloomClient` and returns frozen `Entity`
snapshots with UUID, type, dimension, position, rotation, name, and health.
It includes loaded, alive entities, including players and dropped items;
health is `None` for entities without health. No chunks are loaded.
The default dimension is `minecraft:overworld`. Omit `entity` for all types.
Supply `x`, `y`, and `z` together for an inclusive non-negative `radius` or
`sort="nearest"` / `"furthest"`. Sorting works without a radius.
Filtering precedes sorting, then `limit` (a positive 32-bit integer) is applied.
Distance ties use UUID order; the default `sort="arbitrary"` order is unspecified.
An empty selection returns `[]`.

Kill entities by UUID or by type and optional range:

```python
from boxloom import entities, kill_entities, kill_entity, summon

zombie = summon(entities.ZOMBIE, 0, 64, 0)
print(kill_entity(zombie.uuid).killed_entities)

result = kill_entities(entities.ZOMBIE, x=0, y=64, z=0, radius=20)
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
mise run python-check-constants
mise run python-build
```

When updating Minecraft, run `mise run python-generate-constants` from the
repository root. This exports the version pinned in `server/gradle.properties`
and regenerates both modules. Commit the snapshot and generated files together.
See [`../../data/minecraft/README.md`](../../data/minecraft/README.md).

Detailed API documentation will be maintained separately from this README.
