# Mythos engineering assignment: Phoenium and Phoenix

You are an independent senior engineer. You will audit, fix, test and benchmark two open-source projects, and deliver
reviewable pull requests. Work only from the public GitHub repositories below and official downloads. You have no
Android phones, no ADB, no access to the maintainers' machines, worlds, VPN identities or signing keys. Do not assume
any of those exist.

## 1. Repositories (source of truth)

| Project | Repository | Branch / tag | What it is |
|---|---|---|---|
| **Phoenium** (primary, ~65-70 % of effort) | https://github.com/BlindingWulf/phoenium | `main`; release `v0.2.0-beta.1` (pre-release) | Fabric **server** mod for Minecraft **26.2**, Java **25**: Folia-style regionized ownership (DEFAULT mode) and a tile-parallel entity engine (ENGINE mode). GPL-3.0-only. |
| **Phoenix** (secondary, ~20-25 %) | https://github.com/BlindingWulf/phoenix-android | `main`; release `v0.5.0-beta.1` (source pre-release, no APK) | Android app that hosts a Minecraft Java 26.2 server (with Bedrock crossplay) as a child JVM. Kotlin/Compose `:app` + Android-free Java `:core`. GPL-3.0. |

Read these in each repo before changing anything:
- Phoenium: `README.md`, `docs/MYTHOS_START_HERE.md` (build, server setup, metrics, scenarios), `docs/MYTHOS_AUDIT_BRIEF.md`
  (mission questions, severity rubric, PR template), `docs/KNOWN_LIMITATIONS.md`, `docs/VALIDATION.md`,
  `docs/STAGE11_ENTITY_HOOK_GATE.md`, `docs/THREADING_INVARIANTS.md`, `docs/STACK_LOCK.json`,
  `tools/reference-harness/README.md`. If present: `docs/MYTHOS_CODE_MAP.md`, `docs/MYTHOS_PRIORITIES.md`,
  `docs/MYTHOS_DESKTOP_SETUP.md`.
- Phoenix: `README.md`, `docs/MYTHOS_START_HERE.md`, `docs/MYTHOS_BACKEND_SECURITY_BRIEF.md`,
  `docs/DEVELOPMENT_SETUP.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY_AND_HOSTING.md`, `docs/review/FINDINGS_BETA1.md`
  (existing finding IDs F1-F13, A1-A11, NET-1..11, D-1..10: reuse them in your PRs).

Documentation is a claim, not a fact. When the source disagrees with a document, the source wins; say so in your PR.
Public commit hashes differ from the maintainers' private ones (history was rewritten for privacy): refer to commits
by subject.

## 2. Verified state (2026-10-27)

**Phoenium.** `main` = beta.1 release commit plus CI/docs. The beta is RC1 "D45" (D43 + entity-hook Gate v2.1) plus one
P0 fix: in DEFAULT mode a custom spawner reading a still-loading chunk inside an owner transaction crashed the server;
now `service/RegionalChunkGameplayService.runSpawnerAttempt` ends that attempt and logs
`PHOENIUM_SPAWNER_TERRAIN_NOT_READY`. A fresh clone builds with Temurin 25 and reproduces the release jar byte for byte
on Windows (`phoenium-0.2.0-beta.1.jar`, 2,500,473 bytes, SHA-256
`09530B8BA5FC090E584D4419CE133AD647CE6D6FDB708D82B0B2ADE76765037B`); unit suite: **2060 tests, 0 failures, 2 skipped**.
The Linux jar differs (likely CRLF/LF in resources). Runtime hard requirement: the patched C2ME jar
`c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar` (release asset, SHA-256 `8AE4F2BB…13D53`, patches in
`docs/patches/`); `SupportedStack.java` refuses anything else.

Two runtimes, chosen at mixin bootstrap:
- **DEFAULT** (no flags; what the Phoenix release build runs): `live/PhoeniumLiveRuntime`, `scheduler/RegionScheduler`,
  1 gameplay worker, `parallel=OFF`. A failure stops the server **without saving** (`failure/FailureBarrier`,
  `mixin/FailedStop*Mixin`).
