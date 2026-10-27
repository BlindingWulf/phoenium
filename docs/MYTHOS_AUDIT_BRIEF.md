# Mythos audit brief

This is the engineering mission for **Mythos**, an independent reviewer of Phoenium. Mythos may investigate any part of
the codebase and propose fixes as pull requests from its own branches. Nothing Mythos produces is merged automatically
into the beta.

## Context

- **Project:** Phoenium, a Fabric server mod for Minecraft 26.2 (Java 25). It adds Folia-style regionised, tile-parallel
  server ticking. License: GPL-3.0-only. Repository: https://github.com/BlindingWulf/phoenium
- **Release under review:** `v0.2.0-beta.1` (pre-release), jar `phoenium-0.2.0-beta.1.jar`, 2,500,473 bytes, SHA-256
  `09530B8BA5FC090E584D4419CE133AD647CE6D6FDB708D82B0B2ADE76765037B`. No bundled third-party code.
- **What the beta contains:** release candidate RC1 "D45" (D43 plus entity-hook Gate v2.1), one P0 fix, and the
  version bump. The P0: in DEFAULT mode, a custom spawner read a chunk that was still loading inside an owner
  transaction and crashed the server. The fix is in
  `src/main/java/dev/phoenium/service/RegionalChunkGameplayService.java` (`runSpawnerAttempt`): it ends only that
  spawner attempt and logs `PHOENIUM_SPAWNER_TERRAIN_NOT_READY`. It also adds
  `src/main/java/dev/phoenium/chunk/ChunkSynchronousAccessGuard.java` (`unfinishedWait`). Public commit hashes differ
  from the private ones because history was rewritten for privacy, so refer to commits by subject.
- **Toolchain:** Temurin JDK 25 (tested 25.0.4.1+1), Gradle 9.5.1 through the wrapper (no global Gradle), Fabric Loom
  1.17.20, Fabric Loader 0.19.5, Fabric API 0.159.0+26.2, Minecraft 26.2 (non-obfuscated, official names). Build with
  `./gradlew build` on Linux/macOS or `.\gradlew.bat build` on Windows. The output is
  `build/libs/phoenium-0.2.0-beta.1.jar`.
- **Runtime hard requirement:** the patched C2ME build `c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2` (MIT, attached
  to the release, patches in `docs/patches/`). `src/main/java/dev/phoenium/SupportedStack.java` refuses to start with
  any other build unless `-Dphoenium.allowUnsupportedStack=true` is set. That override is unsupported. Fabric API is also
  required.
- **Two runtimes, chosen at mixin bootstrap:**
  - **DEFAULT mode (no flags).** This is the ownership/region-lease runtime in
    `src/main/java/dev/phoenium/live/PhoeniumLiveRuntime.java`, with `scheduler/RegionScheduler`, 1 gameplay worker and
    `parallel=OFF` (about 246 mixins). It logs
    `PHOENIUM_STAGE2 started lifecycle=RUNNING scheduler=RUNNING workers=1 ... parallel=OFF`. Its invariants are in
    `docs/THREADING_INVARIANTS.md`, and its history is in `docs/ARCHITECTURE.md`.
  - **ENGINE mode.** The desktop shipping configuration is `-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=5
    -Dphoenium.engine.schedule=dag` with a 4 GB heap. `engine/TileEngine` colours tiles and runs them on the
    `Phoenium Tile N` threads and the server thread. Deferred effects are applied on the server thread in colour/tile
    order. A phase falls back to serial when its width is below `minWidth` (default 1.5).
