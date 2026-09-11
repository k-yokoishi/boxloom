# boxloom Python SDK

The boxloom Python SDK connects Python programs to a Minecraft server running the boxloom Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API and supported versions may change.

## Installation

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a2
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

Listen for player chat messages:

```python
from boxloom import watch_chat

with watch_chat() as events:
    for event in events:
        print(f"<{event.player.username}> {event.message}")
```

More runnable examples are available in [`../../examples/python`](../../examples/python).

## Development

From the repository root:

```bash
mise install
mise run python-sync
mise run python-test
mise run python-build
```

Detailed API documentation will be maintained separately from this README.