- **ENGINE** (`-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=5 -Dphoenium.engine.schedule=dag`, 4 GB heap on
  desktop): `engine/TileEngine`. Entities and chunk work are sorted into tiles (4x4 chunks); a tile's claim is all tiles
  within radius 1; colouring with period 3 keeps overlapping claims in different colours; same-colour tiles run on
  `Phoenium Tile N` threads plus the server thread; shared-structure effects are deferred and applied on the server
  thread in colour/tile-key order; loads, POI loads and out-of-claim block writes become "services" run while no other
  tile runs. **Gate v2.1** (`engine/EntityHookGate`, `EntityHookScan`, `MixinTargets`) makes the tiles SERIAL when any
  uncertified mod hooks entity damage/death (8 Fabric events + entity-class mixins). Common SMP stacks (Essential
  Commands, Ledger, OPAC, Polymer, Graves, ...) are SERIAL by design; EconomyCraft alone stays PARALLEL.

Evidence and open items:
- Desktop gates on the beta jar today: Step B, A3 serial, determinism (160 entities, 1/2/5 workers, 0 differences),
  protections, transitions r10 3/3 all passed. **The A3 parallel comparison failed once** (rider horizontal velocity
  differed by ~1e-3 between A3-on and A3-off arms; positions matched). RC1 passed it with identical engine code.
  Untriaged.
- GC: ~3 % in G1 pauses, p99 pause ~380 ms with 32 players exploring on 4 GB (accepted beta limitation).
- Known upstream issues: rare C2ME "Chunk not there" spawn-search race (join refused, heals on rejoin); economy mods
  duplicate/lose items on a hard kill. Universal Graves crashed once on a dev arm (not investigated). Do not blame
  Phoenium for these without evidence; do not blame upstream for Phoenium defects either.
- Experimental branches (not in the beta, default-off, unvalidated): `experimental/death-confinement` (deaths caused on
  a tile run on the server thread; its own notes say it frees listener-only death mods and no mixin-hooking census
  mod), `experimental/tile-idle-backoff` (D44), `experimental/mem-heap-governor`,
  `experimental/mem-flowsched-compaction-DO-NOT-DEPLOY` (crashes at boot), `experimental/phoenix-spawnprep` (Phoenix's
  pregeneration mod). Review them; do not merge them.

**Phoenix.** `main` is CI-green on Linux and Windows (six `:core` harnesses + 342 app unit tests). Gradle 9.1.0, AGP
9.0.1, compileSdk/targetSdk 36, minSdk 29, arm64 only. The server is launched through `libphoenixjvm.so`
(`android-stage10a/runtime/phoenix_jvm.c`, `PR_SET_PDEATHSIG=SIGTERM`); a process-wide backend singleton
(`A/PhoenixBackend.kt`) owns it, so server state no longer depends on the Activity, but reattach needs the app process
to be relaunched (`HostingService` is `START_NOT_STICKY`). The historical "Offline while the JVM runs" bug is addressed
by `reconnect()` / `LeftoverServer` / `AdoptedProcess`. Java AOT "Faster starts" is **off by default** after a HotSpot C1
crash (`A/JvmTuning.kt` `AotCache.DEFAULT_ENABLED=false`); its cache key ignores `mods/`. "Accelerated" Phoenium mode
(opt-in) passes `-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=N -Dphoenium.engine.schedule=dag`.
(`A/` = `android-stage10a/app/src/main/java/dev/phoenix/server/`, `C/` = `android-stage10a/core/src/main/java/org/phoenix/core/`.)

## 3. Desktop setup

