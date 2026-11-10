# boxloom Python SDK

The boxloom Python SDK connects Python programs to a Minecraft server running the boxloom Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API and supported versions may change.

## Installation

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a10
```

Configure the connection to the Fabric server with environment variables:

```bash
export BOXLOOM_BASE_URL=http://127.0.0.1:28886
export BOXLOOM_AUTH_TOKEN=replace-me
```

## Samples

Read the block a player is aiming at and attack it once:

```python
from boxloom import get_player

player = get_player("Steve")
if player is None:
    raise RuntimeError("Steve is not connected")
target = player.get_targeted_block()
if target is not None:
    print(target.block, target.position.x, target.position.y, target.position.z, target.face, target.distance)
result = player.attack()
print(result.swung, result.broken, result.entity)
```

`get_player(username)` returns `Optional[Player]`: a player bound to its client
when connected, or `None` when that player is not connected. The same applies to
`BoxloomClient.get_player(username)`. Invalid names raise `ValueError`; connection,
authentication and other API errors raise their usual exceptions.

Read the player's latest position and look direction:

```python
state = player.get_position()
print(state.position.x, state.position.y, state.position.z)
print(state.dimension, state.yaw, state.pitch)
```

Each call reads a fresh `PlayerPosition` from the server through the player's
original client. `get_player_position(player.username)` remains available and
returns the same type.

Players from `get_players()` also expose these methods and retain their original
client connection. Targeting uses the server's latest view and block interaction
reach, ignores fluids, and stops at intervening entities. `None` means no block
is targeted. `attack()` recalculates the target and performs one block or entity
attack or a miss, with the arm swing sent to the player and observers. Entity
attacks return an `entity` snapshot after the attempt and respect native attack
reach, damage cooldowns, PvP and Fabric cancellation. Walls occlude enemies.
Native protections and tools apply; survival blocks requiring sustained mining
do not break from one click. Piercing weapons are unsupported. An existing
swing can prevent a new swing. Players received through `watch_event()`,
`watch_chat()`, and `subscribe_event()` also retain the receiving client, so
`event.player.get_position()` and other player methods work directly. Manually
constructed `Player` values are not bound; resolve them with the appropriate
client's `get_player()` before calling methods.

Give blocks or items to a player, as with `/give`:

```python
from boxloom import give_item, items

player.give_item(items.STONE, count=64)
player.give_item(items.DIAMOND_SWORD)  # count defaults to 1
result = give_item("Steve", items.BONE_MEAL, count=16)
print(result.item, result.count)
```

Also available as `BoxloomClient.give_item(username, item, count=1)`. All forms
return `GiveItemResult(username, uuid, item, count, slot, slots)` with a canonical namespaced
item ID. Blocks with an item form use item IDs too; both `items.STONE` and
`blocks.STONE` work, while world-only blocks such as water cannot be given. The `minecraft:` prefix may be
omitted. Registered mod item IDs are accepted. Item components and NBT are not
accepted by this interface.

The server merges matching stacks and fills empty slots without replacing other
items or changing the selected hotbar slot. Each call accepts at most 100 stacks
of the item (6,400 stone, 1,600 snowballs, or 100 swords). Survival overflow drops
at the player with immediate pickup reserved for them. Creative overflow follows
vanilla `/give` and can be discarded. `count` reports the amount given, not the
amount retained in inventory. Invalid IDs raise `INVALID_ITEM`; an item-specific
limit violation raises `INVALID_COUNT`. Requests are not retried automatically.

`slots` is a tuple of `GivenItemSlot(slot, count)` entries, ordered by inventory
index. Each count is how many items this request added to that slot, not its
resulting stack size. Indices are `0–8` for the hotbar, `9–35` for the main
inventory, and `40` for the offhand. Dropped or discarded items have no slot.
`slot` is the lowest hotbar index that received items, or `None` if none did.
These are response-time snapshots; later player actions can move the items.

Give, select, then use bone meal (aim at a growing plant in Minecraft first):

```python
result = player.give_item(items.BONE_MEAL, 16)
if result.slot is not None:
    player.select_slot(result.slot)
    player.use()
