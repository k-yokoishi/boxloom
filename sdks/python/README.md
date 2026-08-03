# boxloom Python SDK

The initial SDK provides `say` and `set_block` (`setblock` is also available as an alias) for a running boxloom Fabric server.

```python
from boxloom import init, say, set_block

init(
    base_url="http://127.0.0.1:28886",
    auth_token="replace-me",
)

say("Hello from Python!")
set_block(0, 100, 0, "minecraft:diamond_block")
```

Explicit `init()` is optional. Without it, the SDK reads `BOXLOOM_BASE_URL` (default: `http://127.0.0.1:28886`) and the required `BOXLOOM_AUTH_TOKEN` environment variable. The default request timeout is 10 seconds and can be changed with `BOXLOOM_TIMEOUT_SECONDS` or `init(timeout=...)`.

The SDK uses only the Python standard library at runtime and supports Python 3.9 or newer.

Run its unit tests from this directory with:

```bash
python3 -m unittest discover -s tests -v
```
