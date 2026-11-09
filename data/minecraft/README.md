# Vanilla Minecraft registry snapshot

[`registries.json`](registries.json) contains all vanilla block, entity type, and item IDs
from the Minecraft version pinned in `server/gradle.properties`. It is the shared
input for SDK constants; it does not change the HTTP protocol.

## Regenerate

From the repository root, with the mise tools installed:

```bash
mise run python-generate-constants
```

This runs two steps:

1. `cd server && ./gradlew :fabric:exportRegistries` runs the development-only
   `RegistryExporter` against Loom's configured Minecraft dependency. It initializes
   vanilla registries without launching Fabric, a client, or a world, then exports
   `BuiltInRegistries.BLOCK`, `BuiltInRegistries.ENTITY_TYPE`, and
   `BuiltInRegistries.ITEM`. The loaded Minecraft
   version must match the configured version. Only `minecraft:` IDs are included.
2. `cd sdks/python && uv run python scripts/generate_registry_constants.py` converts
   the snapshot to `src/boxloom/blocks.py`, `src/boxloom/entities.py`, and
   `src/boxloom/items.py`. Names are
   uppercase Python identifiers and values are namespaced strings annotated with
   `Final[str]`. Invalid IDs, duplicate names, and version mismatches fail generation.

The snapshot and generated modules use stable ordering and contain no timestamps.
Commit the snapshot and all three generated modules after updating Minecraft. The exporter lives in a separate
source set and is excluded from the published mod JAR. Python distributions bundle
the generated modules and require no Java or registry extraction at installation
or import time.

Entity constants include every registered vanilla type. Some, such as `player`,
cannot be summoned. `summon` retains the server's existing summonability checks.
Mod IDs can still be supplied as strings; this snapshot covers vanilla Minecraft.

Item constants cover the item registry, including block items such as `items.STONE`
and ordinary items such as `items.BONE_MEAL` and `items.DIAMOND_SWORD`. World-only
blocks such as water are absent from the item registry. `items.AIR` is registered
but cannot be given; `give_item` retains the server's availability checks.

## Verify

```bash
mise run python-check-constants
cd server
./gradlew :fabric:verifyRegistries
```

The Python check compares generated modules with the committed snapshot and verifies
its version against `server/gradle.properties`. It does not require Java. The Fabric
check compares the snapshot with the actual Minecraft registries and is also part
of `./gradlew build`. CI runs both checks, and the Python release workflow verifies
generated modules before building distributions.
