# boxloom server

The server is a Gradle multi-project build split into a platform-independent core and Minecraft server-platform adapters.

```text
server/
|-- core/       # HTTP, authentication, configuration, JSON, errors, DTOs, operation interface
|-- fabric/     # Fabric lifecycle and Minecraft world-operation implementation
|-- build.gradle
|-- settings.gradle
`-- gradlew
```

`core` has no Fabric or Minecraft dependency. It uses the JDK `HttpServer` for HTTP transport and owns routing, JSON validation, SSE, authentication, and structured error handling. Server adapters implement `MinecraftOperations`, including any required handoff to their platform's main server thread. This keeps SDK-visible behavior consistent while allowing adapters such as `paper` or `neoforge` to be added later without bringing an HTTP framework into each mod-loader distribution.

Build every current server module with JDK 25:

```bash
cd server
./gradlew clean build
```

The Fabric build embeds the core JAR, so users install one `boxloom-<version>.jar` file. The HTTP server does not add framework runtime JARs to the distribution.
