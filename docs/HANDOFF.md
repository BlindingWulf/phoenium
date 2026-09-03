# Handoff

## Resume point

Stage 0 remains accepted at **PHOENIUM_P0_SOURCE_TRUTH_LOCKED** and Stage 1 at
**PHOENIUM_P1_SERIAL_REGION_CORE_PASS**. Stage 2 is accepted at
**PHOENIUM_P2_FABRIC_SERIAL_LIVE_PASS**. Resume with **Stage 3 ownership boundaries:
entity/player/packet/chunk-completion integration** in [ROADMAP.md](ROADMAP.md). Do
not enable parallel workers, begin broad geographical regionalization, add C2ME, or
claim required-stack compatibility from this serial live evidence.

### Latest resume point — Stage 3 architecture review

Read [STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md](STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md)
and then [STAGE3_CHUNK_APPLICATION_REVIEW.md](STAGE3_CHUNK_APPLICATION_REVIEW.md).
The movement decision is **TOPOLOGY ENCLOSURE with mandatory escape prevention**:
ordinary movement stays entirely under its admitted owner; coordinate movement within
that owner is bookkeeping. Actual ownership changes require a quiescent topology
publication or explicit relocation protocol. There is no proven universal local radius
for arbitrary vanilla/Fabric movement, and the existing core does not enforce geographic
closure. Do not introduce a mid-`Entity.move` owner change.

The review also corrects the current packet-target pre-transfer interpretation and records
registry, topology-admission and pre-existing-inbox publication gaps. The next narrow change
is coherent metadata publication: same-owner routing updates, recorded owner/incarnation,
eager object transitions, closed admission until state is ready, and execution-time stale
work validation. Do not apply a lazy RegionId-only fix that misses A -> B -> A transitions.

The first parsed-chunk application seam is `ChunkMap.scheduleChunkLoad`'s second
`thenApplyAsync`, invoking `lambda$scheduleChunkLoad$3` before `SerializableChunkData.read`.
The exceptional load path, holder `handle` callback, independent POI unpacking and FULL
conversion also require ownership. A single executor wrapper is not a complete chunk
installation boundary. `getChunkFuture` remains **diagnostic only**; R-CHUNK-01 is PARTIAL.

This review changes documentation only. `clean build` executed all **25 tests with zero
failures/errors/skips**; the subsequent `test` command passed using those up-to-date results.
Stage 1/P2 regressions pass; dedicated-server/client gates were not rerun. Historical live
evidence is retained without promotion. Stage 3 remains PARTIAL, no P3 tag is created,
parallel gameplay is off, and Stage 4/compatibility work has not started.

### 2026-09-03 Stage 3 deterministic correction (current resume point)

The deterministic ownership-publication and topology-enclosure correction is complete
and green. The required closure API is
`requiredClosure(activeSeeds, radius) subset-of regionAuthority`: active seeds and
authority are separate concepts, so a larger legitimate authority remains valid. The
deterministic D03 witnesses cover exact minimum authority, larger authority, a missing
required key, complete and incomplete multi-seed unions, cross-world substitution, and
radius zero.

The Stage 3 movement suite adds the requested deterministic M-01 through M-06 and a
publication-window atomicity probe. It proves same-owner routing changes preserve the
object epoch; full synthetic movement footprints fail before foreign lookup/store;
conflicting closures become pending convergence without expanding active lease domains;
real A -> B ownership changes increment exactly once without ABA reuse; stale old-inbox
work rejects or reroutes under current identity; and huge-delta, no-physics, oversized
box, step-height, projectile, repeated-move, and callback escapes trip the hard barrier
before foreign mutation. The probe covers the window after `directory.publish(next)`
and before reconciliation: stale A and premature B operations have no object authority,
and the directory has one route owner only.

`RegionDirectory` is authoritative; its explicit-topology worlds fail closed for missing
explicit routes rather than inheriting a Stage 2 world default. The object ledger is
derived and revalidated immediately before mutation. The scheduler remains generic
behind closed typed payload, gateway, and executor seams. `getChunkFuture` remains
**DIAGNOSTIC ONLY**. `ChunkCustody` deterministically models request incarnation and
exactly-once stage claims, but this does not claim control of live vanilla chunk
installation or its recovery paths.