```

`player.select_slot(slot)` selects one hotbar slot, numbered `0–8` from left to
right (Minecraft keyboard keys `1–9`). Also available as
`select_slot(username, slot)` and `BoxloomClient.select_slot(username, slot)`.
It returns `SelectSlotResult(username, uuid, slot)` and synchronizes the selection
to the player's client. Empty slots are allowed. Invalid indices, booleans and
floats raise `ValueError` before requesting. Changing slots stops API mining and
main-hand item use without firing a bow; selecting the current slot preserves
ongoing actions. Main-inventory and offhand items must be moved into the hotbar
in Minecraft before they can be selected. If no hotbar slot received items,
`result.slot` is `None` even if `result.slots` contains other destinations.

Use the main-hand item once on the aimed block or entity, or in the air:

```python
# Hold bone meal and aim at a growing plant in Minecraft first.
result = player.use()
print(result.used, result.target)
```

`Player.use()` (or `BoxloomClient.use_player_item(username)`) uses native effects,
consumption, creative rules, cooldowns, reach, spawn protection and mod cancellation.
It can also place blocks, operate a lever with an empty hand, feed animals or throw
an item. There is no automatic offhand fallback. `used` reports accepted native use,
not guaranteed growth or consumption; `target` is the block snapshot before use
and `entity` is an entity snapshot after the attempt. Dead, spectator or already-using
players raise `PLAYER_CANNOT_USE`. Calling `use()` cancels API mining and continuous item use without release effects.
Long-duration items can be interrupted by a vanilla client; use
`start_using_item()` / `stop_using_item()` for controlled continuous use. Requests
are not retried automatically.

Eat one main-hand food item while hungry, or start charging a bow:

```python
result = player.start_using_item()  # Returns immediately; food finishes once.
print(result.active, result.item, result.remaining_ticks)
# player.stop_using_item()  # Interrupt food, or release a bow to fire it.
```

Native hunger, consumption, creative rules, cooldowns and Fabric cancellation
apply. Only API-owned use ignores the client's automatic use-release packets.
Food/drink completes once; a bow/shield continues until release. Repeating start
while active preserves progress; after completion it starts a new use. Stop is
idempotent and leaves client-owned use alone. Slot/stack, world/mode changes,
death, disconnect, shutdown, API attack, single use or mining start cancel without
firing a bow. No client mod is required; the local first-person animation may
stop while the server continues use. Client equivalents are
`start_using_player_item(username)` and `stop_using_player_item(username)`.
Empty or instant-use items return `ITEM_NOT_CONTINUOUSLY_USABLE`. Native refusal
(full hunger, cooldown or mod cancellation) returns inactive. Other busy/invalid
player states return `PLAYER_CANNOT_USE`. Mutations are not retried automatically.

To hold mining, use `start_mining()` and release it with `stop_mining()`:

```python
import time

player.start_mining()  # Returns immediately, even with no targeted block.
try:
    time.sleep(10)  # Aim at blocks to mine them continuously for ten seconds.
finally:
    player.stop_mining()
```

Mining waits when no block is targeted and continues after each break, with a
five-tick delay. Looking away resets block progress; changing the target, block
state, tool or slot starts fresh progress. Item use pauses mining. Protected or
unbreakable blocks stay intact until the aim, block or tool changes. The mode
only mines blocks and does not attack entities. It ends on `stop_mining()`,
`attack()`, `use()`, `start_using_item()`, a different slot selected with `select_slot()`,
death, disconnect, world or game-mode changes, or a competing native
mining action. Repeating start preserves progress. `active` means the hold is
enabled, including while `target` is `None`. `broken` is true only for an immediate
creative or instant break; `active` remains true. Read blocks to observe later
breaks. Native drops, tool durability and protection callbacks apply, and the
player receives crack progress as well as observers. Client methods are
`start_player_mining(username)` and `stop_player_mining(username)`;
`PlayerMiningResult` is returned by both.

`get_block()` returns an immutable `Block` snapshot with `dimension`,
`position` (`BlockPos`), and the namespaced ID in `block`. `TargetedBlock` inherits from `Block` and
adds `face` and `distance`, so code can accept `Block` from either API.

### Coordinates

Use `Vec3(x, y, z)` for player/entity positions, directions, and offsets, and
`BlockPos(x, y, z)` for block positions. Both are immutable values with named
components, equality, hashing, addition, subtraction, negation, and scalar
multiplication (`offset * distance` or `distance * offset`). `Vec3` stores finite
floats; `BlockPos` requires signed 32-bit integers and only integer multipliers.
Booleans are rejected. Arithmetic that produces invalid components raises
`ValueError`.

```python
from boxloom import BlockPos, Vec3, blocks, get_player_position, set_block, teleport_player