- **Entity-hook Gate v2.1:** implemented by `engine/EntityHookGate`, `engine/EntityHookScan` and `engine/MixinTargets`,
  and documented in `docs/STAGE11_ENTITY_HOOK_GATE.md`. It runs at `SERVER_STARTED`. It scans the Fabric listeners on
  `ServerLivingEntityEvents` (ALLOW_DAMAGE, AFTER_DAMAGE, ALLOW_DEATH, AFTER_DEATH, MOB_CONVERSION),
  `AFTER_KILLED_OTHER_ENTITY`, `EQUIPMENT_CHANGE` and `ServerPlayerEvents.ALLOW_DEATH`. It also scans foreign mods'
  mixins that target entity damage/death paths. Any hook, or any scan error, forces SERIAL tiles. The gate logs
  `PHOENIUM_COMPAT` decision lines and watches for listeners registered later. Overrides:
  `-Dphoenium.engine.foreignHooks=allow` and `-Dphoenium.engine.foreignHooks.allowMods=<ids>`.
- **Known limits of the beta:**
  - GC pauses with 32 players exploring on a 4 GB heap are an accepted beta limitation.
  - Economy mods are not crash-safe on a forced kill. This is an upstream problem that Phoenium does not fix.
  - Universal Graves crashed on one D43 arm; this is not yet investigated.
  - Two-client voice, legacy Java through Via, and the formal 11-step Bedrock checklist have not been validated.
  - Android behaviour requires hardware validation. Mythos has no phones or ADB, and Android scheduling or screen-off
    behaviour on Moto devices cannot be proven on a desktop or an emulator.
- **Experimental branches:** these are not in the beta, are default-off and are unvalidated. Do not treat them as
  shipped behaviour:
  - `experimental/death-confinement`
  - `experimental/tile-idle-backoff` (D44)
  - `experimental/mem-heap-governor` (D37)
  - `experimental/mem-flowsched-compaction-DO-NOT-DEPLOY` (D40b, crashes at boot)
  - `experimental/phoenix-spawnprep`

  The H-BIG Android CPU-affinity add-on is not yet published.

## 1. Mission and ground rules

1. **Correctness first.** A faster server that corrupts a world, duplicates an item, or reorders a death has regressed.
2. **Back every finding with evidence.** Cite source (file and method) or give a reproducible procedure. A speculative
   concern is welcome as an issue labelled `question`, but not as a fix.
3. **Do not trust earlier conclusions.** This includes earlier agents' reports, stage documents, milestone names and
   "PASS" labels in `docs/`. Treat them as claims to verify, not facts. Several were produced under time pressure.
4. **Write regression tests before or alongside the fix.** A fix without a test that fails on the old code needs a
   written reason, for example "requires a live server; reproduction steps attached".
5. **Avoid large rewrites.** Propose one only when you can show the architecture is unsound, with a concrete failure
   that cannot be fixed locally.
6. **Keep the Fabric 26.2 build working.** `./gradlew build` must pass on every PR branch, and the unit suite must not
   lose tests.
7. **Nothing reaches the beta automatically.** The owner reviews and merges. Do not push to `main` or to release tags.
8. **Keep the serial fallbacks conservative.** When in doubt, the correct behaviour is to fall back to serial or end the
   attempt, not to run more code in parallel.

## 2. Priority A: parallel-engine correctness

All paths below are relative to `src/main/java/dev/phoenium/`. Each target lists the questions to answer.

### A1. Engine mode (`engine/`)

- **`engine/TileEngine.java`, `engine/TileWork.java`, `engine/TileGeometry.java`, `engine/EngineSchedule.java`**
  - Which mutable state can two tiles of the same colour reach? Consider level-wide caches, `RandomSource` instances,
    POI/village managers, scoreboards, raid managers, the entity lookup, and the block entity ticker list.
  - Is colouring sufficient for every read radius that vanilla actually uses? Examples: pathfinding range, explosion
    radius, entity AI sensing range and piston chains. If not, what happens at the tile boundary?
  - DAG schedule (`schedule=dag`): can a wait or park cycle deadlock or starve? Check the spin, park and wake parameters
    printed by `EngineConfig` (`spins`, `idleParkUs`, `dagWake`, `dagParkUs`). What happens when a tile thread throws
    while other tiles wait on it?