Verification: `./gradlew.bat clean build --no-daemon` passed with **57 tests, 0
failures, 0 errors, 0 skips** (`CoreAcceptanceTest` 26, `Stage3OwnershipTest` 17,
`Stage3MovementTest` 14). This includes the prior Stage 1 and Stage 2 regressions,
D03-A through D03-F, the explicit-route fallback regression, M-01 through M-06, the
publication-window probe, and existing chunk custody/request-incarnation tests.

**Remaining live Stage 3 gates:** no real client, packet handler, live geographic
crossing, live non-player/projectile movement, or live chunk-installation path was run.
Do not create a P3 tag or enable parallel gameplay from deterministic evidence. The exact
next step is to design and review one scoped serial live topology-enclosure admission
gate with its real Minecraft footprint/readiness boundary; only after that review may a
separate task run a controlled live acceptance harness. Stage 3 remains **PARTIAL**;
simulation workers = 1, parallel gameplay = OFF, and Stage 4 has not started.

## Locked decisions

- Treat current Folia 26.2 semantics as primary; archived Ruthenium is a defect and regression reference.
- Use Minecraft 26.2, Java 25, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20, Gradle 9.5.1, official Mojang mappings, and a server-only mod id phoenium.
- Use a serial-first Phoenium scheduler. Parallel workers come only after deterministic core and live serial integration evidence.
- Model owner epoch, topology generation, and region lease separately.
- Never use a broad per-world gameplay lock, peer-region lock ordering, fake chunks/block values, unowned shutdown task execution, or a silent vanilla fallback.
- Choose GPL-3.0-only before any source-derived implementation. No third-party implementation entered this repository in Stage 0.
- The official required deployment stack, full-PASS production gate, compatibility hierarchy, fork prerequisites, fork phases, and generic-layer limits are locked in [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md). Every target remains `NOT_TESTED`; boot alone is never proof.

## Stage 1 completion evidence

### Toolchain and commands

| Layer | Locked value |
| --- | --- |
| Minecraft / Java | 26.2 / 25 |
| Fabric Loader / API | 0.19.5 / 0.159.0+26.2 |
| Fabric Loom / Gradle wrapper | 1.17.20 / 9.5.1 |
| License | GPL-3.0-only |

Run from the project root:

```text
./gradlew.bat clean build --no-daemon
./gradlew.bat test --no-daemon
```

The clean build passed with **18 tests, 0 failures, 0 errors**. C-01 through C-08 all
pass. Applicable regressions R-LEASE-01, R-LOCK-01, R-LOCK-02, R-OWNER-01,
R-OWNER-02, and R-FALLBACK-01 pass. R-CHUNK-01 is deferred precisely because Stage 1
contains no missing-data/chunk abstraction, thus no fabricated semantic state path.

Artifact inspection passed: the regular JAR contains server-only Fabric metadata,
Phoenium classes, and GPL text; no reference source tree or test class is packaged.
Dedicated-server skeleton boot is **NOT RUN** (Stage 1 has no live integration).

### Core structure and contracts

Read [STAGE1_CORE.md](STAGE1_CORE.md) first, then inspect `RegionScheduler`,
`OwnerGateway`, `TopologyCoordinator`, `RegionRuntime`, and `CoreAcceptanceTest`.

| Package | Responsibility |
| --- | --- |
| `core`, `region` | Value identities and validated region lifecycle. |
| `ownership` | Thread-local context, epochs, and exactly-one active region lease. |
| `topology` | Immutable generation directory, planner, metadata-only transition gate. |
| `task`, `scheduler` | Typed envelopes, owner gateway, deterministic one-worker execution. |
| `failure`, `diagnostics` | Hard barrier and owner-wait guard. |
| `transfer`, `save` | Generic transfer/passenger and immutable save-cut models. |