state = get_player_position("Steve")
teleport_player("Steve", state.position + Vec3(0, 10, 0))

origin = state.position.to_block_pos()
set_block(origin + BlockPos(1, -1, 0), blocks.DIAMOND_BLOCK, dimension=state.dimension)
```

`Vec3.to_block_pos()` floors each component: `Vec3(-0.2, 64.8, 3.9)` becomes
`BlockPos(-1, 64, 3)`. `BlockPos.to_vec3()` returns the block's minimum corner;
add `Vec3(0.5, 0.5, 0.5)` for its center. Conversions are explicit: block APIs
reject `Vec3`, and teleport/summon/query APIs reject `BlockPos`. Convert before
mixing the two types in arithmetic. Neither type carries a dimension.

The same signatures apply to `BoxloomClient`. The REST API still uses its existing
flat coordinate fields; the SDK performs serialization and decoding.

Place a block:

```python
from boxloom import BlockPos, blocks, set_block

set_block(BlockPos(0, 100, 0), blocks.DIAMOND_BLOCK)
```

Read and write block-state properties using string keys and values:

```python
from boxloom import BlockPos, blocks, get_block, set_block

set_block(BlockPos(0, 99, 0), blocks.FARMLAND, properties={"moisture": "7"})
result = set_block(BlockPos(0, 100, 0), blocks.WHEAT, properties={"age": "3"})
print(result.changed, result.properties)
print(get_block(BlockPos(0, 100, 0)).properties["age"])  # "3"
```

Properties start from the block defaults; omitted properties reset to defaults.
Reads and set results contain the complete state in a read-only mapping. Unknown
properties or invalid values return `400 INVALID_BLOCK_PROPERTY` without changing
the world. An identical state returns `changed=False`; changing a property alone
returns `changed=True`.


Fill an inclusive block region (up to 32,768 blocks per call):

```python
from boxloom import BlockPos, blocks, fill

result = fill(BlockPos(0, 100, 0), BlockPos(4, 100, 4), blocks.GOLD_BLOCK)
print(result.changed_blocks)
```

Choose a placement mode or restrict which existing blocks are affected:

```python
start, end = BlockPos(0, 100, 0), BlockPos(4, 104, 4)
fill(start, end, blocks.AIR, mode="destroy")  # Break blocks and produce native drops.
fill(start, end, blocks.STONE, replace=blocks.DIRT)  # Replace only dirt.
fill(start, end, blocks.AIR, replace=blocks.STONE, mode="destroy")
fill(start, end, blocks.GLASS, mode="keep")  # Place only in air.
fill(start, end, blocks.GLASS, mode="outline")  # Boundary only; preserve the interior.
fill(start, end, blocks.GLASS, mode="hollow")  # Boundary, with an air interior.
fill(start, end, blocks.OAK_LOG, properties={"axis": "x"},
     replace=blocks.OAK_LOG, replace_properties={"axis": "y"})
