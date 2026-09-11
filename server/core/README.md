# boxloom server core

`core` contains the platform-independent implementation shared by Minecraft server adapters:

- HTTP routing and `/v1` JSON responses
- Optional Bearer authentication and request-size limits
- JSON-file and environment-based listener, authentication, and timeout configuration
- Request validation and structured API errors
- Operation request and response types
- The asynchronous `MinecraftOperations` interface

It deliberately has no Fabric, NeoForge, Paper, or Minecraft dependency. Platform adapters own game lifecycle integration, registry lookups, world access, and scheduling onto the correct server thread.

## HTTP structure

- `BoxloomHttpServer` owns only the JDK HTTP server and executor lifecycle.
- `http/BoxloomHttpApplication` applies authentication and error handling, then dispatches requests through `http/Router`.
- `api/chat`, `api/players`, `api/world`, and `api/events` contain feature-scoped route registration and handlers.
- Common request/response, Minecraft-operation timeout, and SSE framing behavior lives under `http`.

Add new endpoints to the matching feature routes. Add another feature package and register it in `BoxloomHttpApplication` when no existing feature owns the API.

The core targets Java 21 bytecode so future adapters are not forced to adopt Fabric's current Java 25 target. The current Fabric adapter runs it on Java 25 and embeds the core JAR into the distributable mod.
