# boxloom Python SDK

The boxloom Python SDK connects Python programs to a Minecraft server running the boxloom Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API and supported versions may change.

## Installation

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a3
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

## Development

From the repository root:

```bash
mise install
mise run python-sync
mise run python-test
mise run python-build
```

Detailed API documentation will be maintained separately from this README.