```

`mode` is a `FillMode` literal: `"replace"` (default), `"destroy"`, `"keep"`,
`"outline"`, `"hollow"`, or `"strict"`. Strict mode skips placement side effects,
shape updates, and neighbor notifications. All modes except keep can use
`replace`; keep with a filter raises `ValueError`. `replace_properties` requires
`replace` and matches only the specified string properties, while destination
`properties` start from block defaults. IDs and state values are checked before
any world change. Block tags and command strings are not accepted as filters.

Destroy uses Minecraft's native block loot and block-drop game rule, without a
player's held tool or enchantments. `changed_blocks` counts positions placed or
destroyed once each, including a destroyed block replaced with air or the same
block type. A no-op returns zero. The 32,768-block limit applies to the entire
cuboid before filtering. Existing calls and `FillResult` are unchanged.

Switch a connected player between Creative and Survival:

```python
from boxloom import set_player_gamemode

set_player_gamemode("Steve", "creative")
result = set_player_gamemode("Steve", "survival")
print(result.username, result.mode)
```

`set_player_gamemode(username, mode)` also exists on `BoxloomClient`. `mode` is
exactly `"creative"` or `"survival"` (the exported `GameMode` literal type), and the
result is a frozen `PlayerGameMode` with `username`, `uuid`, and `mode`. Repeating
the current mode succeeds. The player must be connected but does not need OP;
the operation uses the same API authentication as other calls. Invalid inputs
raise `ValueError` before making a request. A disconnected player raises
`ApiError` with status `404` and code `PLAYER_NOT_CONNECTED`.

### Block, entity, and item constants

Import `blocks`, `entities`, and `items`, then type `blocks.`, `entities.`, or `items.` to browse IDs
with editor completion. They contain vanilla IDs for Minecraft 26.3, exposed as
ordinary strings with `Final[str]` annotations. For example,
`blocks.DIAMOND_BLOCK == "minecraft:diamond_block"`,
`items.BONE_MEAL == "minecraft:bone_meal"`, and
`entities.ZOMBIE == "minecraft:zombie"`.

Use block constants with `set_block` and `fill`, entity constants with `summon`
and `kill_entities`, and item constants with `give_item` and `Player.give_item`,
including their `BoxloomClient` methods. Item constants include block items such
as `items.STONE` and ordinary items such as `items.BONE_MEAL` and
`items.DIAMOND_SWORD`. Namespaced strings
are still accepted, including IDs from installed Mods. The server checks whether
an ID exists and whether an entity type can be summoned; registry constants such
as `entities.PLAYER` do not imply that the type is summonable. Likewise,
`items.AIR` is registered but cannot be given.

`blocks.MINECRAFT_VERSION`, `entities.MINECRAFT_VERSION`, and
`items.MINECRAFT_VERSION` identify the source
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
            print(event.dimension, event.position.x, event.position.y, event.position.z)
```

`watch_event("player.death")` receives only player deaths;
`watch_event("chat.message")` receives only chat. `watch_event` also exists on
`BoxloomClient` and accepts `last_event_id` and `reconnect`, like `watch_chat`.
Filtering happens in the SDK; all types share the `GET /v1/events` stream and
its combined history of 1,024 events. Unknown event names are skipped. Existing
`watch_chat()` callers continue to receive only chat messages.

`event.player.get_position()` reads the player's latest `PlayerPosition` through
the stream's original client, even after `boxloom.init()` changes the default
client. Use its `.position` (`Vec3`) for coordinates and `.dimension` for world
operations. Death and action events' own `position` and `dimension` fields remain
snapshots from when the event occurred.

`EventType` includes `"chat.message"`, `"player.death"`, `"player.swing"`,
`"block.attack"`, `"block.use"`, and `"item.use"`. Type checkers reject
unknown event type strings. `watch_event()` yields `Event`, a union of the six
event classes, and checking `event.type` narrows it to the matching class.
Overloads make `watch_event("player.death")` yield `PlayerDeathEvent` directly,
including inside a `with` block. The distribution includes `py.typed` so editors
and type checkers can use these annotations after installation.

Watch attack swings, including misses, without a client-side mod:

```python
from boxloom import watch_event

with watch_event("player.swing") as swings:
    for event in swings:  # PlayerSwingEvent
        if event.item == "minecraft:diamond_sword":
            print(event.player.username, "swung a diamond sword")
```