- **`engine/EngineConfig.java`**
  - Are all system properties validated? What do malformed values do, for example `minWidth=NaN`, negative worker counts,
    or `workers` larger than the number of cores?
  - Is the `minWidth` serial fallback (`MIN_WIDTH`, default 1.5) applied in every phase, including phases added after
    D43?
- **`engine/EngineWorkerPool.java`**
  - Thread lifecycle at server stop and after a failure: are tile threads always joined or parked? Is a stuck thread
    detected?
  - Memory visibility: what establishes happens-before between a tile thread's writes and the server thread's apply step?
    Identify the exact barrier (volatile, lock, `LockSupport` park/unpark with a volatile flag, or a concurrent queue).
- **`engine/TileContext.java`, `engine/EngineOutbox.java`, `engine/EngineNeighborUpdater.java`, `engine/EngineMoveSplit.java`**
  - Deferral ordering: the deferred effects are applied in colour/tile order. Where does that differ from vanilla's
    order (entity list order, scheduled tick order, neighbour update order), and can the difference be observed? Examples:
    redstone timing, comparator outputs, two entities picking up the same item, or damage applied before or after a
    knockback.
  - Can a deferred effect go stale between capture and apply, for example when the target entity was removed or changed
    dimension, or its chunk unloaded?
- **Entity damage and death across tiles.** When entity A on tile 1 damages entity B on tile 2, where does the damage
  run, and in what order relative to B's own tick? Can B die twice, drop loot twice, or fire `AFTER_DEATH` from a tile
  thread? Death confinement on the experimental branch is not in the beta, so the beta behaviour is what needs
  answering.
- **Chunk loads from tile threads.** `engine/mixin/EngineChunkCacheMixin.java`: what happens when tile code calls
  `getChunk` for a chunk that is not loaded or not FULL? Does it block a tile thread on the server-thread main executor,
  and can that deadlock?
- **`engine/EntityHookGate.java`, `engine/EntityHookScan.java`, `engine/MixinTargets.java`.** Look for false negatives,
  where a hook exists but the gate still allows PARALLEL:
  - listeners registered after `SERVER_STARTED`: is the late-listener watch complete, and what does it do mid-tick?
  - mixins added programmatically (mixin plugins, `IMixinConfigPlugin`, mixins added from code at runtime)
  - `@Invoker` and `@Accessor` mixins, and indirect paths reached through helper methods
  - raw ASM or class-transformer edits that are not mixins
  - damage or death paths that are not in `MixinTargets`, such as explosions, thorns, fall damage, `/kill`, void damage,
    and `hurtServer` overrides in modded entity classes.

  Limitation L-d in `docs/STAGE11_ENTITY_HOOK_GATE.md` (the OPAC explosion repro) is a known false-negative class.
  Characterise it and propose a sound fix.
- **`engine/EngineProtections.java`.** Which vanilla invariants does it protect, and which does it only detect? Are its
  checks reachable in production builds, or only behind diagnostics?
- **`engine/EngineAutosave.java` and save in engine mode.** Is every chunk and entity written by the autosave captured
  at a consistent point, with no tile still running?

### A2. Memory and the C2ME interaction

- **`mem/NoiseRelease.java`, `mem/MemSwitches.java`, `mixin/MemNoiseReleaseMixin.java`.** These are installed in both
  modes. Can memory be released while a C2ME worker or a tile still references it, which would be a use-after-release?
- **`mixin/C2meScheduledTaskReleaseMixin.java`.** How does it interact with a C2ME task that completes mid-tick while
  tiles are running? Are released tasks unreachable from every queue? A stale queue entry pinning finished tasks has been
  a memory-leak class in this project before.

### A3. DEFAULT-mode ownership

- **`ownership/OwnershipContext.java`**: Can the context leak across tasks? Check thread-locals that are not cleared on
  an exception and nested transactions.
- **`chunk/ChunkSynchronousAccessGuard.java`**: Is `unfinishedWait` precise? It must match only the "chunk still loading"
  failure and never hide a real protocol violation. Add tests for the negative cases.