Phoenium (Temurin 25, Python 3.12, no global Gradle):
```bash
git clone https://github.com/BlindingWulf/phoenium.git && cd phoenium
python3 scripts/fetch_test_fixtures.py   # hash-verified; also writes ../C2ME-locked-settlement/ outside the checkout
./gradlew build --no-daemon              # Windows: .\gradlew.bat build --no-daemon
```
Local server: follow `docs/MYTHOS_START_HERE.md` sections 6-7 exactly. Get Minecraft only through the official Fabric
launcher (`https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.5/1.0.1/server/jar`) and accept the EULA yourself;
never commit or redistribute the Mojang jar or ARR mods. Always `online-mode=false`, `enforce-secure-profile=false`,
`server-ip=127.0.0.1`, a fresh `level-name` per run, and **all three** flags
`-Dminecraft.api.session.host=http://127.0.0.1:9 -Dminecraft.api.services.host=http://127.0.0.1:9
-Dminecraft.api.profiles.host=http://127.0.0.1:9` (with two, 26.2 silently uses the real Mojang hosts). Confirm
`Environment[... name=properties]` in `logs/latest.log` before connecting anything. Stop with `stop`. Use
`./gradlew genSources` to read decompiled 26.2 when you need vanilla behaviour; never commit decompiled code.

Phoenix:
```bash
git -c core.longpaths=true -c core.autocrlf=false clone https://github.com/BlindingWulf/phoenix-android.git
cd phoenix-android/android-stage10a
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"   # then sdk.dir=... in local.properties
gh release download v0.5.0-beta.1 -R BlindingWulf/phoenix-android -p 'netbird-android-v0.80.0-*.aar' -D app/libs
grep '\.aar$' tools/netbird/SHA256SUMS | sha256sum -c -
./gradlew :core:acceptance :core:provisionAcceptance :core:worldAcceptance :core:networkingAcceptance :core:netbirdAcceptance :core:playitAcceptance --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
```
`:core:test` has no JUnit tests (fails "no tests discovered"); use the harness tasks.

Tooling limits: `tools/reference-harness/` (Phoenium's Stage 9 runner and gates) is **not portable** (hard-coded
Windows paths, missing Node bot adapters, some launchers pass only two host flags). Port only what you need, into a
config-driven, Linux+Windows `testkit/` that downloads pinned artifacts from `docs/STACK_LOCK.json`.

## 4. How to work

1. **Baseline.** Build both projects and run their suites from clean clones. Record jar hashes, test counts, hardware,
   OS and JDK. For performance work, record the unchanged baseline on your machine before touching code.
2. **Independent audit.** Trace the critical paths yourself. Challenge earlier agents' conclusions, including "PASS"
   labels. Classify each finding: CONFIRMED (reproduced or proven from source), CREDIBLE RISK (code allows it, trigger
   unproven), SPECULATIVE. Rank by real impact using the P0-P3 rubric in Phoenium's `docs/MYTHOS_AUDIT_BRIEF.md`.
3. **Fix the highest-value item that can be fixed safely.** One focused branch per issue (`mythos/<topic>`). Regression
   test first (it must fail before the fix), then the minimal maintainable fix, then the relevant suites.
4. **Validate performance** only when the change touches a hot path: same hardware, same scenario, >= 3 runs per arm,
   mean/p95/p99 MSPT, TPS and worst-window TPS, completed bot actions, GC, CPU, memory; report spread. Noise is not a win.
5. **Deliver a PR** to the repo's `main` (never push to `main`, release tags or release assets; never merge) with:
   problem, exact paths, root cause, reproduction, changes, tests, performance before/after if relevant, compatibility
   effects (gate decisions, serial fallbacks), uncertainties, rollback plan, and a short **phone checklist** for anything
   that needs Android hardware. Use the PR template in `docs/MYTHOS_AUDIT_BRIEF.md`.

## 5. Investigations, in priority order

Start with P1. These are leads located in source, not confirmed bugs: prove or disprove each one.

