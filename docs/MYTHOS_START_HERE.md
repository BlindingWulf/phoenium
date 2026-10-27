# Start here: onboarding for Mythos

This guide is for an independent reviewer ("Mythos") who has only this GitHub repository: no phones, no ADB and no
access to the maintainers' machines. It explains what Phoenium is, how to build it and check the release hash, how to
run the unit tests and a disposable local server, how to collect metrics, and which test scenarios you can run on a
desktop today.

Statements marked **(unverified)** have not been checked against the beta jar or on a clean machine. Please confirm or
correct them.

Repository: https://github.com/BlindingWulf/phoenium. Release tag `v0.2.0-beta.1` (pre-release), version `0.2.0-beta.1`.
License: GPL-3.0-only.

---

## 1. What Phoenium is, and the two modes

Phoenium is a Fabric **server** mod for Minecraft 26.2 (Java 25). It adds Folia-style regionized and tile-parallel
server ticking (`environment: server` in `src/main/resources/fabric.mod.json`).

The beta is release candidate RC1 "D45" (which is D43 plus the entity-hook Gate v2.1) with one P0 fix and a version bump.
The P0 fix: in DEFAULT mode, a custom spawner that read a chunk that was still loading, inside an owner transaction,
crashed the server. Now `RegionalChunkGameplayService.runSpawnerAttempt`
(`src/main/java/dev/phoenium/service/RegionalChunkGameplayService.java`) ends only that spawner attempt and logs
`PHOENIUM_SPAWNER_TERRAIN_NOT_READY`. See also `ChunkSynchronousAccessGuard.unfinishedWait` in
`src/main/java/dev/phoenium/chunk/ChunkSynchronousAccessGuard.java`. The public history was rewritten for privacy, so
public commit hashes differ from the private ones. Refer to commits by their subject, not by hash.

**Runtime hard requirement:** the patched C2ME build `c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2` (MIT). The jar is
attached to the GitHub release, and the patches are in `docs/patches/` (`c2me-root-settlement.patch`,
`c2me-flowsched-settlement.patch`). `src/main/java/dev/phoenium/SupportedStack.java` refuses to start without it. The
override `-Dphoenium.allowUnsupportedStack=true` exists for tests only and is unsupported. Fabric API is also required.

Phoenium picks one of two runtimes at mixin bootstrap:

| | DEFAULT mode | ENGINE mode |
|---|---|---|
| Enable | no flags | `-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=5 -Dphoenium.engine.schedule=dag` (desktop shipping config, 4 GB heap) |
| Core | `PhoeniumLiveRuntime` (`live/`): ownership and region-lease runtime with `RegionScheduler` (`scheduler/`) | `TileEngine` (`engine/`): colours tiles and runs them on `Phoenium Tile N` threads plus the server thread |
| Workers | 1 gameplay worker, `parallel=OFF` | 5 tile workers. Deferred effects are applied on the server thread in colour/tile order. Falls back to serial when the phase width is below `minWidth` (default 1.5, `EngineConfig.MIN_WIDTH`) |
| Mixins | about 246, mostly `phoenium.mixins.json` | `phoenium.engine.mixins.json` |
| Docs | `docs/THREADING_INVARIANTS.md`, `docs/ARCHITECTURE.md` (default-mode history) | `docs/STAGE11_ENTITY_HOOK_GATE.md`, `docs/STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md` |

**Entity-hook Gate v2.1 (engine mode only):** `engine/EntityHookGate.java`, `engine/EntityHookScan.java` and
`engine/MixinTargets.java`. The gate runs at `SERVER_STARTED`. It scans the Fabric event listeners
(`ServerLivingEntityEvents` `ALLOW_DAMAGE`, `AFTER_DAMAGE`, `ALLOW_DEATH`, `AFTER_DEATH`, `MOB_CONVERSION`;
`AFTER_KILLED_OTHER_ENTITY`; `EQUIPMENT_CHANGE`; `ServerPlayerEvents.ALLOW_DEATH`) and the mixins of other mods that
target entity damage or death paths. If it finds any hook, or the scan fails, the tiles run SERIAL (on the server
thread). It logs `PHOENIUM_COMPAT` decision lines and also watches for listeners registered after start. Operator
overrides, at the operator's own risk: `-Dphoenium.engine.foreignHooks=allow` and
`-Dphoenium.engine.foreignHooks.allowMods=<ids>`.