The core execution path is typed envelope -> immutable directory resolution -> gateway
-> owner inbox -> serial scheduler -> lease/context -> typed payload. Thread identity
never grants authority. Lifecycle and lease are separate. The gateway reroutes, defers,
or rejects stale work; it never runs it at a stale owner. A topology transition uses
only `metadataGate`, waits by later scheduler advancement rather than blocking, and
commits only after leases drain. There are no peer-region locks, broad world locks,
raw queue shutdown executors, blocking owner waits, or vanilla fallback paths.

Transfer state is REQUESTED -> SOURCE_PREPARED -> DESTINATION_RESERVED -> COMMITTED
-> DESTINATION_APPLIED -> COMPLETED, with pre-commit rollback and post-commit barrier
semantics. Passenger groups are complete reachable root graphs. Save cuts are immutable
and generated by owners under leases.

### Build correction

The locked 26.2 artifact is already official-named/non-obfuscated to Loom 1.17.20.
`officialMojangMappings()` and Loom `modImplementation` remap configurations were
reproduced to fail in that environment. The narrow build correction uses ordinary
`implementation` dependencies, no mapping layer, and no Yarn/intermediary dependency.
See [STAGE1_BUILD_COMPATIBILITY.md](STAGE1_BUILD_COMPATIBILITY.md).

### Deferred Stage 2 work

No live Minecraft tick hook, Mixins, world/chunk/entity object, packet route, C2ME
adapter, redstone path, real serialization, dedicated boot, or multi-worker execution
exists. Stage 2 must identify and prove the smallest dedicated-server lifecycle hook
and preserve this gateway/lease/directory path unchanged. No Stage 0 decision changed.

All implementation is independent; no upstream source was copied or adapted. See
[SOURCE_PROVENANCE.md](SOURCE_PROVENANCE.md).

## Stage 3 in-progress handoff

The coherent partial Stage 3 implementation was preserved before final live-boundary work as
`bf9717de8afa6156de64032693dffcaf9c3be827` (`feat: add stage 3 live ownership bindings`).
This is explicitly a rollback point, not a Stage 3 PASS checkpoint and not a P3 tag.

The tree now contains a buildable deterministic Stage 3 ownership core. Read
`STAGE3_OWNERSHIP_BINDING.md`, `STAGE3_PACKET_INGRESS.md`, and `STAGE3_C2ME_BOUNDARY.md`
after the Stage 2 documents. `ObjectOwnershipRegistry` uses UUID/string identities and a separate
object epoch; it is derived from and revalidated against `RegionDirectory`. `ObjectOwnerGateway`
reroutes/rejects stale entity/player envelopes, and `ChunkCompletionGateway` only accepts
immutable request metadata from an asynchronous producer.

The Stage 2 redirect is unchanged. `PhoeniumLiveRuntime` now reconciles actual entities and
players after the vanilla level tick under the existing controlled lease, with an explicit
whole-world default route for this serial stage. Do not treat that default as a geographically
regionalized topology or as packet-hook completion.

Stage 3 now has exact 26.2 re-entrancy-safe Mixin admissions for `ServerboundMovePlayerPacket`
and `ServerboundInteractPacket`, and an observer boundary at base
`ServerChunkCache.getChunkFuture`. The final normal server marker is `1266 = 1266 = 1266` with
all ownership/fallback diagnostics zero. The final injected-failure marker is `1293 intercepted,
10 dispatched, 9 executed, 1 barrier`, with no fallback or bypass and clean RCON shutdown.

Next work remains live acceptance evidence: drive a real client/bot through movement and entity
interaction, create a configured live multi-region player boundary crossing with recorded epochs,
and force a base chunk future across a topology change. Post-`ServerLevel.tick` non-player
reconciliation is explicitly discovery only; it is not a future parallel entity transfer boundary.
Stage 3 is not an accepted checkpoint yet.

### 2026-09-02 Stage 3 architectural escalation (historical)

The later architecture review linked at the top resolves the movement design choice and
refines the chunk trace. The following preserves the evidence that motivated that review.