- **`scheduler/RegionScheduler.java`**: Lease ordering, fairness and starvation. What happens when a region lease is
  requested from inside another region's transaction?
- **`service/RegionalChunkGameplayService.java`** (`regionalCustomSpawnerCallback` path, `runSpawnerAttempt`):
  - The P0 fix wraps every `CustomSpawner.tick` routed through `mixin/RegionalCustomSpawnerMixin.java`. For each vanilla
    spawner, check whether the spawner mutates its own state before the failing read. If it does, ending the attempt
    halfway may leave the spawner in a different state than vanilla's "terrain not loaded" branch would. Candidates:
    `PhantomSpawner`, `PatrolSpawner`, `VillageSiege` (see `mixin/RegionalSiegeSpawnerAccess.java`),
    `WanderingTraderSpawner` (see `mixin/RegionalTraderSpawnerAccess.java`), and any timed spawner (see
    `mixin/RegionalTimedCustomSpawnerAccess.java`).
  - **The P0 class.** Find other vanilla paths that run inside an owner transaction and can reach `getChunk` on an
    unfinished chunk. Start with reads gated by `hasChunksAt` or `isLoaded`, because those gates count a FULL-ticketed
    chunk that is still loading. Examples: natural spawning, raids, random ticks near a freshly respawned or teleported
    player, portal search, and the POI lookups behind villager AI. List each candidate and say whether it is reachable;
    a list is useful even without a fix.
- **`failure/FailureBarrier.java`, `mixin/FailedStopMinecraftServerMixin.java`, `save/`** (`SaveCoordinator`,
  `RegionalSaveCoordinator`, `RegionalSaveBinding`, `SaveSnapshot`, `RetainedChunkSnapshot`):
  - A fail-closed stop must not save a partially applied tick. Verify that a failure inside a transaction rolls the world
    back to the last autosave and does not write the half-applied state. Verify that player data follows the same rule.
  - Can a failure during save itself leave region files or `level.dat` inconsistent?

## 3. Priority B: performance

Compare every performance claim against a baseline on an equivalent workload.

- **Targets:**
  - MSPT tail latency (p95/p99), not only the mean
  - multicore utilisation of the `Phoenium Tile N` threads
  - allocation rate and GC behaviour, including the accepted GC pauses with 32 players exploring on a 4 GB heap
  - chunk processing throughput with C2ME
  - startup time
  - work partitioning and tile sizing
  - scheduling contention
  - the `minWidth` heuristic
  - idle-park cost
- **Rules:**
  - Compare against a baseline: the previous Phoenium build and the "L5" stack without engine mode.
  - Use the same hardware and the same scenario.
  - Ignore differences within run-to-run noise.
  - Keep the correctness suite green; a performance PR that weakens a serial fallback must say so explicitly.
- The certified optimisation stack ("L5") and its Modrinth URLs are listed in `docs/STACK_LOCK.json`. Benchmarks
  should use exactly that stack.

## 4. Priority C: Fabric compatibility

- **What to look for:** third-party callbacks that run in parallel when they should not. Review:
  - entity hooks, and listeners registered late
  - mixins added programmatically
  - explosions (OPAC repro, limitation L-d in `docs/STAGE11_ENTITY_HOOK_GATE.md`)
  - protection or claim checks
  - death callbacks
  - access to modded inventories (block entities with item handlers)
  - mod APIs that are not thread-safe (static `HashMap` caches, shared `RandomSource`)
- **Keep the conservative serial fallback.** Any narrowing of the gate needs a test that shows the narrowed case is safe.
- **Death confinement** (experimental branch only) runs deaths caused on a tile on the server thread, in tile order. It
  is not a licence to run arbitrary mod code off the server thread.
- **Background reading:** `docs/STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md` and `docs/COMPATIBILITY_MATRIX.md`.
  - EconomyCraft 1.11.0 was measured PARALLEL.
  - burger-economy 1.1.0 was measured SERIAL.
  - The SMP stack (EconomyCraft, LuckPerms, Essential Commands, OPAC, Universal Graves, Ledger, VillagerBased
    PlayerShops, Simple Voice Chat) was measured SERIAL.
  - The Universal Graves crash on a D43 arm is open.

