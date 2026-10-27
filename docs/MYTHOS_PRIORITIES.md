# Mythos investigation shortlist

Ranked by potential impact. Each item was located in the public source on 2026-10-27. "Verified" means the code path
was read and the stated behaviour follows from it; it does **not** mean a live reproduction exists. Nothing here is a
confirmed user-facing bug until Mythos reproduces it or proves it from source.

Phoenium: https://github.com/BlindingWulf/phoenium (`main`). Phoenix: https://github.com/BlindingWulf/phoenix-android
(`main`). Paths for Phoenium are under `src/main/java/dev/phoenium/`.

---

## P1. Engine mode: a failure inside a phase discards finished tiles' deferred effects, then vanilla saves the world

- **Weakness (verified in source).** `engine/TileEngine.java` `LevelPhase.run` calls `clear()` in `finally`. When a tile
  or a deferred effect throws, `clear()` empties `tile.deferred` for every tile of that phase that already finished (DAG
  schedule: the whole phase; colour schedule: the failing colour). Those deferred lists hold entity adds (drops,
  conversions, XP), section moves and removals, scheduled block ticks, block-entity tickers, POI dirty marks and ticket
  changes. In engine mode the default-mode `FailedStop*` mixins are not applied (`mixin/PhoeniumMixinConfigPlugin.java`
  `ENGINE_RETAINED`), so after the crash vanilla `MinecraftServer.stopServer` saves players and chunks. The same
  deferral runs when the gate chose SERIAL, so SERIAL is affected too.
- **Why it matters.** Vanilla also saves after a crash, but in vanilla every entity ticked before the crash has applied
  all of its effects. Here finished entities keep their direct mutations and lose their deferred ones. Example: a player
  killed by a mob on a tile has their inventory cleared at once, while the death drops are a deferred `addNewEntity`. If
  anything later in that phase throws, the drops are gone and the empty inventory is saved.
- **Entry points.** `TileEngine.LevelPhase.run` (676), `runColours` (707), `runDag` (744), `apply` (825), `clear`
  (839); `EngineWorkerPool.run` (391) and `Job.runOne` (90); `engine/mixin/EngineEntityManagerMixin.java`.
- **How to test.** Add a unit-testable seam (for example extract "run tiles, then apply deferred lists in key order" from
  `LevelPhase`) and test that a failing tile does not lose other finished tiles' effects. Live: a test-only mod entity
  that throws on a chosen tick next to a mob killed in the same phase, engine `workers=5 schedule=dag`; compare drops and
  inventories after restart.
- **Success.** Either (a) before rethrowing, completed tiles' deferred effects are applied in the normal deterministic
  order, or (b) engine mode gets the same fail-closed stop as default mode. Present both options with their trade-off (b
  rolls back to the last autosave). The owner chooses.
- **Unsafe if.** The fix applies effects of tiles that did not finish, swallows the exception, keeps the server running
  after a tile failure, or changes the success path's order.
- **Android hardware:** no.

## P2. Engine mode: claims are enforced for block writes only

- **Weakness (verified in source; impact unproven).** The only claim check is `engine/mixin/EngineLevelChunkMixin.java`
  `setBlockState` (plus load and POI services). Not checked: block reads outside the claim; `Level.getBlockEntity` from a
  tile (`engine/mixin/EngineLevelMixin.java` `phoenium$tileBlockEntity`), which uses `IMMEDIATE` and can create and
  register a block entity; and entity-to-entity effects (`hurtServer`, knockback, `setDeltaMovement`, teleports) on
  entities outside the claim. Same-colour tiles run at once with claims one tile apart (64 blocks with defaults), so an
  effect that reaches farther than the claim races another tile thread.
- **Plausible triggers to test.** Fast entities that leave their claim within one tick (TNT launched by a TNT cannon,
  `/summon` with large `Motion`), mobs with a raised `follow_range` (data packs, `/attribute`), modded long-range
  attacks or AoE abilities.
