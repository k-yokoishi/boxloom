<p align="center">
  <img src="assets/brand/boxloom-logo-concept-v1.png" alt="boxloom" width="720">
</p>

# boxloom

boxloom connects Python programs to Minecraft Java Edition through a server-side Fabric mod.

> [!IMPORTANT]
> boxloom is an early-stage project. Its API, supported versions, and release process may change.

## Quick start

The quickest way to try boxloom is the included Docker Compose environment. It starts a Minecraft server with the boxloom mod and a browser-based editor with the local Python SDK already configured.

You need Docker and Minecraft Java Edition 26.2.

```bash
docker compose up --build
```

When the Minecraft log reports `Done`:

1. Connect Minecraft Java Edition to `localhost:25566`.
2. Open `http://localhost:8080`.
3. Run `python sample.py` in the browser-based editor's terminal.

The first startup can take a few minutes while Docker downloads and builds the required components. Stop the environment without deleting the Minecraft world with:

```bash
docker compose down
```

Ports, memory, and the local API token can be changed by copying [`.env.example`](.env.example) to `.env` and editing it.

## Install the Python SDK

The current alpha is published on TestPyPI:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0a2
```

Point the SDK at a running boxloom Fabric server:

```bash
export BOXLOOM_BASE_URL=http://127.0.0.1:28886
export BOXLOOM_AUTH_TOKEN=replace-me
```

The Docker Compose environment sets these values automatically inside its browser-based editor.

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

Runnable examples are available in [`examples/python`](examples/python).

## Development

The repository uses [mise](https://mise.jdx.dev/) to install Java 25 and uv and to run the common development tasks.

```bash
mise install
mise run python-sync
mise run python-test
mise run python-build
mise run fabric-build
mise run docs-install
mise run docs-build
```

The Fabric mod JAR is written to `server/fabric/build/libs/`.

## Documentation

Detailed user and API documentation is maintained in [`docs-site`](docs-site). The current language-neutral wire contract is in [`protocol`](protocol), and repository structure is described in [Repository layout](docs/repository-layout.md).

## License

boxloom is available under the [MIT License](LICENSE).
