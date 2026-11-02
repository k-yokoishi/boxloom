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
mise run python-build
```

Detailed API documentation will be maintained separately from this README.
