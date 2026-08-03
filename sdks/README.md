# boxloom SDKs

Each supported language has an independently buildable and publishable package under `sdks/<language>/`.

The first SDK is `sdks/python/`, published as the Python package `boxloom`. Future SDKs should be siblings such as `sdks/typescript/` or `sdks/java/`, rather than being nested inside the Python project.

All SDKs share the contract in [`../protocol/openapi.yaml`](../protocol/openapi.yaml). An SDK may provide idiomatic language features, but authentication, errors, field meanings, and `/v1` wire behavior must remain compatible with that contract.
