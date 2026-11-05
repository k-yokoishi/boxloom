# Vanilla Minecraft registry snapshot

[`registries.json`](registries.json) contains all vanilla block and entity type IDs
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
   `BuiltInRegistries.BLOCK` and `BuiltInRegistries.ENTITY_TYPE`. The loaded Minecraft
   version must match the configured version. Only `minecraft:` IDs are included.
2. `cd sdks/python && uv run python scripts/generate_registry_constants.py` converts
   the snapshot to `src/boxloom/blocks.py` and `src/boxloom/entities.py`. Names are
   uppercase Python identifiers and values are namespaced strings annotated with
   `Final[str]`. Invalid IDs, duplicate names, and version mismatches fail generation.

The snapshot and generated modules use stable ordering and contain no timestamps.
Commit all three files after updating Minecraft. The exporter lives in a separate
source set and is excluded from the published mod JAR. Python distributions bundle
the generated modules and require no Java or registry extraction at installation
or import time.

Entity constants include every registered vanilla type. Some, such as `player`,
cannot be summoned. `summon` retains the server's existing summonability checks.
Mod IDs can still be supplied as strings; this snapshot covers vanilla Minecraft.

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