An exact bytecode review of Minecraft 26.2 established that ordinary non-player
`Entity.move(MoverType, Vec3)` resolves its movement before `setPos(Vec3)`, but then continues
in the same synchronous method with collision, block, fall-damage, movement-emission, and
velocity mutations. Establishing B ownership before `setPos` would leave that tail mutating a
B-owned entity under A; deferring/replaying it would split synchronous vanilla semantics or
require broad caller interception. The requested real non-player crossing gate is therefore
blocked pending an architecture that owns the complete mutation transaction. See
`STAGE3_OWNERSHIP_BINDING.md` for the exact call graph and candidate analysis.

The corresponding base chunk trace found a main-thread application continuation after background
chunk parsing, rather than an irreducible producer-thread Phoenium-mutation boundary. However,
the current return observer is downstream of that application binding, so `R-CHUNK-01` remains
PARTIAL until a controlled application seam and real request/completion evidence exist. See
`STAGE3_C2ME_BOUNDARY.md`. Do not create the P3 PASS tag while this escalation remains open.

## Historical Stage 0 first work session checklist

1. Read [ARCHITECTURE.md](ARCHITECTURE.md), [THREADING_INVARIANTS.md](THREADING_INVARIANTS.md), and [SOURCE_PROVENANCE.md](SOURCE_PROVENANCE.md).
2. Start from a current Fabric 26.2 template; do not resurrect Ruthenium's old Gradle, Yarn, client, or mixin setup.
3. Add the full GPL-3.0-only license before any source-derived file.
4. Create core-only tests for region lifecycle, lease pairing, stale-route handling, transition mailboxes, transfer records, save cuts, and hard failure barrier.
5. Record actual Build, Unit, Integration, Dedicated Boot, violations/fallbacks, compatibility, changed files, risks, architecture changes, blockers, git status, and commit in the next stage report.

## Known risks and blockers

| Area | Current position | Required resolution |
| --- | --- | --- |
| Live server interception | No target approved or implemented. | Bytecode review and exactly-once dedicated-server test in Stage 2. |
| Required deployment stack | No target has execution evidence; C2ME is the highest-priority external integration and Krypton is tested last in isolation. | Post-core, feature-complete required-stack audit under the compatibility hierarchy; use adapter/patch/fork only when proven necessary. |
| All tick categories | No Phoenium category is implemented. | Stage progression through entity, chunks, scheduled ticks, block events, redstone, trackers, save. |
| Parallelism | Not enabled or measured. | Deterministic core then serial live oracle before multiple workers. |
| Secondary/future mods | VMP, Carpet, Servux, WorldEdit, LuckPerms, and BlueMap are not production dependencies. | Consider only after official required-stack gates. |
| Upstream licensing | Ruthenium metadata conflicts with root GPL license. | Avoid direct reuse unless upstream clarifies; retain provenance review. |

## Stage 2 completion evidence

### Exact commands and environment

From the Phoenium root, run:

```text
./gradlew.bat clean build --no-daemon
./gradlew.bat test --no-daemon
```

The finalized suite passed with **19 tests, 0 failures**. The actual dedicated-server
environment was Minecraft 26.2, Temurin Java 25.0.4.1, Fabric Loader 0.19.5,
`fabric-api-base` 2.0.4+ece063239e, and `fabric-lifecycle-events-v1`
4.1.4+29b6eb019e. The disposable server directory is `.stage2-server-live1` (ignored
from Git); its logs are `logs/latest.log`. The regular Phoenium artifact is
`build/libs/phoenium-0.2.0-stage2.jar`.

The coherent Stage 2 implementation commit is `bdac4fb` (`feat: integrate Stage 2
Fabric serial live ticking`). Resolve the immutable
`PHOENIUM_P2_FABRIC_SERIAL_LIVE_PASS` tag for the final checkpoint tree.

Launch from that disposable server directory:

```text
java -Xmx1G -jar fabric-server-launch.jar nogui
```

