# boxloom protocol

This directory is the language-neutral contract between the boxloom server and every SDK.

- [`openapi.yaml`](openapi.yaml) describes the current PoC HTTP API.
- Breaking protocol changes require a new API version rather than silently changing `/v1` behavior.
- SDK-specific conveniences belong under `sdks/<language>/`; they must not redefine wire behavior.
- Protocol-facing server behavior belongs under `server/core/`; Fabric and other Minecraft platform details belong in their `server/<platform>/` adapters.

The OpenAPI document is descriptive for the initial PoC. Code generation and automated protocol conformance checks can be added as the contract grows.
