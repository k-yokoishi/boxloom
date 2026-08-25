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

`core` has no Fabric or Minecraft dependency. It uses Ktor/CIO for routing, JSON serialization, SSE, authentication, and structured error handling. Server adapters implement the suspending `MinecraftOperations` interface, including any required handoff to their platform's main server thread. This keeps SDK-visible behavior consistent while allowing adapters such as `paper` or `neoforge` to be added later.

Build every current server module with JDK 25:

```bash
cd server
./gradlew clean build
```

The Fabric build embeds the core JAR and the required Ktor runtime JARs, so users still install one top-level `boxloom-<version>.jar` file and do not install Ktor separately. Run `./gradlew :fabric:verifyDistributionJar` to audit the nested JAR list, transitive runtime coverage, platform-library exclusions, and duplicate classes in the final distribution artifact.
