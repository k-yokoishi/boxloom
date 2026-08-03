# Repository layout

## Decision

boxloom is a small polyglot monorepo. Server code, language SDKs, the wire contract, examples, and cross-component tests are separate top-level concerns:

```text
boxloom/
|-- server/
|   |-- core/              # platform-independent HTTP, auth, DTOs, and operation interface
|   |-- fabric/            # Fabric adapter and distributable mod
|   `-- <platform>/        # future Paper, NeoForge, or other server adapters
|-- sdks/
|   |-- python/            # initial Python SDK
|   `-- <language>/        # future independently published SDKs
|-- protocol/
|   `-- openapi.yaml       # language-neutral SDK-to-server contract
|-- examples/
|   `-- <language>/        # runnable public-API examples, added with each SDK
|-- tests/
|   `-- integration/       # end-to-end tests added when an SDK exists
|-- docs/                  # architecture, compatibility, security, and ADRs
`-- README.md
```

Only directories with current content are committed. The tree shows planned extension points; placeholder build projects are not created before their implementation starts.

## Why this shape

`server/` is one Gradle multi-project build. `server/core/` owns HTTP routing, authentication, configuration, JSON validation, structured errors, shared DTOs, and the asynchronous `MinecraftOperations` interface. It has no Fabric or Minecraft dependency.

`server/fabric/` implements that interface using Fabric and Minecraft APIs, including handoff to the Minecraft server thread. The Fabric build embeds the core JAR, so deployment remains a single mod JAR. A future `server/paper/` or `server/neoforge/` can implement the same interface without copying the protocol-facing code.

`sdks/<language>/` gives every SDK its own package metadata, tests, dependencies, supported runtime versions, and release workflow. Python is not treated as the repository root or as the owner of shared protocol types, so adding another language will not require moving it later.

`protocol/` owns wire compatibility. The Fabric implementation and every SDK depend on this contract conceptually; SDKs do not depend on each other. This is the key boundary for independent SDK and mod versioning.

`examples/<language>/` belongs outside published SDK packages so teaching and integration examples do not become runtime dependencies. Cross-component tests likewise belong under `tests/integration/`, while unit tests stay with the component they test.

## Dependency direction

```text
examples/<language> -> sdks/<language> -> protocol <- server/core <- server/fabric
tests/integration  -> sdks/<language> + running server adapter
```

The OpenAPI file begins as a reviewed contract rather than a code-generation requirement. Once the Python SDK exists, CI should check both implementations against the contract and can add generated models or clients only if they improve maintainability without weakening the public SDK design.

## Naming and releases

- Fabric mod ID and display name: `boxloom`
- Fabric JAR base name: `boxloom`
- Python distribution and import package: `boxloom`
- API version: path-prefixed (`/v1`)
- Component versions: independent, with a published compatibility matrix

The repository may use one coordinated release initially, but its layout must not require SDK and server versions to remain identical.
