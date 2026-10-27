# Mythos code map: Phoenium and Phoenix

A navigation guide for an independent engineer working from the two public repositories only. Every path below was
checked against the public sources on 2026-10-27; line numbers are from Phoenium `main` and drift with later commits,
so search by method name. Where a claim comes only from a design document, it says so.

| Repository | URL | Default branch | Release tag | Head checked |
|---|---|---|---|---|
| Phoenium (Fabric mod) | https://github.com/BlindingWulf/phoenium | `main` | `v0.2.0-beta.1` (pre-release, commit `b371a11`) | `d03441c` (adds CI and docs only) |
| Phoenix (Android app) | https://github.com/BlindingWulf/phoenix-android | `main` | `v0.5.0-beta.1` (pre-release, commit `e4154f4`) | `c327cfa` (CI and test fixes) |

Phoenium paths are relative to `src/main/java/dev/phoenium/` unless they start with `src/`, `docs/` or `tools/`.
Phoenix paths are relative to the repository root.

---

## Part 1: Phoenium

### 1.1 How the two runtimes are chosen

| What | Where | Notes |
|---|---|---|
| Mod entrypoint | `Phoenium.java` | `LIVE` is `null` in engine mode. Engine mode calls `TileEngine.install()`. The `/phoenium` command is registered only with `-Dphoenium.test.diagnostics=true`. |
| Stack check | `SupportedStack.java` | Refuses to start without C2ME `0.4.2-alpha.0.43+26.2-phoenium-settlement2`. Logs `PHOENIUM_STACK`. |
| Engine switches | `engine/EngineConfig.java` | Every `phoenium.engine.*` property, read once at mixin bootstrap. Defaults: `workers=3`, `tileShift=2` (4x4 chunks), `claimRadius=1`, `schedule=colours`, `minWidth=1.5`, `spins=8192`, `idleParkUs=1000`. The desktop shipping config overrides `workers=5 schedule=dag`. |
| Mixin selection | `mixin/PhoeniumMixinConfigPlugin.java` `shouldApplyMixin`, `ENGINE_RETAINED`; `mixin/FullBoundaryPlugin.java`; `mixin/LiveTickPhasePlugin.java`; `engine/mixin/EngineMixinPlugin.java` | In engine mode only five default-mode mixins stay (`TrackedEntityRangeFastPathMixin`, `PublishedDirectHolderSetMixin`, `C2meScheduledTaskReleaseMixin`, `ChunkAccessNoiseAccess`, `MemNoiseReleaseMixin`), plus the A3 selector hooks when A3 is on. `phoenium.full.mixins.json` and `phoenium.tick.mixins.json` are off. **So none of the `FailedStop*` mixins apply in engine mode** (see 1.6). |
| Mixin configs | `src/main/resources/phoenium.mixins.json`, `phoenium.full.mixins.json`, `phoenium.tick.mixins.json`, `phoenium.engine.mixins.json` (44 engine mixins) | All four are listed in `fabric.mod.json`. |

### 1.2 ENGINE mode: the tile engine (primary audit target)

Design in one paragraph: in `ServerLevel.tick`, the entity loop and in `ServerChunkCache.tickChunks` the chunk loop are
intercepted. Entities and chunks are sorted into **tiles** (2^tileShift chunks square). Each tile has a **claim**: all
tiles within `claimRadius` (Chebyshev). Tile `(tx,tz)` has colour `(tx mod P) + P*(tz mod P)`, `P = 2r+1`, so two tiles
whose claims overlap always differ in colour. Same-colour tiles run concurrently on `Phoenium Tile N` threads plus the
server thread. Side effects that touch shared structures are **deferred** into the tile's list and applied on the server
thread in colour-then-tile-key order. Work a tile cannot do while others run (load a chunk, a POI column, write outside
its claim) is a **service**: the tile pauses until no other tile runs, then the server thread does it inline.