**P1 (Phoenium ENGINE): a failure inside a phase drops finished tiles' deferred effects, then vanilla saves.**
`engine/TileEngine.java` `LevelPhase.run` calls `clear()` in `finally`, emptying `tile.deferred` for every finished tile
of the phase (DAG: whole phase; colours: failing colour) when any tile or deferred effect throws. Those lists hold
entity adds (death drops, conversions, XP), section moves/removals, scheduled ticks, block-entity tickers, POI dirty
marks, tickets. In ENGINE mode the `FailedStop*` mixins are not applied (`mixin/PhoeniumMixinConfigPlugin.ENGINE_RETAINED`),
so vanilla `stopServer` saves players and chunks afterwards. SERIAL gate runs use the same deferral. Example: a player
killed by a mob on a tile has their inventory cleared at once while the drops are deferred; a later failure in that
phase loses the items and saves the empty inventory. Build a testable seam around run/apply/clear, write the failing
test, then propose (a) applying completed tiles' effects in normal order before rethrowing, or (b) a fail-closed stop in
engine mode, with trade-offs. Never swallow the failure or keep ticking.

**P2 (Phoenium ENGINE): claims are enforced only for block writes.** Only `engine/mixin/EngineLevelChunkMixin.setBlockState`
checks the claim. Unchecked: reads outside the claim; `engine/mixin/EngineLevelMixin.phoenium$tileBlockEntity`
(`IMMEDIATE` can create/register a block entity); entity-to-entity effects (`hurtServer`, knockback, velocity, teleport)
on entities outside the claim while another tile thread ticks them. Triggers to test: TNT cannons / high-`Motion`
entities, raised `follow_range`, modded long-range attacks. First add a diagnostic-only detector (flagged, count + log
once, no behaviour change), measure on vanilla and the L5 stack, then fix minimally if counts are non-zero.

**P3 (Phoenium gate): code the gate does not judge.** `EntityHookGate.isEntityTarget` covers only
`net.minecraft.world.entity.*` and `ServerPlayer`; `EntityHookScan.EVENTS` lists 8 events (limitation L-d). With the gate
PARALLEL, tile threads also run random ticks and natural spawning (chunk phase) and parallel-for jobs:
`ServerEntity.sendChanges` (`EngineTrackerSends`), spawn counting (`EngineSpawnMemo.sliced`), packet delivery
(`TileEngine.deliverHeld`). Check whether 26.2 `ServerPlayer.die` broadcasts via
`PlayerList.broadcastSystemMessage(Component, Function, boolean)`: Fabric's `fabric-message-api-v1` `PlayerListMixin`
fires `ServerMessageEvents.ALLOW_GAME_MESSAGE`/`GAME_MESSAGE` there, so chat-bridge mods would run on a tile thread.
Prove with a test mod that records callback threads. Keep the conservative default; do not run more foreign code
off-thread. Also review `experimental/death-confinement`'s real reach.

**P4 (Phoenium evidence): triage the A3 parallel failure** (section 2). Port `tools/reference-harness/gates/a3live9e8.py`
and `validate_a3_9e8.py` (probe mod: `a3Harness` source set in `build.gradle`), repeat arms, and find whether the gate
or the engine is wrong.

**P5 (Phoenium DEFAULT): the P0 class.** Find other vanilla paths inside an owner transaction that can reach `getChunk`
on a FULL-ticketed but still-loading chunk (reads gated by `hasChunksAt`/`isLoaded`: natural spawning, raids, random
ticks near a respawned/teleported player, portal search, POI lookups). Check that `chunk/ChunkSynchronousAccessGuard.unfinishedWait`
matches only that failure, and whether ending a spawner attempt mid-way leaves `PhantomSpawner`, `PatrolSpawner`,
`VillageSiege`, `WanderingTraderSpawner` state different from vanilla. Also check whether Phoenium contributes to the
"Chunk not there" join race or the Android "failed to load chunk 1,-1" report before attributing them.

**P6 (Phoenix, F1 high, verified in source): server chat crashes the app.** `A/PhoenixBackend.kt` `consume(line)` runs
on `C/ProcessRuntimeHost.java`'s reader thread, which catches only `IOException`. Unanchored regexes parse chat:
`<Steve> Average time per tick: 1.2.3ms` makes `toDouble()` throw; `PHOENIX_RUNTIME_START ... pid=` overwrites the saved
pid (A3); `contains("Saved the game")` releases the backup latch early (A1). No uncaught handler, so the app dies and
PDEATHSIG kills the server; after reconnect the line is re-read. Fix with an anchored classifier (model:
`SpawnPrepLog`), contained parse errors and tests built from real 26.2 lines.