- **How to test.** First add a diagnostic-only detector behind a flag (count, log once, never change behaviour) for (1)
  entity mutations from a tile on an entity whose chunk is outside the running claim, and (2) block-entity creation
  outside the claim. Run vanilla and L5 workloads plus a TNT-cannon fixture under `workers=5 schedule=dag`.
- **Success.** Measured counts. If non-zero, a minimal fix: move the entity to the serial set for that tick, or make the
  effect a service. Then a regression test.
- **Unsafe if.** It widens the serial set so much that the engine has no benefit, without measurements.
- **Android hardware:** no.

## P3. Gate v2.1 does not judge code run by parallel-for jobs or non-entity callbacks

- **Weakness (verified in source; impact unproven).** `engine/EntityHookGate.java` `isEntityTarget` covers
  `net.minecraft.world.entity.*` and `ServerPlayer`, and `engine/EntityHookScan.java` `EVENTS` lists 8 entity events
  (known limitation L-d). When the gate says PARALLEL, `TileEngine.runParallel` also runs `ServerEntity.sendChanges`
  (`EngineTrackerSends`), spawn-state counting (`EngineSpawnMemo.sliced`) and held packet delivery (`deliverHeld`, one
  thread per connection) on tile threads, and the chunk phase runs random block ticks and natural spawning in tiles.
  Mixins on `ServerEntity`, `NaturalSpawner`, blocks or connection send paths are not judged.
- **Concrete case to check.** A player killed by a mob dies on a tile thread. If 26.2 `ServerPlayer.die` broadcasts the
  death message through `PlayerList.broadcastSystemMessage(Component, Function, boolean)`, Fabric's
  `fabric-message-api-v1` `PlayerListMixin` fires `ServerMessageEvents.ALLOW_GAME_MESSAGE` and `GAME_MESSAGE` there
  (verified in the Fabric API jar), so chat-bridge mods would run on a tile thread. Confirm against the decompiled 26.2
  source (`genSources`).
- **How to test.** A test-only Fabric mod that registers `GAME_MESSAGE`, a `ServerEntity` mixin and a random-tick
  mixin, and records `Thread.currentThread()`. Run engine mode with the gate PARALLEL.
- **Success.** A measured list of off-thread callbacks. Then the smallest sound change: extend the event list, defer the
  broadcast, or mark these paths for confinement. Keep the conservative default.
- **Unsafe if.** It runs more third-party code off-thread, or removes the gate.
- **Android hardware:** no.

## P4. Open evidence: A3 parallel comparison failed once on the exact beta jar

- **Fact.** On 2026-10-27 the maintainers' gate chain on `phoenium-0.2.0-beta.1.jar` passed Step B, A3 serial,
  determinism (1/2/5 workers, 0 differences), protections and transitions r10 (3/3). The A3 **parallel** comparison
  (`tools/reference-harness/gates/validate_a3_9e8.py`, A3 on vs off, `workers=5 schedule=dag`, foreign hooks allowed
  for the probe mod) failed: `('vehicles-after', 'rider', 'velocity', [-0.00424, -0.0784, 0.00164], [-0.00453, -0.0784,
  0.00047])`. Positions matched; only the rider's horizontal velocity differed. RC1 passed the same comparison, and the
  beta's engine code is identical to RC1.
- **Why it matters.** Either the comparison is sensitive to something A3 legitimately changes (so the gate is flaky), or
  there is run-to-run nondeterminism in parallel mode that the 1/2/5-worker determinism gate does not catch.
- **How to test.** Port `a3live9e8.py` + `validate_a3_9e8.py` (the probe mod source is the `a3Harness` source set in
  `build.gradle`) and run each arm several times; vary only one thing at a time.
- **Success.** Root cause stated with evidence, and the gate fixed or the engine fixed.
- **Android hardware:** no.

## P5. Phoenix: server chat can crash the app (F1) and spoof backend state (A1, A3, F8)