| System | File / method | Purpose | Invariant it must keep | Tests |
|---|---|---|---|---|
| Install / start / stop | `engine/TileEngine.java` `install` (157), `start` (165), `stop` (218) | Registers `SERVER_STARTED`/`SERVER_STOPPING`/`START_SERVER_TICK`. `start` runs the hook gate, creates `EngineWorkerPool` only when the gate says PARALLEL. | No pool exists when the gate is SERIAL; `hooksSerial` latches once set. | none for TileEngine itself |
| Entity phase | `TileEngine.tickEntities` (248) -> `LevelPhase.classify` (665), `LevelPhase.run` (676) | Wraps `EntityTickList.forEach`: first pass classifies, then tiles run the vanilla body per entity, then the **serial set** runs on the server thread. | Entity order inside a tile is tick-list order; serial set runs last. | live gates only |
| Serial set | `TileEngine.serial(Entity)` (573) | Players, raiders, ender pearls, dragon, wither, hopper/command minecarts, anything with `portalProcess`, dolphins, vehicles with player passengers. | Anything that acts across distances larger than a claim must be here. | none |
| Chunk phase | `beginChunks` (264), `recordSpawnChunk` (282), `recordTickChunk` (296), `runChunks` (309); mixins `engine/mixin/EngineChunkCacheMixin.java`, `EngineServerLevelMixin.java` | Records `tickSpawningChunk` and `tickChunk` calls (natural spawning, random ticks, precipitation, thunder) per tile and runs them before the custom spawners. | Falls back to inline (`chunkFallbacks`) when the shared arguments differ. | none |
| Colour schedule | `LevelPhase.runColours` (707) | One colour at a time, barrier and apply after each colour. Dispatch largest-measured-first; apply in key order. | Apply order is deterministic (sorted `BY_KEY`), independent of thread timing. | `EngineScheduleTest` |
| DAG schedule | `LevelPhase.runDag` (744); `EngineWorkerPool.DagJob` (216), `DagJob.release` (255) | Builds successors to every higher-colour tile within `2*claimRadius`; a tile starts when its pending count hits 0. Applies **all** deferred effects after the whole phase. | Two tiles whose claims touch never run at once; predecessors finish first. | `EngineWorkerPoolTest.aDagJobKeepsNeighboursApartAndPredecessorsFirst`, `aDagJobDrainsAfterAFailure` |
| Width fallback | `engine/EngineSchedule.java` `dagWidth`, `colourWidth`, `serial` | D43: a phase whose total weight / critical-chain weight is below `minWidth` runs on the server thread alone, same order. | Structural (entity and chunk counts), so the same world state gives the same choice. | `EngineScheduleTest` |
| Worker pool | `engine/EngineWorkerPool.java` `run(Job, TileContext)` (391), `Job.runOne` (90), `Job.beginTile` (116), `Job.service` (138), `Job.serve` (162), `Worker.run`, `stop` (418) | Publishes a job via a volatile field, runs tiles on the caller too, waits on `remaining`. `active == -1` while the server thread runs services. First failure is kept; later tiles are skipped; the failure is rethrown after the job drains. | Happens-before: tile writes -> `remaining.decrementAndGet()` -> coordinator's `remaining.get()`; DAG successors via `pending` + `ConcurrentLinkedQueue`; service result via volatile `done`. No service runs while any tile is active. `stop` joins each worker for 2 s (daemon threads). | `EngineWorkerPoolTest` (11 tests: drain, failure rethrow, dag order, dag wake, services, service failure, for-jobs) |
| Per-thread tile state | `engine/TileContext.java` `enter`, `exit`, `claims`, `defer`, `chunk`, `fullChunk` | Own level random (seeded per tile per tick), path-type cache, neighbour updater, 4-entry chunk cache, outbox. | Owned by one thread. `claims(x,z)` is the only claim check used by the write guard. | `TileGeometryTest` |
| Geometry | `engine/TileGeometry.java` `colour`, `inClaim`, `seed` | Pure arithmetic. | Same-colour tiles are >= P tiles apart on some axis. | `TileGeometryTest` |
| Services | `TileEngine.service` (355), `runService` (365), `loadChunk` (379), `storage` (386), `write` (396), `villageUpdates`, `structureSearch` | Server thread runs the work with the running tile set aside. `services=false` turns them into traps (`loadTrap` returns an `EmptyLevelChunk`). | A service never runs while another tile runs. | `EngineWorkerPoolTest.servicesRunOnTheCallerWhileNoTileRuns` |
| Claimed writes | `engine/mixin/EngineLevelChunkMixin.java` `setBlockState` | A block write outside the claim becomes a `write` service. | **Only block writes are claim-checked.** Block reads, block-entity creation and entity-to-entity effects outside the claim are not checked (see priorities P2). | none |
| Chunk lookups from tiles | `EngineChunkCacheMixin.phoenium$tileChunk`, `phoenium$tileChunkNow`; `TileContext.chunk` | Present chunks from the visible holder map; a miss with `load=true` is a load service. From a parallel-for item a request throws (`checkChunkRequest`). | Tiles never block on the main-thread executor directly. | none |
| Block entities from tiles | `engine/mixin/EngineLevelMixin.java` `phoenium$tileBlockEntity` | Returns `getChunkAt(pos).getBlockEntity(pos, IMMEDIATE)` for tile threads (vanilla returns null off-thread). `IMMEDIATE` may create and register a block entity. | No claim check here. | none |
| Deferred effects | `EngineChunkCacheMixin` (`blockChanged`, tickets), `EngineServerLevelMixin` (`blockEvent`, `sendBlockUpdated`, `updatePOIOnBlockStateChange`), `EngineLevelTicksMixin.schedule`, `EngineLevelMixin.addBlockEntityTicker`, `EngineEntityManagerMixin.addNewEntity`, `EngineSectionCallbackMixin.onMove`/`onRemove`, `EngineScoreboardMixin`, `EngineServerPlayerMixin.awardStat`, `EnginePoiManagerMixin`, `EngineSectionStorageMixin.setDirty` | Shared-structure mutations queued per tile. `addNewEntity` returns `true` at once and adds later only if the entity is not removed. | Applied in `LevelPhase.apply` (825) in deterministic order. **Discarded by `LevelPhase.clear` (839) if anything in the phase throws** (priorities P1). | none at unit level |
| Shared lazy maps | `EngineMixinPlugin.postApply` -> `synchronize` | Makes `ClassInstanceMultiMap.find` and Lithium's lazy group lists, and Lithium's navigation registration, `synchronized`. Rewrites `Level.randValue` accesses to per-tile values. Guards C2ME `executeTasksMidTick` while a colour runs. | Transform counts print on the start line (`transforms=`). | `MixinTargetsTest` (gate), none for transforms |
| Parallel-for jobs | `TileEngine.runParallel` (485), `deliverHeld` (530); `engine/EngineTrackerSends.java`; `engine/EngineSpawnMemo.java` `createState`, `sliced`, `merge`; `engine/EngineMoveSplit.java` (`moveSplit`, default off); `engine/EngineOutbox.java` | Outside tiles: tracker `ServerEntity.sendChanges`, spawn-state counting in slices, held packet delivery one thread per connection. | Runs only when the pool exists and `hooksSerial` is false. **The hook gate does not judge the code these jobs run** (priorities P3). | `EngineOutboxTest`, `EngineTrackerIndexTest` |
| Tracker index | `engine/EngineTrackerIndex.java`, `engine/mixin/EngineChunkMapTrackerMixin.java` | Skips tracker checks that cannot change anything; self-check every `trackerIndex.check` ticks. | Index equals the entity map at each check. | `EngineTrackerIndexTest` |
| Autosave spread | `engine/EngineAutosave.java`, `EngineAutosave*Mixin` | Optional (`autosaveSpread`, default off) spreading of autosave chunk/entity/player saves over ticks. Keep OFF on economy servers. | `/save-all` and shutdown save stay synchronous. | `EngineAutosaveTest` |
| Protections in engine mode | `engine/EngineProtections.java` | A3 mob decisions, projectile cap, pearl cap keyed by tile. Self-check counters must stay 0. | Off unless enabled. | live gate `protections9e8` |