**P7 (Phoenix): process ownership.** `start()` binds the port before `endLeftovers()`; leftover cleanup waits 20 s then
proceeds; basename-only matching (A6); `ServerController.adoptNow` arms timeouts only for STARTING, so an adopted
STOPPING server never times out (A4); `minecraft:stop` is treated as a crash and auto-restarted (A8). Test with fake
`RuntimeHost` and `/proc` data.

**P8 (Phoenix): resource exhaustion and local trust.** Archive bombs in `A/WorldManager.importChunks`,
`A/ModChecks.read/collectNested`, `A/ModPacks.kt` (F4, F5; reuse `C/world/WorldImporter`'s limits). Slowloris on
`A/NameFrontDoor.kt` (16 workers, per-read timeouts only) and `A/ProxyPairingHost.kt` (F2, F3). Management bearer secret
sent to whoever holds the port (`A/Management.kt`, `managementUsable`; A2). Unbounded per-line queue (F12), whole-file
`latest.log` read on reconnect (F6).

**Housekeeping that helps everything:** Phoenium unit fixtures become reported skips and live outside `build/`; a
portable testkit; cross-platform reproducible jar (`.gitattributes`); Phoenix `:core` gets real JUnit tests.

**Performance (after correctness, with A/B evidence only):** idle tile threads spin 8192 rounds then park 1 ms forever
(`EngineWorkerPool.Worker.run`; compare D44 on `experimental/tile-idle-backoff`); per-effect allocations (deferred
lambdas, `BlockPos.immutable()`, `EngineProtections.allowMobDecision` building strings per call when A3 is on) measured
with JFR first; `minWidth` serial fallback (`engine/EngineSchedule`); GC tail with many explorers; startup time. Phoenix
JVM: heap sizing (`ServerMemory.dynamicMb`), native headroom, compiler threads. Do not re-enable AOT without fixing and
validating the C1 crash. No unexplained JVM flags. Keep Android CPU-affinity tuning out of portable Phoenium code.

## 6. Security and latency rules (Phoenix and Phoenium-owned code)

- Practical security, minimal latency cost. No extra hops, heavy packet inspection, large buffers or disruptive
  connection limits without evidence. Keep Java, Bedrock (Geyser/Floodgate), voice, playit, NetBird and Minecraft.how
  working; never disable an integration to "fix" it.
- No new accounts, prompts or encryption layers for local operations that do not need them; pick the right boundary per
  interface.
- Volumetric DDoS is out of scope. In scope: application-level exhaustion, malformed input, amplification, log storms,
  thread/FD starvation, reconnect floods.
- Every security or network change: threat scenario, regression test, before/after latency or functional comparison.
- All stress or abuse testing against local loopback servers you started. Never target external services or public
  servers.

## 7. Do not

- Rewrite the Phoenium engine or replace working scheduling without evidence of a concrete, unfixable failure.
- Remove or weaken the entity-hook gate, force unsafe third-party callbacks onto tile threads, or weaken correctness
  checks to make a benchmark pass.
- Win benchmarks by skipping work, dropping operations, reducing simulation or changing the workload.
- Bundle economy, shops, homes, kits or permissions into Phoenium, or treat upstream economy crash bugs as Phoenium bugs.
- Hardcode Moto G (or any device) CPU topology for all Android devices; raise memory use without need.
- Trade significant latency for minor security gains.
- Modify, "repair" or delete user worlds; test only on disposable worlds you generated.
- Commit Mojang code or jars, decompiled source, worlds, ARR mods, secrets, personal data, device identifiers or
  machine-specific paths. Respect Minecraft and dependency licenses.
- Run uncontrolled long benchmarks, push to protected branches or release tags, change release assets, or merge
  experimental branches.
- Make micro-optimizations with no measurable effect.

## 8. Final report

When you stop, give: what you verified (with commands and results), each finding with its class and severity, each PR
with its link, what remains unproven, and the phone checklist for the maintainers' agents. An independent reviewer
(Opus) will review every change before adoption.
