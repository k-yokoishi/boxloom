# boxloom Python SDK

The initial SDK provides `say`, `get_players`, `get_player_position`, and `set_block` (`setblock` is also available as an alias) for a running boxloom Fabric server.

```python
from boxloom import get_player_position, get_players, init, say, set_block

init(
    base_url="http://127.0.0.1:28886",
    auth_token="replace-me",
)

say("Hello from Python!")
players = get_players()
position = get_player_position(players[0].username)
x, y, z = position.block_coordinates()
set_block(x + 1, y - 1, z, "minecraft:diamond_block", dimension=position.dimension)
```

Position lookup and block placement are separate requests. The example uses the sampled position even if the player moves before `set_block` reaches the server.

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