### 1.3 Stage 11 entity-hook gate (Gate v2.1)

| What | Where | Notes |
|---|---|---|
| Decision rule | `engine/EntityHookGate.java` `decide`, `DEFAULT_CERTIFIED`, `certified` | Any hook from a mod outside the certified list -> SERIAL (`pool == null`). `fabric-*` and `c2me-*` ids count as certified. Overrides: `phoenium.engine.foreignHooks=allow`, `phoenium.engine.foreignHooks.allowMods=a,b`. |
| What counts as an entity target | `EntityHookGate.isEntityTarget` | Only `net.minecraft.world.entity.*` and `ServerPlayer`. This is limitation **L-d**: explosions, blocks, loot, scoreboard, advancements, level events, `ServerEntity`, `PlayerList`, `NaturalSpawner` are not judged. |
| Tile-reachable methods | `EntityHookGate.isTileHook`, `PLAYER_CLASSES`, `SERVER_THREAD_ONLY` | Mixins touching only server-thread-only player methods are discounted. |
| Scan | `engine/EntityHookScan.java` `run` (54), `listeners` (147), `mixins` (228), `tileHook` (261), `applied` (278) | Listener arrays of 8 events (`EVENTS`): `ServerLivingEntityEvents` ALLOW_DAMAGE/AFTER_DAMAGE/ALLOW_DEATH/AFTER_DEATH/MOB_CONVERSION, `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY`, `ServerEntityEvents.EQUIPMENT_CHANGE`, `ServerPlayerEvents.ALLOW_DEATH`. Mods' mixin configs read from their own files. Applied-mixin cross-check through Mixin internals (failure only warns). Any scan exception -> `scan-error` -> SERIAL. |
| Mixin parsing | `engine/MixinTargets.java` `targets`, `hookTargets`, `injectedMethods`, `selectorName`, `configClasses`, `modConfigs` | Interface mixins add nothing. Wildcard/regex/`@Desc` selectors make the injected set unknown -> treated as a hook. |
| Late listeners | `EntityHookScan.Watch`, `TileEngine.watchHooks` (199) | Re-reads the 8 listener arrays at `START_SERVER_TICK`; a new foreign listener latches SERIAL. Events outside the list are not watched. |
| Docs | `docs/STAGE11_ENTITY_HOOK_GATE.md` (limitations L-a..L-e), `docs/STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md`, `docs/COMPATIBILITY_MATRIX.md` | |
| Tests | `src/test/java/dev/phoenium/engine/EntityHookGateTest.java`, `MixinTargetsTest.java` | |
| Experimental death confinement | branch `experimental/death-confinement` (one commit, 287 lines) | `-Dphoenium.engine.deathConfinement=true`: wraps `die` call sites so deaths caused on a tile run on the server thread after the colour. Its own commit message: it frees listener-only death mods and **no** mixin-hooking mod in the census. Not live-validated. |

