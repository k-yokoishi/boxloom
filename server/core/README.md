# boxloom server core

`core` contains the platform-independent implementation shared by Minecraft server adapters:

- HTTP routing and `/v1` JSON responses
- Optional Bearer authentication and request-size limits
- JSON-file and environment-based listener, authentication, and timeout configuration
- Request validation and structured API errors
- Operation request and response types
- The asynchronous `MinecraftOperations` interface

It deliberately has no Fabric, NeoForge, Paper, or Minecraft dependency. Platform adapters own game lifecycle integration, registry lookups, world access, and scheduling onto the correct server thread.

The core targets Java 21 bytecode so future adapters are not forced to adopt Fabric's current Java 25 target. The current Fabric adapter runs it on Java 25 and embeds the core JAR into the distributable mod.