- **Weakness (verified in source).** `A/PhoenixBackend.kt` `consume(line)` runs on the reader thread of
  `C/ProcessRuntimeHost.java`, which catches only `IOException`. Unanchored regexes match anywhere in a line, chat
  included: `Average time per tick: ([0-9.]+)ms` with `toDouble()` throws on `1.2.3`; `PHOENIX_RUNTIME_START.*pid=(\d+)`
  overwrites the saved pid (and `toInt()` throws on a huge number); `contains("Saved the game")` releases the backup
  flush latch. No uncaught-exception handler, so the app dies and PDEATHSIG stops the server; after a reconnect the
  same line is re-read from the log tail.
- **How to test.** Extract a pure line classifier into `:core` or `app/src/test`; feed real 26.2 server lines and chat
  variants. Add a reader test where `onLine` throws and draining must continue.
- **Success.** Only lines from the expected logger/thread prefix are parsed; parse failures are contained; tests cover
  all chat spoof cases. `SpawnPrepLog` (prefix-anchored) is the in-repo model.
- **Unsafe if.** It stops recognising genuine server lines (regression in startup progress, players, metrics).
- **Android hardware:** no (optional reconnect smoke test later).

## P6. Phoenix: process ownership gaps (A4, A5, A6)

- **Weakness (verified in source).** `start()` binds the game port before `endLeftovers()` runs; leftover cleanup waits
  20 s and proceeds anyway; `Leftovers.isLeftover` matches by basename; `ServerController.adoptNow` arms timers only for
  STARTING, so an adopted STOPPING server never times out.
- **Why it matters.** Two JVMs on one world, opaque start failures, a server stuck in STOPPING.
- **How to test.** Fake `RuntimeHost` and fake `/proc` data, as `LeftoverServerTest` does.
- **Android hardware:** yes for the final lifecycle check; the logic is desktop-testable.

## P7. Phoenix: archive and listener exhaustion (F2-F5)

- Archive bombs: `A/WorldManager.kt` `importChunks`, `A/ModChecks.kt` `read`/`collectNested`, `A/ModPacks.kt` l.120.
  Reuse `C/world/WorldImporter.java`'s byte counting and limits.
- Slowloris: `A/NameFrontDoor.kt` (16 workers, per-read timeouts only), `A/ProxyPairingHost.kt` (one connection, 48 h
  window). Add a total pre-splice deadline and a bounded drain; measure join latency before and after.
- Management secret to a port squatter: `A/Management.kt` + `PhoenixBackend.kt` `managementUsable` (A2).
- **Android hardware:** no.

## P8. Housekeeping that unblocks everything else

- Phoenium unit fixtures: 36 tests need `scripts/fetch_test_fixtures.py`, which also writes outside the checkout
  (`../C2ME-locked-settlement/`); missing fixtures are errors, not skips.
- Phoenium reference harness is not portable (`tools/reference-harness/README.md`): hard-coded paths, missing Node bot
  adapters, some launchers pass only two of the three Mojang host flags.
- Cross-platform jar reproducibility (Linux jar differs, likely CRLF/LF resources).
- Phoenix `:core:test` has no JUnit tests (Gradle 9 fails "no tests discovered").

## Performance (after correctness)

Only with A/B evidence (>= 3 runs per arm, p50/p95/p99 MSPT with spread, same hardware and scenario):
- Idle tile threads spin 8192 rounds and then park 1 ms in a loop forever (`EngineWorkerPool.Worker.run`,
  `EngineConfig.SPINS`, `IDLE_PARK_US`). Compare with `experimental/tile-idle-backoff` (D44), especially for Phoenix's
  "Accelerated" mode on phones (CPU/thermal cost is a phone measurement).
- Per-effect allocation on hot paths (deferred lambdas, `BlockPos.immutable()`, `EngineProtections.allowMobDecision`
  builds a `UUID` string and a `Scope` per call when A3 is on). Measure with JFR allocation profiling first.
- GC: ~3 % time in G1 pauses, p99 pause ~380 ms with 32 players exploring on 4 GB (accepted beta limitation).