### 1.4 DEFAULT mode: ownership runtime

The default (no flags) runtime used by the Phoenix release build. One gameplay worker, `parallel=OFF`.

| System | Where | Notes |
|---|---|---|
| Runtime | `live/PhoeniumLiveRuntime.java`, `live/LiveDiagnostics.java` (`toLogLine`) | Start line `PHOENIUM_STAGE2 started lifecycle=RUNNING scheduler=RUNNING workers=1 ... parallel=OFF`. |
| Scheduler and leases | `scheduler/RegionScheduler.java`, `ownership/OwnershipContext.java`, `region/`, `topology/`, `transfer/`, `task/` | Region leases and owner transactions. Invariants: `docs/THREADING_INVARIANTS.md`. |
| Chunk boundary | `chunk/` (about 105 files), e.g. `chunk/ChunkSynchronousAccessGuard.java` (`unfinishedWait`) | C2ME/chunk-system boundary and provider settlement. |
| P0 fix in the beta | `service/RegionalChunkGameplayService.java` `runSpawnerAttempt`, `mixin/RegionalCustomSpawnerMixin.java` | A custom spawner that reads a still-loading chunk inside an owner transaction ends only that attempt and logs `PHOENIUM_SPAWNER_TERRAIN_NOT_READY`. Test: `RegionalCustomSpawnerBoundaryTest`. |
| Fail-closed stop | `failure/FailureBarrier.java`, `mixin/FailedStopMinecraftServerMixin.java`, `FailedStopChunkMapMixin`, `FailedStopServerChunkCacheMixin`, `FailedStopPersistentEntitySectionManagerMixin`, `FailedStopSavedDataStorageMixin`, `FailedStopDedicatedServerMixin` | After a failure, `stopServer` skips player and world saves (rollback to the last autosave). Default mode only. |
| Save | `save/` (`SaveCoordinator`, `RegionalSaveCoordinator`, `RegionalSaveBinding`, `SaveSnapshot`, `RetainedChunkSnapshot`) | |
| Memory | `mem/NoiseRelease.java`, `mem/MemSwitches.java`, `mixin/MemNoiseReleaseMixin.java`, `mixin/C2meScheduledTaskReleaseMixin.java` | Installed in both modes. MEM-6 (C2ME finished worldgen tasks pinned by a queue) was fixed before the beta. |

