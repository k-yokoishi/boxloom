# boxloom Python SDK

The boxloom Python SDK connects Python programs to a Minecraft server running the boxloom Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API and supported versions may change.

## Installation

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a8
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
    print(target.block, target.x, target.y, target.z, target.face, target.distance)
result = player.attack()
print(result.swung, result.broken, result.entity)
```

`get_player(username)` returns `Optional[Player]`: a player bound to its client
when connected, or `None` when that player is not connected. The same applies to
`BoxloomClient.get_player(username)`. Invalid names raise `ValueError`; connection,
authentication and other API errors raise their usual exceptions.

Players from `get_players()` also expose these methods and retain their original
client connection. Targeting uses the server's latest view and block interaction
reach, ignores fluids, and stops at intervening entities. `None` means no block
is targeted. `attack()` recalculates the target and performs one block or entity
attack or a miss, with the arm swing sent to the player and observers. Entity
attacks return an `entity` snapshot after the attempt and respect native attack
reach, damage cooldowns, PvP and Fabric cancellation. Walls occlude enemies.
Native protections and tools apply; survival blocks requiring sustained mining
do not break from one click. Piercing weapons are unsupported. An existing
swing can prevent a new swing. Event player snapshots and manually constructed
`Player` values are not bound; resolve them with the appropriate client's
`get_player()` before calling methods.

Give blocks or items to a player, as with `/give`:

```python
from boxloom import blocks, give_item

player.give_item(blocks.STONE, count=64)
player.give_item("minecraft:diamond_sword")  # count defaults to 1
result = give_item("Steve", "minecraft:bone_meal", count=16)
print(result.item, result.count)
```

Also available as `BoxloomClient.give_item(username, item, count=1)`. All forms
return `GiveItemResult(username, uuid, item, count)` with a canonical namespaced
item ID. Blocks with an item form use item IDs too; `blocks.STONE` works, while
world-only blocks such as water cannot be given. The `minecraft:` prefix may be
omitted. Registered mod item IDs are accepted. Item components and NBT are not
accepted by this interface.

The server merges matching stacks and fills empty slots without replacing other
items or changing the selected hotbar slot. Each call accepts at most 100 stacks
of the item (6,400 stone, 1,600 snowballs, or 100 swords). Survival overflow drops
at the player with immediate pickup reserved for them. Creative overflow follows
vanilla `/give` and can be discarded. `count` reports the amount given, not the
amount retained in inventory. Invalid IDs raise `INVALID_ITEM`; an item-specific
limit violation raises `INVALID_COUNT`. Requests are not retried automatically.

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
`attack()`, `use()`, `start_using_item()`, death, disconnect, world or game-mode changes, or a competing native
mining action. Repeating start preserves progress. `active` means the hold is
enabled, including while `target` is `None`. `broken` is true only for an immediate
creative or instant break; `active` remains true. Read blocks to observe later
breaks. Native drops, tool durability and protection callbacks apply, and the
player receives crack progress as well as observers. Client methods are
`start_player_mining(username)` and `stop_player_mining(username)`;
`PlayerMiningResult` is returned by both.

`get_block()` returns an immutable `Block` snapshot with `dimension`, `x`, `y`,
`z`, and the namespaced ID in `block`. `TargetedBlock` inherits from `Block` and
adds `face` and `distance`, so code can accept `Block` from either API.
`GetBlockResult` remains an alias for `Block` for compatibility; existing
constructor arguments and response formats are preserved.

Place a block:

```python
from boxloom import blocks, set_block

set_block(0, 100, 0, blocks.DIAMOND_BLOCK)
```

Read and write block-state properties using string keys and values:

```python
from boxloom import blocks, get_block, set_block

set_block(0, 99, 0, blocks.FARMLAND, properties={"moisture": "7"})
result = set_block(0, 100, 0, blocks.WHEAT, properties={"age": "3"})
print(result.changed, result.properties)
print(get_block(0, 100, 0).properties["age"])  # "3"
```

Properties start from the block defaults; omitted properties reset to defaults.
Reads and set results contain the complete state in a read-only mapping. Unknown
properties or invalid values return `400 INVALID_BLOCK_PROPERTY` without changing
the world. An identical state returns `changed=False`; changing a property alone
returns `changed=True`.


Fill an inclusive block region (up to 32,768 blocks per call):

```python
from boxloom import blocks, fill

result = fill(0, 100, 0, 4, 100, 4, blocks.GOLD_BLOCK)
print(result.changed_blocks)
```

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
Filtering happens in the SDK; all types share the `GET /v1/events` stream and
its combined history of 1,024 events. Unknown event names are skipped. Existing
`watch_chat()` callers continue to receive only chat messages.

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

All four classes have `id`, `timestamp`, `player`, `dimension`, `x`, `y`, `z`,
`hand` (`"main_hand"` or `"off_hand"`), and `item` (namespaced ID, or `None`
for an empty hand). `x`, `y`, and `z` are the player's position. Block events
also have `block`, integer `block_x`, `block_y`, `block_z`, and `face` (`"down"`,
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