| Filter | Yielded class | Meaning |
| --- | --- | --- |
| `"player.swing"` | `PlayerSwingEvent` | An attack arm swing, including misses. |
| `"block.attack"` | `BlockAttackEvent` | A request to start attacking a block. |
| `"block.use"` | `BlockUseEvent` | A request to use a block. |
| `"item.use"` | `ItemUseEvent` | A request to use a non-empty held item. |

All four classes have `id`, `timestamp`, `player`, `dimension`, `position`,
`hand` (`"main_hand"` or `"off_hand"`), and `item` (namespaced ID, or `None`
for an empty hand). `position` is the player's `Vec3` position. Block events
also have `block`, `block_position` (`BlockPos`), and `face` (`"down"`,
`"up"`, `"north"`, `"south"`, `"west"`, or `"east"`). Item and target values
are server-thread snapshots taken before consumption or other changes.

These events describe attempts, not successful results. `player.swing` also
fires for attacks that hit and for block attacks; it does not tell you whether
an attack missed. A single operation can emit both swing and block events.
Holding a button can repeat actions. In Minecraft 26.3, attack swings use the
main hand. Item use on a block, including placement, is observed as `block.use`;
`item.use` observes the separate item-use path. Spectator actions are excluded.

The same filters work with `subscribe_event`, with callbacks typed to the
corresponding event class. See [`watch_player_actions.py`](../../examples/python/watch_player_actions.py)
and [`subscribe_player_swing.py`](../../examples/python/subscribe_player_swing.py).

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

List entities, or find the nearest zombie within 20 blocks:

```python
from boxloom import Vec3, get_entities

for entity in get_entities():
    print(entity.uuid, entity.name, entity.entity, entity.health)

nearest = get_entities(
    "minecraft:zombie", center=Vec3(0, 64, 0),
    radius=20, sort="nearest", limit=1,
)
```

`get_entities` also exists on `BoxloomClient` and returns frozen `Entity`
snapshots with UUID, type, dimension, position, rotation, name, and health.
It includes loaded, alive entities, including players and dropped items;
health is `None` for entities without health. No chunks are loaded.
The default dimension is `minecraft:overworld`. Omit `entity` for all types.
Supply `center=Vec3(...)` for an inclusive non-negative `radius` or
`sort="nearest"` / `"furthest"`. Sorting works without a radius.
Filtering precedes sorting, then `limit` (a positive 32-bit integer) is applied.
Distance ties use UUID order; the default `sort="arbitrary"` order is unspecified.
An empty selection returns `[]`.

Kill entities by UUID or by type and optional range:

```python
from boxloom import Vec3, entities, kill_entities, kill_entity, summon

zombie = summon(entities.ZOMBIE, Vec3(0, 64, 0))
print(kill_entity(zombie.uuid).killed_entities)

result = kill_entities(entities.ZOMBIE, center=Vec3(0, 64, 0), radius=20)
print(result.killed_entities)
```

Both functions also exist on `BoxloomClient` and default to
`dimension="minecraft:overworld"`. A missing or already dead entity reports zero.
`kill_entities` matches an exact registered type ID. Supply `center=Vec3(...)`
and a finite non-negative `radius` together for an inclusive sphere; omit both to
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

Tests use pytest. From `sdks/python`, run `uv run pytest -v` for the full suite,
`uv run pytest -k subscription` to select tests, or `uv run pytest --lf` to rerun
the previous failures. Tests start local HTTP servers; Minecraft is not required.

`tests/smoke_test.py` is run separately against each built distribution so it
imports the installed package rather than the source checkout:

```bash
uv build --clear
uv run --isolated --no-project --with dist/*.whl --with pytest pytest -v tests/smoke_test.py
uv run --isolated --no-project --with dist/*.tar.gz --with pytest pytest -v tests/smoke_test.py
```

CI runs these checks on Python 3.13. Pytest is a development dependency;
installing the SDK does not install pytest.

When updating Minecraft, run `mise run python-generate-constants` from the
repository root. This exports the version pinned in `server/gradle.properties`
and regenerates both modules. Commit the snapshot and generated files together.
See [`../../data/minecraft/README.md`](../../data/minecraft/README.md).

Detailed API documentation will be maintained separately from this README.
