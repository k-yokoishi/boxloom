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

`core` has no Fabric or Minecraft dependency. Server adapters implement `MinecraftOperations`, including any required handoff to their platform's main server thread. This keeps SDK-visible behavior consistent while allowing adapters such as `paper` or `neoforge` to be added later.

Build every current server module with JDK 25:

```bash
cd server
./gradlew clean build
```

The Fabric build embeds the core JAR, so users still install one `boxloom-<version>.jar` file.