The `/phoenium` command (diagnostics, `mark`, test reseed) is registered only with `-Dphoenium.test.diagnostics=true`.

---

## 2. Repository map

| Path | Contents |
|---|---|
| `src/main/java/dev/phoenium/Phoenium.java` | Server entrypoint |
| `src/main/java/dev/phoenium/SupportedStack.java` | Stack check (C2ME settlement2), logs `PHOENIUM_STACK` |
| `.../chunk/` (about 105 files) | C2ME/chunk-system boundary, synchronous-access guard, provider settlement |
| `.../live/` (about 67) | DEFAULT-mode runtime (`PhoeniumLiveRuntime`, `LiveDiagnostics`) |
| `.../engine/` (about 25) | ENGINE mode: `TileEngine`, `EngineConfig`, `EngineSchedule`, `EngineWorkerPool`, entity-hook gate |
| `.../mixin/` (about 249) | Mixin classes |
| `.../service/` (about 27) | Regional gameplay services (`RegionalChunkGameplayService` and others) |
| `.../ownership/`, `region/`, `scheduler/`, `topology/`, `transfer/`, `task/` | Ownership leases, region model, scheduler, topology and cross-region transfer |
| `.../save/`, `interaction/`, `failure/`, `diagnostics/`, `core/`, `mem/` | Persistence, interaction admission, failure fencing, diagnostics, core types, memory switches |
| `src/main/resources/phoenium.mixins.json`, `phoenium.full.mixins.json`, `phoenium.tick.mixins.json`, `phoenium.engine.mixins.json` | Mixin configs (all four are listed in `fabric.mod.json`) |
| `src/test/java/dev/phoenium/` | JUnit unit suite |
| `src/clientGameTest/` | Fabric client game tests (`runClientGameTest`, `runStage3ClientGameTest`) |
| `src/*Harness/`, `src/stage7*` | Older acceptance harness source sets. Most need the maintainers' evidence trees **(unverified on a clean clone)** |
| `scripts/` | Python run/compare/validate scripts from earlier stages. `scripts/fetch_test_fixtures.py` (added in this release) fetches the C2ME test fixtures |
| `docs/` | Stage-by-stage design records. Start with the files named in sections 1 and 10 |
| `docs/patches/` | C2ME settlement2 patches |
| `docs/STACK_LOCK.json` | Certified stack with official Modrinth URLs (being added) |
| `tools/reference-harness/` | Load, performance and correctness harness from the original machine. **Not portable yet** (section 8) |

**Experimental branches.** None of these is in the beta. All are default-off and unvalidated.
- `experimental/death-confinement`: `-Dphoenium.engine.deathConfinement=true`. Deaths caused on a tile run on the
  server thread in tile order.
- `experimental/tile-idle-backoff` (D44).
- `experimental/mem-heap-governor` (D37): `-Dphoenium.mem.governor=true`. Steps the view distance down under heap
  pressure. It never triggered in tests.
- `experimental/mem-flowsched-compaction-DO-NOT-DEPLOY` (D40b): **crashes at boot**.
- `experimental/phoenix-spawnprep`: a separate world-pregeneration helper mod for the Android app.

H-BIG, a big-core CPU-affinity add-on for Android, is **not yet published**.

---

## 3. Prerequisites