Wait for `PHOENIUM_STAGE2 started`, allow a bounded tick observation, then use local
console `/stop` (or the disposable server's local RCON stop command). See
[STAGE2_SERVER_EVIDENCE.md](STAGE2_SERVER_EVIDENCE.md) for the exact installed module
set and expected counters.

### Accepted live binding and behavior

The only Stage 2 live hook is a required redirect of the one direct
`ServerLevel.tick(BooleanSupplier)` invocation in
`MinecraftServer.tickChildren(BooleanSupplier)`. The binding is fully recorded in
[STAGE2_LIVE_BINDING.md](STAGE2_LIVE_BINDING.md). The redirect submits typed
`LiveLevelTick(worldKey)` work through P1 `OwnerGateway`, then the existing
`RegionScheduler`, `RegionLease`, and `OwnershipContext` execute the original level
operation. The live envelope never carries a Minecraft world object.

Each loaded `ServerLevel` at startup is one temporary real Phoenium region with ID
`stage2-live-world`; this is not chunk/entity regionalization. The serial execution
lane remains on Minecraft's server thread to preserve vanilla thread affinity, but it
receives no authority simply from that identity: only scheduler-installed lease/context
permits the operation. `simulationWorkers=1` means this single serial execution lane;
parallel gameplay is OFF and no Phoenium background worker exists.

Lifecycle starts from `SERVER_STARTED`, shuts down from `SERVER_STOPPING`, and runs
P1 `RUNNING -> QUIESCING -> STOPPING`; a failed execution changes the scheduler to
`FAILED` and later redirected calls are consumed with no vanilla fallback.

### Measured results

| Run | Intercepted | Dispatched | Executed | Duplicate | Bypass | Fallback | Barrier |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Normal A | 180 | 180 | 180 | 0 | 0 | 0 | 0 |
| Normal B | 177 | 177 | 177 | 0 | 0 | 0 | 0 |
| Extended normal | 1173 | 1173 | 1173 | 0 | 0 | 0 | 0 |
| Injected failure at dispatch 10 | 1131 | 10 | 9 | 0 | 0 | 0 | 1 |

Both normal runs reached ready state and cleanly exited through actual RCON `/stop`;
there were no active leases, ownership violations, unleased execution, fallback, or
Phoenium thread leaks at stop. The failure run held its barrier and also stopped cleanly
on request. R-LIVE-TICK-01, R-LIVE-OWNER-01, R-LIVE-FALLBACK-01, and
R-FABRIC-SERVER-01 are recorded as PASS in [TEST_MATRIX.md](TEST_MATRIX.md).

### Known limits and Stage 3 entry conditions

Stage 2 does **not** regionalize entity/player/AI/vehicle behavior, block entities,
random or scheduled ticks, block events, redstone, game events, entity tracking,
packets, chunks, saves, or cross-world / cross-region work. Runtime-added levels
fail closed until their lifecycle is explicitly owned. No C2ME, compatibility adapter,
compatibility fork, or required-stack target was tested; all remain `NOT_TESTED`.

Read [STAGE2_LIVE_BINDING.md](STAGE2_LIVE_BINDING.md), then `PhoeniumLiveRuntime`,
`MinecraftServerMixin`, `RegionScheduler`, and the Stage 3 roadmap before extending
ownership. Stage 3 must retain this serial binding as its oracle and add named owner
boundaries before introducing a new gameplay category.

## Stage 0 report status

| Required item | Stage 0 status |
| --- | --- |
| Build | Not run; documentation-only stage. |
| Unit tests | Not run; no Phoenium tests exist. |
| Integration | Not run. |
| Dedicated boot | Not run. |
| Violations/fallbacks | No Phoenium runtime. Ruthenium fallback is captured as a prohibited regression. |
| Compatibility tested | None. |
| Changed files | Phoenium documentation and repository metadata only. |
| Architecture | Locked by Stage 0 documents. |
| Next stage | Stage 1 isolated core model. |

## Git record

The original Stage 0 checkpoint remains **PHOENIUM_P0_SOURCE_TRUTH_LOCKED**. The compatibility/fork-policy amendment is committed on top of it as `docs: lock required compatibility and fork policy`. The amendment commit is intentionally identified by the repository's current `HEAD` at delivery rather than embedding its own hash in this tracked file, which would become stale or self-referential. Its SHA and clean-status evidence are reported with the amendment delivery.