## 5. Priority D: security and availability of Phoenium-owned code

The scope is Phoenium's own server-facing code. Make no claims about volumetric DDoS.

- **Diagnostics commands.** `src/main/java/dev/phoenium/Phoenium.java` registers the `/phoenium` command (diagnostics,
  mark, test reseed) only when `Boolean.getBoolean("phoenium.test.diagnostics")` is true, in both modes. Verify that no
  other path registers it or exposes the same actions. Check `core/DiagnosticSwitches.java`,
  `engine/EngineTestReseed.java`, and every other reader of `phoenium.test.diagnostics`.
- **Unbounded growth.** Can a client cause unbounded growth in a Phoenium queue, outbox, deferral list or tracker?
  Check `engine/EngineOutbox.java`, `engine/EngineTracker*.java` and the region scheduler queues.
- **Malformed data.** Look at malformed packets or NBT reaching Phoenium code paths, memory amplification (one action
  causing large retained state), and debug features reachable in a production configuration.

## 6. Housekeeping tasks

### Task #1: make the unit suite portable

- **Current state:** the suite has 2060 tests: 0 failures and 2 skipped (env-gated), but only when the C2ME binary
  fixtures are present.
- **Affected tests:** 36 tests in `dev.phoenium.chunk`:

  | Test class | Tests |
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

- **Where the fixtures come from:** these tests read jars from the git-ignored `build/stage6-audit-20260909/research/...`,
  `build/stage8-entry/...` and `../C2ME-locked-settlement/build/patched/...settlement2.jar`.
- **Current workaround:** run `scripts/fetch_test_fixtures.py`. It downloads upstream C2ME 0.4.2-alpha.0.43 from Modrinth
  (SHA-256 `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`) and the settlement2 jar from the release
  assets (SHA-256 `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`), checks the hashes and places the
  files. `gradlew clean` deletes `build/` and the fixtures with it, so run the script again after a clean.
- **Required fix:**
  - Move fixtures to a directory outside `build/`.
  - Turn a missing fixture into a reported skip (`Assumptions`) instead of a failure.
  - Add a Gradle fetch task that verifies hashes.
  - Fix the "installed W1 evidence" test, which silently returns when the `run/` evidence is absent. It must skip
    visibly or use a fixture.

### Task #2: turn the reference harness into a portable testkit

- **What the harness contains:** `tools/reference-harness/` holds the Stage 9 load and performance runner:
  - `run_stage9.py`
  - scenarios: idle, connect, region, groups, cluster, explore, combat, mixed and mounted-portal
  - Java bots through ViaProxy and mineflayer, plus synthetic Bedrock bots
  - the LOW-RAM preset: `--heap 1536M/1024M/900M` on groups-v2 and explore-v2 with 16 bots, against an L5 control
  - the correctness gates: determinism9e8b, transitions9e8r10, protections9e8, wgeq9m (save/restart equality), memcycle9m
    (memory cycle), a3live9e8 with its validator, and stepbsw9m
  - the region and world checkers `regions.py` and `worldcmp.py`
- **Why it needs porting:** it is not portable. Paths are hard-coded for the original Windows machine.
- **The port must have:**
  - a bootstrap that downloads from official sources with SHA-256 pins, using `docs/STACK_LOCK.json`
  - config-driven paths
  - support for Linux and Windows
  - a documented list of scenarios
  - the low-RAM preset kept as a first-class preset