Unit test distribution (`@Test` annotations, not counting parameterised expansion): `chunk` 837, `live` 156, `service`
125, `engine` 67, `mixin` 25, `save` 14, others small. The engine (about 7,500 lines) is covered mostly by live gates,
not unit tests; `TileEngine.LevelPhase` has no unit test.

### 1.5 C2ME settlement2 dependency

| What | Where |
|---|---|
| Patches | `docs/patches/c2me-root-settlement.patch`, `docs/patches/c2me-flowsched-settlement.patch` |
| Jar | Release asset `c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar`, SHA-256 `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53` |
| Test fixtures | `scripts/fetch_test_fixtures.py` downloads upstream C2ME from Modrinth and settlement2 from the release, checks hashes, and writes into `build/...` **and into a sibling folder outside the checkout** (`../C2ME-locked-settlement/build/patched/`). |
| Docs | `docs/STAGE7_C2ME_*.md`, `docs/C2ME_*_MILESTONE.md` |

### 1.6 Known crash/consistency boundary (verified in source)

- DEFAULT mode: a failure stops the server **without** saving (`FailedStop*` mixins).
- ENGINE mode: those mixins are not applied, so vanilla `MinecraftServer.stopServer` saves players and chunks after a
  crash. If a tile or a deferred effect throws, `LevelPhase.run`'s `finally` calls `clear()`, which drops the deferred
  effects of every tile that already finished in that phase (DAG: the whole phase; colours: the failing colour). This
  is also true when the gate made the run SERIAL, because SERIAL uses the same deferral with one thread.

### 1.7 Test and evidence tooling

| Tool | Where | Portable? |
|---|---|---|
| Unit suite | `./gradlew build` / `test` | Yes, after `scripts/fetch_test_fixtures.py`. Verified 2026-10-27 on a fresh clone of `main`: 2060 tests, 0 failures, 2 skipped; jar byte-identical to the release on Windows. |
| Client game tests | `runClientGameTest`, `runStage3ClientGameTest` in `build.gradle` | Downloads the client. `runStage3ClientGameTest` status on the beta is unverified. |
| Reference harness | `tools/reference-harness/` (`stage9-runner/run_stage9.py`, `gates/*.py`, `world-checkers/*.py`) | **No.** Hard-coded Windows paths; local server template; Node bot adapters not published yet; some launchers pass only two of the three Mojang host flags. |
| Older stage scripts | `scripts/*.py`, `src/*Harness` source sets | Mostly need the maintainers' evidence trees. |

---

## Part 2: Phoenix (Android hosting app)

Gradle project `android-stage10a/`: `:app` (Kotlin/Compose, Android) and `:core` (plain Java 17, Android-free).
`A/` = `android-stage10a/app/src/main/java/dev/phoenix/server/`, `C/` = `android-stage10a/core/src/main/java/org/phoenix/core/`.
Finding IDs (F1, A2, NET-3, D-4 ...) refer to `docs/review/FINDINGS_BETA1.md` in the Phoenix repo. Line numbers are
from `c327cfa`.

### 2.1 Process model

The app is the only owner. The Minecraft server is a **child process** started through a native launcher
(`libphoenixjvm.so`, source `android-stage10a/runtime/phoenix_jvm.c` + `runtime/parent_lifecycle.h`) that sets
`PR_SET_PDEATHSIG=SIGTERM`, so the server dies when the app process dies. A backend singleton (`PhoenixBackend.get()`
on the application context) owns the process. The foreground service keeps the app process alive. The UI polls.

