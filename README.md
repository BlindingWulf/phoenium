# Phoenium

Phoenium is a **Fabric server mod for Minecraft 26.2** (Java 25) that brings Folia-style regionized / tile-parallel
ticking to a Fabric server. It is designed to run beside C2ME, ScalableLux, Lithium and ServerCore.

> **Status: BETA (0.2.0-beta.1).** Not stable. Desktop gates passed on the RC1 build; the beta adds one crash fix.
> Read [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) before running it on a server you care about. Keep backups.

- **Download:** [GitHub releases](https://github.com/BlindingWulf/phoenium/releases) (jar + SHA-256 + manifest).
- **Independent reviewers / AI agents:** start at [docs/MYTHOS_START_HERE.md](docs/MYTHOS_START_HERE.md); the
  engineering mission is [docs/MYTHOS_AUDIT_BRIEF.md](docs/MYTHOS_AUDIT_BRIEF.md).
- **Changes:** [CHANGELOG.md](CHANGELOG.md). **Validation evidence:** [docs/VALIDATION.md](docs/VALIDATION.md).

## Two modes

| Mode | How | What it does |
|---|---|---|
| **Default** (no flags) | nothing to set | Ownership / region-lease runtime (`PhoeniumLiveRuntime`, `RegionScheduler`), 1 gameplay worker, `parallel=OFF`. Stable, conservative, no performance claim. |
| **Engine** (desktop shipping config) | `-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=5 -Dphoenium.engine.schedule=dag` (4 GB heap) | `TileEngine`: entities are ticked on colored tiles across `Phoenium Tile N` threads plus the server thread; side effects are deferred and applied on the server thread in a fixed order. **Gate v2.1** switches to SERIAL tiles automatically when another mod hooks entity damage/death. |

## Requirements

- Minecraft **26.2** dedicated server with **Fabric Loader 0.19.5** and **Fabric API** (0.159.0+26.2 tested).
- **Java 25** (Temurin 25.0.4.1 tested).
- The **patched C2ME build** `c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar` (MIT, attached to the release; the
  patches are in [docs/patches/](docs/patches/)). Phoenium refuses to start without it.
- Pinned, hash-checked versions of the tested stack: [docs/STACK_LOCK.json](docs/STACK_LOCK.json).

## Build from source

```bash
git clone https://github.com/BlindingWulf/phoenium.git
cd phoenium
python3 scripts/fetch_test_fixtures.py     # C2ME binary fixtures for 36 unit tests (hash-verified downloads)
./gradlew build                            # Windows: .\gradlew.bat build
sha256sum build/libs/phoenium-0.2.0-beta.1.jar
```

No global Gradle install is needed (wrapper 9.5.1). Full details, server setup and test scenarios:
[docs/MYTHOS_START_HERE.md](docs/MYTHOS_START_HERE.md).

## License

Phoenium is licensed under **GPL-3.0-only** ([LICENSE](LICENSE)). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Minecraft is a trademark of Mojang/Microsoft; this project is not affiliated with them. Never redistribute the Minecraft
server jar: the Fabric launcher downloads it from Mojang and you must accept the Minecraft EULA yourself.