- **OS:** Windows 10/11 or Linux. macOS should work with the Unix commands below **(unverified)**.
- **Git.**
- **Temurin JDK 25.** Tested with 25.0.4.1+1. Use the same build if you want to reproduce the release jar byte for byte.
- **No global Gradle.** Use the wrapper (Gradle 9.5.1). Other versions: Fabric Loom 1.17.20, Fabric Loader 0.19.5,
  Fabric API 0.159.0+26.2, Minecraft 26.2 (non-obfuscated, official names).
- **Python 3** for `scripts/fetch_test_fixtures.py`.
- **Hardware (recommended):** 8 or more CPU cores and 16 GB RAM. That is enough for builds and for test servers with a
  4 to 6 GB heap.
- **Internet access** for Maven, Fabric, Mojang and Modrinth downloads. The first build downloads several GB of
  dependencies and Minecraft assets.

---

## 4. Build and verify the hash

Expected release artifact: `phoenium-0.2.0-beta.1.jar`, 2,500,473 bytes, SHA-256
`09530B8BA5FC090E584D4419CE133AD647CE6D6FDB708D82B0B2ADE76765037B`. It bundles no third-party code.

**Reproducibility (measured by CI on 2026-10-27, run 40561281506):** a fresh **Windows** checkout built with Temurin 25
reproduces the release jar **byte for byte** (`09530B8B…037B`, 2,500,473 bytes). A fresh **Linux** checkout builds a
different jar (`6341915E…E860`, 2,500,264 bytes; all tests pass). The likely cause, still unconfirmed, is line endings: Git
for Windows checks text resources (mixin JSON, `fabric.mod.json`, `LICENSE`) out with CRLF, and Linux checks them out
with LF. The class files should be identical. Making the jar reproducible across platforms (for example a
`.gitattributes` with `eol=lf` for resources) is a welcome small PR. If it changes the release hash, say so in the PR.

### PowerShell (Windows)

```powershell
git clone https://github.com/BlindingWulf/phoenium.git
cd phoenium
git checkout v0.2.0-beta.1
python scripts/fetch_test_fixtures.py
.\gradlew.bat build --no-daemon
Get-FileHash .\build\libs\phoenium-0.2.0-beta.1.jar -Algorithm SHA256
(Get-Item .\build\libs\phoenium-0.2.0-beta.1.jar).Length
# expect 09530B8BA5FC090E584D4419CE133AD647CE6D6FDB708D82B0B2ADE76765037B and 2500473
```

### bash (Linux)

```bash
git clone https://github.com/BlindingWulf/phoenium.git
cd phoenium
git checkout v0.2.0-beta.1
python3 scripts/fetch_test_fixtures.py
./gradlew build --no-daemon
sha256sum build/libs/phoenium-0.2.0-beta.1.jar
stat -c %s build/libs/phoenium-0.2.0-beta.1.jar
# expect 09530b8ba5fc090e584d4419ce133ad647ce6d6fdb708d82b0b2ade76765037b and 2500473
```

`build` runs the unit tests. Run the fixture script before the build, or 36 tests fail (section 5). To build the jar
without the tests, add `-x test`.

---

## 5. Unit tests

```powershell
.\gradlew.bat test --no-daemon        # Windows
```
```bash
./gradlew test --no-daemon            # Linux
```

Expected result: **2060 tests, 0 failures, 2 skipped**, provided the C2ME binary fixtures are present. The two skips are
gated on environment variables:
- `src/test/java/dev/phoenium/chunk/NativePortalGraphParentNullControlTest.java` runs only with `PHOENIUM_GRAPH_STRICT=true`.
- `src/test/java/dev/phoenium/live/MobTailCensusTest.java` runs only when `PHOENIUM_TAIL_PINS` is set.