| System | Where | Purpose / invariant | Tests | Known issues |
|---|---|---|---|---|
| JVM command line | `A/PhoenixBackend.kt` `processBuilder(probe)` (about l.462-505) | Argument list, no shell: launcher, `-Xms128m -Xmx{heap}m`, `-javaagent:phoenix-metrics.jar`, `JvmTuning.flags`, `AotCache.flags`, `PhoeniumTuning.flags`, `-DGeyser.MaxConnectionsPerAddress`, then `org.phoenix.runtime.JarMain <jar> nogui`. | none for `processBuilder` | |
| Heap sizing | `A/Models.kt` `ServerMemory.dynamicMb` (l.239) | 45 % of available memory, clamped to 256-1536 MB; refuses under 650 MB free or `lowMemory`. | `ServerMemoryTest` | |
| Phoenium mode | `A/PhoeniumTuning.kt` `flags()` | "Accelerated" (opt-in, `DEFAULT_ACCELERATION=false`) adds `-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=N -Dphoenium.engine.schedule=dag` only for Fabric with an engine-capable Phoenium jar. | `PhoeniumTuningTest` | Hardware-specific tuning must stay out of Phoenium. |
| JVM tuning / AOT | `A/JvmTuning.kt` (`JvmTuning`, `object AotCache`, `DEFAULT_ENABLED=false`, `experimentFlags` debug-only); `PhoenixBackend.kt` `assembleAotCache`, `finishAotAssembly`, `launchWithModRollback` | AOT ("Faster starts") is off by default after a HotSpot C1 crash. Rollback turns AOT and tuning off if Java dies before "Loading Minecraft". | `JvmTuningTest` | `AotCache.key` ignores `mods/`. A10 (debug experiment file). |
| Runtime install | `ensureRuntime()`; `android-stage10a/runtime/src/main/java/org/phoenix/runtime/RuntimeLayout.java` | Unpacks and hashes the bundled Java 25 runtime. Refuses 16 KB-page devices. | | |
| Output reader | `C/ProcessRuntimeHost.java` (reader loop, catches only `IOException`, l.34); `PhoenixBackend.kt` `consume(line)` (l.1130-1167, regexes l.92-98) | Parses server stdout for pid, startup, metrics, players, seed, backup flush. | none for `consume` | **F1 (high, verified 2026-10-27):** chat such as `Average time per tick: 1.2.3ms` makes `toDouble()` throw on the reader thread; no uncaught handler, so the app dies and PDEATHSIG stops the server. A1, A3, F8 (chat spoofing). |
| Controller | `C/ServerController.java` (`startNow`, `stopNow`, `forceStop`, `adoptNow` l.109, `command` l.237, per-line `queue.execute` l.89) | Single-thread queue. 5 min start timeout; ONLINE needs "Done" plus a status probe. Stop: `stop`, SIGTERM after 60 s, ERROR after 30 s more, never auto-SIGKILL. | `:core:acceptance` (`CoreAcceptance`), `StartWaitTest` | A4 (adopted STOPPING has no timeout), A8 (`minecraft:stop` becomes a crash restart), F12 (unbounded queue). |
| Crash handling | `PhoenixBackend.kt` `afterUnexpectedExit` (about l.867), `ChunkErrors.find`, `ServerCrashes.find`; `A/Reliability.kt` `CrashLoop` | 3 restarts / 15 min, 5 s delay; stays stopped on world damage. | `ReliabilityTest` | |
| Leftovers and reattach | `PhoenixBackend.kt` `rememberRuntime` (l.903), `reconnect()` (l.929-961), `endLeftovers()` (l.533-551), `start()` port bind (l.575); `A/LeftoverServer.kt` (`classify`, `isOurServer`, `scanForServer`); `A/AdoptedProcess.kt`; `C/ProcessRuntimeHost.attach` | Reattach after app death: pid + `/proc` start ticks + launcher and jar path. The authoritative state is the backend singleton, not the Activity; it does need the app process to be relaunched (`START_NOT_STICKY`). | `LeftoverServerTest`, `ReliabilityTest` | A3 (chat can overwrite the saved pid), A5, A6 (port bound before leftover cleanup; 20 s wait then proceed; basename match). No tests for `reconnect`, `AdoptedProcess`, `endLeftovers`. |
| Foreground service | `A/HostingService.kt` | `specialUse`, `START_NOT_STICKY`, partial wake lock + Wi-Fi lock; actions START/STOP/RESTART/FORCE_STOP/PROBE/...; `onDestroy` stops an active runtime. | | |
| UI link | `A/PhoenixViewModel.kt` `service(action)` | `startForegroundService` intents; polls `dashboard()`/`consoleLines()` every 0.5-1 s. | | |
| State | SharedPreferences `phoenix-stage10a` | pid, start ticks, hosting marker, settings. | | NET-11 / E3: device transfer may copy `server.properties` with the management secret. |

### 2.2 Files, worlds, mods

