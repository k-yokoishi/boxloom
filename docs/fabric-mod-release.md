# Publishing the boxloom Fabric mod

This checklist covers the initial `0.1.0-alpha.1` release to GitHub Releases and Modrinth.

## Build and verify

From the repository root:

```bash
mise run fabric-build
mise run python-test
```

Upload `server/fabric/build/libs/boxloom-0.1.0-alpha.1.jar` as the primary mod file. The `-sources.jar` file is optional supplementary source code, not the installable mod.

Before publishing, repeat the clean-profile smoke test with the new JAR and no `BOXLOOM_AUTH_TOKEN`. Confirm that Minecraft starts, `config/boxloom.json` is created, the unauthenticated warning appears, and the HTTP server listens on `127.0.0.1:28886`.

## Modrinth project

Create the project as a draft with this metadata:

| Field | Value |
| --- | --- |
| Project type | Mod |
| Name | boxloom |
| Summary | Control Minecraft from Python through a local HTTP API provided by a server-side Fabric mod. |
| License | MIT |
| Category | Utility |
| Source | `https://github.com/k-yokoishi/boxloom` |
| Issues | `https://github.com/k-yokoishi/boxloom/issues` |

Use the following English project description:

```markdown
# boxloom

boxloom is a server-side Fabric mod that lets Python programs interact with Minecraft through a local HTTP API.

The initial alpha supports:

- Broadcasting a system message to connected players
- Listing connected players
- Reading a connected player's position and look direction
- Setting one block in a loaded dimension

It works with both Fabric dedicated servers and integrated singleplayer servers. The distributable mod JAR includes the boxloom server core; Fabric API and Fabric Language Kotlin are installed separately.

## Requirements

- Minecraft Java Edition 26.2
- Java 25 or newer
- Fabric Loader 0.19.3 or newer
- Fabric API 0.156.0+26.2 or newer
- Fabric Language Kotlin 1.13.13+kotlin.2.4.10 or newer

## Status

This is an alpha release. Its API, configuration format, and compatibility may change in future versions.
```

## Modrinth version

Upload the primary JAR and set:

| Field | Value |
| --- | --- |
| Version number | `0.1.0-alpha.1` |
| Version type | Alpha |
| Loader | Fabric |
| Game version | 26.2 |
| Client environment | Optional |
| Server environment | Required |
| Required dependency | Fabric API |
| Required dependency | Fabric Language Kotlin |

Paste the matching section from `server/fabric/CHANGELOG.md` into the version changelog. Add `boxloom-0.1.0-alpha.1-sources.jar` only as a supplementary Sources JAR if desired.

## GitHub Release

GitHub Actions creates the prerelease when a `fabric-v*` tag is pushed. Before
tagging, confirm that `server_version` in `server/gradle.properties` and the
matching heading in `server/fabric/CHANGELOG.md` use the same version.

Create and push an annotated tag from the release commit:

```bash
git tag -a fabric-v0.1.0-alpha.1 -m "boxloom Fabric Mod 0.1.0-alpha.1"
git push origin fabric-v0.1.0-alpha.1
```

The `.github/workflows/fabric-release.yml` workflow then:

- checks that the tag and configured version match
- builds and tests the Fabric server modules with Java 25
- copies the matching section from `server/fabric/CHANGELOG.md`
- publishes a GitHub prerelease with `boxloom-0.1.0-alpha.1.jar` attached

No additional GitHub secret is required; the workflow uses the repository's
short-lived `GITHUB_TOKEN`. After it succeeds, add the release link to the
Modrinth project and submit the Modrinth draft for review.