**Known test portability issue (Mythos task #1).** 36 tests in `dev.phoenium.chunk` read C2ME jars from paths that git
ignores:

| Test | Tests that need fixtures |
|---|---|
| `C2meDiskReadOperationTest` | 10 |
| `C2meDiskReadRxTest` | 2 |
| `C2meFullProviderBinaryTest` | 5 |
| `C2meGenerationProviderBinaryTest` | 2 |
| `C2meNativeAvailabilityTest` | 2 |
| `C2meNativeFailureSettlementTest` | 1 |
| `C2meReadProviderBinaryTest` | 5 |
| `C2meScheduledTaskReleaseBinaryTest` | 2 |
| `C2meSendReadyInputsTest` | 7 |

The paths are `build/stage6-audit-20260909/research/...`, `build/stage8-entry/...` and
`../C2ME-locked-settlement/build/patched/...settlement2.jar`. `scripts/fetch_test_fixtures.py` does the following:
- downloads upstream C2ME 0.4.2-alpha.0.43 from Modrinth (SHA-256
  `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`);
- downloads the settlement2 jar from the GitHub release assets (SHA-256
  `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`);
- checks both hashes and puts the jars where the tests expect them.

The tests have no JUnit assumptions, so a missing fixture is an **error**, not a skip. `gradlew clean` deletes `build/`
and the fixtures with it, so run the fetch script again after any `clean`. One more test (installed W1 evidence) returns
early and passes without checking anything when its `run/` evidence is absent.

A proper fix is open work: a fixture directory outside `build/`, assumptions or explicit skips, and a Gradle fetch task.

---

## 6. Run a disposable local dedicated server

### 6.1 Obtain Minecraft legally

1. Make an empty server directory.
2. Download the Fabric server launcher:
   `https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.5/1.0.1/server/jar`. Save it as `fabric-server-launch.jar`.
3. Run it once (command in 6.4). On first run it downloads the official Mojang server. It then stops and asks you to
   accept the EULA.
4. Read the Minecraft EULA. If you accept it, set `eula=true` in `eula.txt`.

**Never commit or redistribute the Mojang server jar.**

### 6.2 `mods/` for the three configurations

Official download URLs are listed in `docs/STACK_LOCK.json` (being added). Check each SHA-256 against that file.

**A. Standalone (minimum).** Fabric API 0.159.0+26.2, C2ME settlement2
(`c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2`, from the release assets), and `phoenium-0.2.0-beta.1.jar`. C2ME
settlement2 is a **hard requirement**: without it `SupportedStack` refuses to start.

**B. Certified optimization baseline ("L5").** Everything in A, plus:
- Lithium 0.25.3+mc26.2
- ScalableLux 0.3.0-alpha.0.3
- ServerCore 1.5.19+26.2
- Clumps 26.2.1
- Chunky 1.5.3
- Geyser 2.11.3-b1245
- Floodgate 2.2.6-b67
- ViaVersion 5.12.0 and ViaBackwards 5.12.0
- ViaFabric 0.4.21
- NoChatReports 2.20.2
- Simple Voice Chat 2.6.23 (link only; its license is ARR, so it is not redistributed)

**C. Optional SMP compatibility (Stage 11, validated on desktop).**

| Set | Result | Expected gate decision (engine mode) |
|---|---|---|
| EconomyCraft 1.11.0 | 28/28 | PARALLEL |
| burger-economy 1.1.0 | 25/25 | SERIAL |
| SMP stack: EconomyCraft + LuckPerms + Essential Commands + OPAC + Universal Graves + Ledger + VillagerBased PlayerShops + Simple Voice Chat | 23/23 | SERIAL (mods with entity hooks correctly force serial tiles) |

Caveats for configuration C:
- Economy mods are **not crash-safe when the server is killed** (an upstream issue, not fixed by Phoenium). Stop the
  server with `stop`.
- Universal Graves crashed once on a D43 arm. This is still under investigation.
- No `/kit` mod has been verified for 26.2. Do not rely on one.

### 6.3 `server.properties`

```properties
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25565
level-name=mythos-<scenario>-<yyyymmdd-hhmm>
level-seed=
```

Use a **new `level-name` for every run** and leave the seed blank. These are disposable worlds: delete the world folder
after you have copied the logs and metrics you need. Do not reuse worlds across runs.

### 6.4 Java command lines

You must pass **all three** Mojang host flags. With only two, 26.2 ignores them and uses the real Mojang hosts. The flags
point the services at a dead local port, so the test server makes no real auth calls.

In PowerShell, quote every `-D` argument. Unquoted, `-Dx.y=z` can be split into `-Dx` and `.y=z`.

**DEFAULT mode (test run):**

```powershell
java -Xmx4G "-Dphoenium.test.diagnostics=true" "-Djava.net.useSystemProxies=false" `
  "-Dminecraft.api.session.host=http://127.0.0.1:9" "-Dminecraft.api.services.host=http://127.0.0.1:9" `
  "-Dminecraft.api.profiles.host=http://127.0.0.1:9" -jar fabric-server-launch.jar nogui
```
```bash
java -Xmx4G -Dphoenium.test.diagnostics=true -Djava.net.useSystemProxies=false \
  -Dminecraft.api.session.host=http://127.0.0.1:9 -Dminecraft.api.services.host=http://127.0.0.1:9 \
  -Dminecraft.api.profiles.host=http://127.0.0.1:9 -jar fabric-server-launch.jar nogui
```

**ENGINE mode (test run, desktop shipping config):**

```powershell
java -Xmx4G "-Dphoenium.engine.tiles=true" "-Dphoenium.engine.workers=5" "-Dphoenium.engine.schedule=dag" `
  "-Dphoenium.test.diagnostics=true" "-Djava.net.useSystemProxies=false" `
  "-Dminecraft.api.session.host=http://127.0.0.1:9" "-Dminecraft.api.services.host=http://127.0.0.1:9" `
  "-Dminecraft.api.profiles.host=http://127.0.0.1:9" -jar fabric-server-launch.jar nogui
```
```bash
java -Xmx4G -Dphoenium.engine.tiles=true -Dphoenium.engine.workers=5 -Dphoenium.engine.schedule=dag \
  -Dphoenium.test.diagnostics=true -Djava.net.useSystemProxies=false \
  -Dminecraft.api.session.host=http://127.0.0.1:9 -Dminecraft.api.services.host=http://127.0.0.1:9 \
  -Dminecraft.api.profiles.host=http://127.0.0.1:9 -jar fabric-server-launch.jar nogui
```

Stop the server with `stop` in the console, not by killing the process.

### 6.5 Startup checklist (`logs/latest.log`)

Check every line below before any client connects:

1. `Environment[sessionHost=http://127.0.0.1:9, servicesHost=http://127.0.0.1:9, profilesHost=http://127.0.0.1:9, ... name=properties]`.
   If it shows `name=PROD`, stop: the host flags were not applied.
2. `PHOENIUM_STACK supported: c2me <version>`. If you see `PHOENIUM_STACK unsupported...`, the run is invalid.
3. `PHOENIUM_STAGE2 entrypoint registered once; live integration awaits server start.`
4. Minecraft `Done (...)`.
5. Phoenium start line:
   - DEFAULT: `PHOENIUM_STAGE2 started lifecycle=RUNNING scheduler=RUNNING workers=1 ... parallel=OFF ...`
     (the fields come from `live/LiveDiagnostics.toLogLine`).
   - ENGINE: `PHOENIUM_STAGE2 started lifecycle=RUNNING scheduler=ENGINE workers=5 mode=engine-tiles ... threads=6 parallel=TILES ...`
     (`engine/TileEngine.java`). The `EngineConfig.describe()` part should include `schedule=dag` and `minWidth=1.5`.
     The values `threads=6` and `parallel=TILES` for this config are **(unverified)**.
6. ENGINE mode only: `PHOENIUM_COMPAT ...` decision lines. With configurations A and B, expect parallel entity hooks.
   With the configuration C sets marked SERIAL above, expect `entityHooks=SERIAL` together with
   `PHOENIUM_COMPAT entity hook mod=<id> kind=<kind> ...` lines that name the mods responsible. Exact text:
   `engine/EntityHookScan.java`, `engine/TileEngine.java`.
7. With Geyser/Floodgate (B, C): Geyser is listening on its Bedrock UDP port and Floodgate has initialized.

---

## 7. Collecting metrics

| Metric | How |
|---|---|
| TPS and MSPT | Run `/tick query` in the server console. In 26.2 it prints TPS and P50/P95/P99 MSPT. Sample it at fixed intervals during a scenario |
| GC | Add `-Xlog:gc*:file=gc.log` to the java command |
| Profile | Add `-XX:StartFlightRecording=filename=rec.jfr,settings=profile`. Read it with `jfr print` or JDK Mission Control |
| Heap | `jcmd <pid> GC.heap_info` (find the pid with `jcmd -l`) |
| CPU | OS tools: `top`/`pidstat` on Linux, Task Manager or `Get-Process java` on Windows |
| Errors | `logs/latest.log` (search `ERROR`, `Exception`, `PHOENIUM_STAGE2 live tick failure`, `PHOENIUM_SPAWNER_TERRAIN_NOT_READY`) and `crash-reports/` |
| Phoenium state | `/phoenium` diagnostics (needs `-Dphoenium.test.diagnostics=true`) |

Record the exact jar SHA-256, the `mods/` list with hashes, the JVM flags, the hardware and the world name with every
result.

---

## 8. Test scenarios you can run without phones

Existing tools:
- **Unit tests** (`gradlew test`).
- **Fabric client game tests.** `runClientGameTest` starts a real Minecraft client from the command line, offline, as
  `PhoeniumTest0` (a name, not an account). `runStage3ClientGameTest` connects that client over TCP to a separate
  dedicated server. Both are defined in `build.gradle`. Neither is run by `build`; both download the client.
  `runStage3ClientGameTest` may depend on fixtures from the original machine. Its status on the beta is
  **(unverified)**.
- **Reference harness** (`tools/reference-harness/`).
  - `run_stage9.py`: load and performance runner. Scenarios: idle, connect, region, groups, cluster, explore, combat,
    mixed and mounted-portal. It drives Java bots through ViaProxy + mineflayer and synthetic Bedrock bots. It includes
    a LOW-RAM preset: a desktop run with phone-size heaps (`--heap 1536M`, `1024M`, `900M`) on groups-v2 and explore-v2,
    with 16 bots, compared against an L5 control.
  - Correctness gates: `determinism9e8b`, `transitions9e8r10`, `protections9e8`, `wgeq9m` (save/restart equality),
    `memcycle9m` (memory cycle), `a3live9e8` with its validator, and `stepbsw9m`.
  - Checkers: `regions.py` (region files) and `worldcmp.py` (world comparison).
  - **The harness is not portable yet.** It hard-codes Windows paths from the original machine. Porting it into a
    portable testkit is **Mythos task #2**: bootstrap everything from official downloads with SHA-256 pins, read paths
    from config, and run on Linux and Windows.

| Area | What exists today | What must be ported or added |
|---|---|---|
| Entity ticking / deterministic ordering | Unit tests under `src/test/java/dev/phoenium/engine`, `live`, `topology`. Harness `determinism9e8b` (RC1: workers 1/2/5, 160 entities, 0 diffs) | Port `determinism9e8b` |
| Combat / deaths | Harness `run_stage9.py` combat scenario, `protections9e8` | Port both. Add a death-ordering check for engine mode (see `experimental/death-confinement`) |
| Item conservation | `protections9e8` **(unverified that it covers item counts)** | A portable item-count invariant (drop, pickup, hopper, death drops) |
| Chunk load/unload | Unit tests in `dev.phoenium.chunk` (need fixtures). Harness explore scenario and the unload/reload integrity gate | Port explore and unload/reload |
| Portals / dimensions | `transitions9e8r10` (RC1: 6/6. The older r7 fixture had an intermittent vanilla portal re-entry flake, 4/10, also on D43). Harness mounted-portal scenario | Port both |
| Concurrent players | Harness groups, cluster and mixed scenarios with Java and synthetic Bedrock bots | Port the bot drivers (ViaProxy + mineflayer) |
| Save/restart integrity | `wgeq9m` save/restart equality (RC1 PASS. Bit equality is inconclusive because world generation is nondeterministic) | Port it, and compare only deterministic fields |
| Region-file validation | `regions.py`, `worldcmp.py` | Port them. These are easy to make standalone |
| Callback thread affinity | Unit tests. Gate v2.1 scan logs | A test mod that registers each gated Fabric event and asserts which thread runs it in both modes |
| Deadlock / race detection | Unit tests, A3 gate `a3live9e8` (serial + parallel) | Port it. Add `jcmd Thread.print` sampling on stall and JFR lock profiling |
| Memory pressure / GC | `memcycle9m` (RC1: 35 Java + 15 Bedrock bots, post-GC 446/485/504 MB). LOW-RAM preset in `run_stage9.py` | Port both. Run the LOW-RAM preset as a desktop stand-in only (it is not phone validation) |
| Gate classification | `EntityHookScan` / `EntityHookGate` logs. Stage 11 SMP runs (section 6.2 C) | A portable script that installs each mod set and checks for the expected `PHOENIUM_COMPAT` decision |
| No-add-on performance | Configuration A versus vanilla + Fabric API, with `/tick query`, GC log and JFR | A portable A/B runner. RC1 A/B was against D43 only (t16, 8/8, no regression) |

Do not make performance claims from single runs. Report the hardware, the repeat count and the spread.

---

## 9. HARDWARE VALIDATION REQUIRED (Android)

Mythos cannot validate any of the following. Desktops and emulators cannot show how Moto devices schedule work or
behave with the screen off. These items stay open for the maintainers' own agents:

- The Phoenix Android app running Phoenium on Moto G phones. Default mode was stable for 45 minutes with a 1536 MB heap.
  The release build runs default mode with the engine off.
- Engine mode on phones (not shipped there).
- Behaviour with the screen off, in the background or under thermal throttling, and the app process being killed.
- H-BIG big-core affinity (not published) and the effect of world pregeneration (`experimental/phoenix-spawnprep`).
- Bedrock: the formal 11-step checklist was not run. Protocol and startup smoke tests passed, and one real owner session
  worked until it hit the P0 crash that is now fixed. Two-client voice and legacy Java through Via are not validated.

Focused plan for the maintainers' agents:
1. Run the exact beta jar in the release app build, default mode, heap 1536 MB, on a fresh world. Run 45 minutes or more
   idle, then with join/leave cycles. Record `/tick query`, the GC log and logcat.
2. Repeat step 1 with the screen off and the app in the background. Record watchdog events and catch-up behaviour.
3. Run the formal Bedrock checklist with a real player: join, move, combat, death/respawn, pearl/portal, restart.
4. Two-client voice gate and the legacy Java (Via) gate.
5. Add the results to `docs/VALIDATION.md` with the device model, jar SHA-256 and world name.

---

## 10. Further reading and contributing

- Known limitations: `docs/KNOWN_LIMITATIONS.md`. Gate v2.1 is being re-run on the exact beta jar, and the results will
  be added to `docs/VALIDATION.md`.
- Audit brief and contribution guide: `docs/MYTHOS_AUDIT_BRIEF.md`.
- Open tasks to start with: **#1** portable test fixtures (section 5), **#2** a portable testkit from
  `tools/reference-harness/` (section 8).