| System | Where | Notes | Tests |
|---|---|---|---|
| World import | `C/world/WorldImporter.java`, `ZipCentralDirectory`, `WorldImportLimits` | Good reference implementation: counts copied bytes, CRC, symlink and path normalization, ratio limits. | `:core:worldAcceptance` (`WorldImporterHarness`) |
| Chunk import | `A/WorldManager.kt` `importChunks` (l.136 trusts declared size, l.143 unbounded copy) | F4, D-7. | |
| Mod checks | `A/ModChecks.kt` `read` (l.36), `collectNested` (l.57: 64 MB per nested jar, no total cap) | F5. | `ModChecksTest` |
| Mod packs | `A/ModManager.kt` `readMeta`; `A/ModPacks.kt` (l.120 unbounded `modrinth.index.json` read outside `runCatching`) | F5: an `OutOfMemoryError` escapes `launchAction` (catches only `Exception`). | `ModJournalTest` |
| Spawn preparation | `A/SpawnPrep*.kt` (`SpawnPrepInstaller`, `SpawnPrepLog` - prefix-anchored parser, a model for fixing F1) | Bundled mod `phoenix-spawnprep` (source on Phoenium branch `experimental/phoenix-spawnprep`). | `SpawnPrep*Test` |
| Backups | `WorldManager.kt` `autoBackup` | No fsync; rotation can evict the last good backup (D-6). | |

### 2.3 Networking

| Surface | Where | Bind | Notes / findings |
|---|---|---|---|
| Pinned downloads | `A/CrossplayInstaller.kt`; `C/provision/Provisioner.java`, `PinnedDistribution`, `DEPENDENCY_MANIFEST.json` | outbound | https only, host allowlist, no redirects, size cap, SHA-256, atomic move. Fabric's own first-run library download is not pinned (D-4). |
| Custom-name front door | `A/NameFrontDoor.kt` (`handle` l.176-199, 16 workers l.224, `soTimeout` 10 s l.177, drain loop l.197) | `0.0.0.0:25591` | F2/NET-3 slowloris, F7/NET-4 unauthenticated name logging. |
| Oracle proxy pairing | `A/ProxyPairingHost.kt` `listen` | all interfaces, 40000-48999 | F3/NET-2: one trickling client holds the 48 h window. |
| Minecraft management API client | `A/Management.kt` `ManagementClient.call`; `PhoenixBackend.kt` `enableManagementApi`, `managementUsable`, `management` | client to 127.0.0.1 | A2/NET-1: bearer secret sent to whoever holds the port; secret never rotated. `readHttpHead` is O(n^2) up to 8 KiB. |
| Playit agent | `A/PlayitAgent.kt` (l.136 `ProcessBuilder` for `libplayitagent.so`), `A/PlayitManager.kt` `PlayitSecrets` | LocalSocket in `filesDir/playit` | Key file deleted only once RUNNING; F10 log rotation. The ARCHITECTURE.md claim of a `libphoenixjvm.so --playit` mode does not match the C source. |
| NetBird | `C/network/netbird/`, `A/NetBird*.kt`, `A/NetBirdStore.kt` (Keystore AES-GCM) | VPN | Device key in plaintext `noBackupFilesDir/netbird-client/config.json` (written by the Go client). NET-10. |
| Status probes | `C/MinecraftStatusProbe.java` | client | Per-read timeouts only (F11). |
| Minecraft server itself | vanilla + Fabric + Geyser/Floodgate/voice | `0.0.0.0` game ports | Vanilla has no connection throttle; behind Playit/NetBird all players share one source address (F9). |

### 2.4 Build facts

- Gradle wrapper 9.1.0, AGP 9.0.1 (built-in Kotlin), Compose compiler 2.3.10, compileSdk/targetSdk 36, minSdk 29, Java 17
  bytecode, `arm64-v8a` only.
- `:core` has **no JUnit tests**; its checks are `main()` harnesses run as `JavaExec` tasks. `:core:test` fails with
  "no tests discovered" under Gradle 9 (brief item BUILD-6).
- `:app` needs the Android SDK (platform 36, build-tools 36.0.0) and the two NetBird AARs from release `v0.5.0-beta.1`.
  342 JVM unit tests run with `:app:testDebugUnitTest` (AGP 9 has no `testReleaseUnitTest`).
- `:core:byopAcceptance` (not in CI) failed on a Windows checkout with CRLF line endings in
  `deploy/oracle-proxy/schema.yaml`: a test artifact, not a product bug.