- **Server rules the testkit must follow:**
  - `server.properties` must set `online-mode=false`, `enforce-secure-profile=false` and `server-ip=127.0.0.1`.
  - The JVM must get all three host flags: `-Dminecraft.api.session.host=http://127.0.0.1:9`,
    `-Dminecraft.api.services.host=http://127.0.0.1:9` and `-Dminecraft.api.profiles.host=http://127.0.0.1:9`. With only
    two, 26.2 ignores them and uses the real Mojang hosts.
  - Every run must check that the startup log shows
    `Environment[sessionHost=http://127.0.0.1:9, servicesHost=..., profilesHost=..., name=properties]`.
  - Get the Minecraft server through the Fabric launcher
    (`https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.5/1.0.1/server/jar`). It downloads the official server on
    first run, and the user must accept the Minecraft EULA. Never commit or redistribute the Mojang server jar.

### Task #3: CI

- Set up GitHub Actions with Temurin 25 and the Gradle wrapper. Run `./gradlew build` and the unit suite, with fixtures
  fetched by the Task #1 mechanism and cached by hash.
- Publish test reports as artifacts.
- Do not require Minecraft servers or bots in the default CI job.

## 7. Contribution protocol

- **One PR per issue.** Use a branch named `mythos/<topic>`, for example `mythos/spawner-partial-state` or
  `mythos/unit-fixtures`.
- **Every PR contains:**
  1. **Root cause:** the mechanism, with file and method references.
  2. **Severity and reproduction:** use the rubric below. Give exact steps, or a test that fails before the fix.
  3. **Minimal diff:** no unrelated reformatting or refactoring.
  4. **Tests:** a new or changed unit test. If a live server is required, give a scripted reproduction.
  5. **Performance comparison,** if the change touches a hot path or scheduling:
     - same hardware and same scenario
     - at least 3 runs per arm
     - report p50 and p95 MSPT with the spread (min to max or standard deviation) for each arm
     - name the baseline jar by SHA-256
  6. **Compatibility impact:** gate decisions, serial fallbacks, and any change to mod-visible behaviour.
  7. **Rollback plan:** a revert is enough when the change is self-contained. If it adds a property or flag, give its
     default and say how to disable it.
- **Never commit:**
  - Mojang code or jars, or decompiled vanilla source
  - worlds or player data
  - secrets or credentials
  - all-rights-reserved (ARR) mods; link them only
  - machine-specific paths or personal data

### Severity rubric

| Severity | Meaning | Examples |
|---|---|---|
| **P0** | Crash, world or save corruption, or item/entity duplication reachable in a supported configuration | server crash from a still-loading chunk in an owner transaction; half-applied tick saved on a failed stop |
| **P1** | Incorrect game behaviour or a data race with observable effects, or a gate false negative that lets a hooked mod run in parallel | double death callback; a deferred effect applied to a removed entity; a protection check bypassed from a tile thread |
| **P2** | Performance regression beyond noise, deadlock or starvation risk without a known trigger, or an unbounded resource with a realistic bound | p95 MSPT regression across at least 3 runs; a queue that grows with player count without a cap |
| **P3** | Portability, tests, tooling, documentation, or a hardening improvement with no current user-visible effect | fixture paths; a silent-return test; CI |

A P0 or P1 claim needs a reproduction or a source-level proof. A finding without either is filed as a `question` issue.

### PR template

```markdown
## Summary
<one sentence>

## Root cause
<mechanism, with file:method references>

## Severity
P0 | P1 | P2 | P3, with a justification against the rubric in docs/MYTHOS_AUDIT_BRIEF.md

## Reproduction
<steps or failing test; mode (DEFAULT / ENGINE with flags); stack (STACK_LOCK.json entries); seed if relevant>

## Fix
<what changed and why it is minimal>

## Tests
- [ ] New or updated unit test fails before the fix and passes after
- [ ] `./gradlew build` passes; test count not reduced
- [ ] Live reproduction re-run (if applicable): <result>

## Performance (if relevant)
Hardware: <cpu/ram/os> | Scenario: <name, bots, heap> | Runs per arm: >= 3
| Arm | jar SHA-256 | p50 MSPT | p95 MSPT | spread |
|---|---|---|---|---|

## Compatibility impact
<gate decisions, serial fallback, mod-visible behaviour>

## Rollback plan
<revert / property to disable / default value>
```
