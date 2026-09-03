# Stage 3 chunk runtime evidence

This is the historical compact evidence record for the RCON-only chunk-
ownership task. The
descriptor-level inventory is in
[`STAGE3_CHUNK_RUNTIME_MAP.md`](STAGE3_CHUNK_RUNTIME_MAP.md). This file records
the final reproducible RCON-only run and the unresolved ownership questions.
For the later real-client ticking/readiness/send path, use
[`STAGE3_CHUNK_READINESS_EVIDENCE.md`](STAGE3_CHUNK_READINESS_EVIDENCE.md);
its results supersede only this report's earlier “not observed” readiness
statements.

Verdict: **reconnaissance complete; R-CHUNK-01 remains PARTIAL**. The run
demonstrates real vanilla generation, disk-backed reload, POI/lighting activity,
holder publication, FULL conversion, and successful future completion. It does
not bind any stage to a Phoenium owner lease.

## 1. Reproduction command

From the `Phoenium` project directory:

```text
./gradlew.bat runStage3ChunkTrace --no-daemon
```

The task prepares an ignored disposable external Fabric server, copies the
production Phoenium jar into `mods`, launches a Fabric Client GameTest process,
and drives the server through local RCON. The client process supplies a bounded
clock and reads each server's trace after a clean shutdown.

The test-only server command is:

```text
/phoenium chunktrace load 32 4
```

It calls the genuine
`ServerLevel.getChunkSource().getChunkFuture(32, 4, ChunkStatus.FULL, true)`.
The added code does not join, replace, or wrap the returned future. The status
command polls until the original `ChunkResult` is successful.

Generated artifacts are under the ignored build directory:

| Artifact | Purpose |
| --- | --- |
| `build/client-gametest/evidence/STAGE3-CHUNK-RUNTIME.json` | Compact machine-readable summary |
| `STAGE3-CHUNK-RUNTIME-generation-1.json` | Raw selected fresh-run events |
| `STAGE3-CHUNK-RUNTIME-generation-2.json` | Raw selected fresh-run events |
| `STAGE3-CHUNK-RUNTIME-persisted-reload.json` | Raw selected reload events |
| `STAGE3-CHUNK-RUNTIME-world-seeds.txt` | World/seed/artifact record |
| `STAGE3-CHUNK-RUNTIME-*-latest.log` | Per-lifecycle server log snapshots |

The trace is dormant unless `phoenium.stage3.chunkTrace=true`, an explicit
target list, and an output path are supplied. Normal server operation does not
emit chunk traces.

## 2. Environment and policy

| Item | Final run |
| --- | --- |
| Minecraft / Java | `26.2` / `25` |
| Fabric Loader / API | `0.19.5` / `0.159.0+26.2` |
| Loom / Gradle | `1.17.20` / `9.5.1` |
| Workers | `1` |
| Parallel gameplay | `OFF` |
| Real dedicated server | **YES** |
| Production Phoenium jar | **YES** |
| Real client TCP connection | **NOT REQUIRED** for this chunk-only scenario |
| Personal account / Microsoft auth | **NO** |
| Launcher / GUI automation | **NO** |

The GameTest process is an automated orchestrator, not a logged-in player. The
disposable server uses `online-mode=false`, local RCON, and the fake identity
`PhoeniumTest0`. Movement, interaction, packet, and client-readiness tests are
not reused by this mode.

Exact artifact identities:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `minecraft-merged-deobf-26.2.jar` | 37,399,549 | `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4` |
| `build/libs/phoenium-0.2.0-stage2.jar` | 204,709 | `A0EBFDF6AADD2D82C4E852181DB3417E78D15E0295EAACD98E49866747C3D51B` |

The mapped Minecraft path is
`C:\Users\Admin\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`.
The production jar scan found zero client-test, client-game-test, or
`net/minecraft/client` entries.

## 3. Target and lifecycle record

| Field | Value |
| --- | --- |
| World / chunk | `minecraft:overworld`, `(32,4)` |
| Requested status | `FULL` |
| Route snapshot | `stage3-fixture-b`, topology `1`, owner epoch `0` |
| Fixture | A `x=-4..4`; B `x=5..32`; both `z=-4..4` |
| Initial target state | absent/unloaded: **YES** |
| Trace generated | `2026-09-03T13:54:56.068429400Z` |

The target is outside the startup/spawn fixture. The wider B span is enabled
only for `phoenium.stage3.chunkTrace`; it is test geometry for route evidence,
not a chunk-owner implementation.

| Run | World | Seed | Events | Parsed data | Future | Clean stop |
| --- | --- | ---: | ---: | --- | --- | --- |
| `fresh-generation-1` | `STAGE3-CHUNK-GEN-1-1788443676413` | `3628321240088420154` | 231 | `false` | success | **YES** |
| `fresh-generation-2` | `STAGE3-CHUNK-GEN-2-1788443683711` | `1247676692653712630` | 231 | `false` | success | **YES** |
| `existing-chunk-reload` | `STAGE3-CHUNK-GEN-1-1788443676413` | `3628321240088420154` | 170 | `true` | success | **YES** |

The first two lifecycles are independent new-world generation. The third
restarts the first evidence world after a normal stop and therefore exercises
the disk-backed parse/read path. This persisted-world exception is isolated
from movement/interaction tests.

## 4. Observed stage map and ordering

The raw traces retain every selected event. The summary's `stageOrder` records
first occurrence of each unique stage; repeated holder statuses remain in
`stageCounts`. The complete descriptor map and full unique orders are in
`STAGE3_CHUNK_RUNTIME_MAP.md` and `STAGE3-CHUNK-RUNTIME.json`.

| Family | Observed final-run evidence |
| --- | --- |
| Request | `CHUNK_FUTURE_REQUEST` on `Server thread`, sequence 1 |
| Generation | `ChunkMap.runGenerationTask` on `Server thread`, then `lambda$runGenerationTask$0` on `Worker-Main-*`; holder/step stages repeat for status progression |
| Disk/parse | `readChunk` and `parseChunk` on `Worker-Main-*`; parse appears only on reload |
| Initial application | `lambda$scheduleChunkLoad$3` on `Server thread`; fresh returns `ProtoChunk`, reload returns `ImposterProtoChunk` |
| POI | `prefetch` on a worker; reload consistency/unpack on `Server thread` inside `SerializableChunkData.read` |
| Lighting | fresh/reload `lightChunk` on `Worker-Main-*`; reload retain/queue calls on `Server thread` |
| FULL | `ChunkStatusTasks.full` at worker boundary; `lambda$full$0` and holder proto replacement on `Server thread` |
| Future | `CHUNK_FUTURE_RETURN` then successful `CHUNK_FUTURE_COMPLETION` |
| Later bookkeeping | `POI_UNPACK_DATA` and `ChunkMap.markPosition` can occur after public future completion on fresh |

Fresh runs had identical semantic order:

```text
request -> accessible -> generation dispatch/worker -> holder/step -> schedule/read/POI prefetch -> application -> empty ProtoChunk -> empty marker -> holder statuses -> lightChunk -> FULL -> LevelChunk conversion/proto replacement/post-load -> FULL holder completion -> public future return/completion -> later POI/map bookkeeping
```

Reload inserted the expected persisted-data branch:

```text
schedule/read -> parse -> application -> SerializableChunkData.read -> POI consistency/unpack -> light retain/queue -> persisted map marker -> application return -> holder statuses -> lightChunk -> FULL conversion -> public future return/completion
```

Required relationships were stable in both fresh runs and the reload:

- request precedes public return and successful completion;
- schedule precedes application;
- holder step has an enter/return pair and precedes initial application;
- application has an enter/return pair;
- FULL conversion has an enter/return pair;
- fresh application reports `parsedDataPresent=false`;
- reload reports `parsedDataPresent=true` and `SerializableChunkData.read` is
  inside the application continuation.

Representative sequence anchors (absolute numbers are not a scheduler
contract):

| Run | Application / first map-cache write | FULL conversion | Future return/completion | Later map marker |
| --- | ---: | ---: | ---: | ---: |
| fresh 1 | 15 / `markPositionReplaceable` 17 | 216 / 221 | 226 / 227 | 230 / 231 |
| fresh 2 | 17 / `markPositionReplaceable` 19 | 216 / 221 | 226 / 227 | 230 / 231 |
| reload | 17 / `markPosition` 82 | 160 / 162 | 167 / 168 | 169 / 170 |

## 5. Thread, identity, and ownership facts

The trace records raw thread name, thread ID, and a conservative name-based
class. `Worker-Main-*` is reported as `background-worker`; the method role can
still be known from the exact symbol (`lambda$runGenerationTask$0` is the
generation worker body). No distinct name matching `generation-worker`,
`lighting-worker`, or `io-worker` was observed.

| Question | Result |
| --- | --- |
| Request observed? | **YES**, correlation `1` in each lifecycle |
| `ChunkPos` available? | **YES**, at public request and load/application seams |
| Holder identity available? | **YES**, at holder and FULL stages; stable within a lifecycle |
| Vanilla request/holder incarnation available? | **NO**; not exposed at observed seams |
| Topology generation / route available? | **YES**, diagnostic snapshot `1` / B |
| Original public future available? | **YES** at `getChunkFuture` return/completion boundary |
| Public future directly available at FULL lambda? | **NO**; only internal continuation inputs are present |
| Exact-once continuation proof? | **UNRESOLVED** for a future ownership implementation; the observer itself invokes nothing |
| Real supersession observed? | **NO** |

The raw event field `requestIncarnation=1` is explicitly tagged
`requestIncarnationSource="trace-assigned correlation; vanilla incarnation not exposed"`.
It is not Minecraft's request-generation fence. `holderIncarnation` is
`NOT_EXPOSED_BY_VANILLA`. Object identities are class-plus-identity-hash
strings and are not retained or cross-process identities.

All core application/FULL events had `ownershipContext=false` and
`regionLease=false`, despite route B. A later fresh POI callback and final map
markers carried an existing context for A; the reload's final marker did too.
Here `regionLease=true` means only that the current thread-local
`OwnershipContext` carried a `RegionLease`; it is not a chunk-specific lease.
The route/context mismatch is evidence that ambient context or thread identity
cannot be treated as regional chunk authority.

## 6. First mutation and remaining footprint

There are two useful “first mutation” answers:

1. The first observed write to live Minecraft map state is
   `ChunkMap.markPositionReplaceable(ChunkPos)` on fresh generation (17/19), or
   `ChunkMap.markPosition` on reload (82). Bytecode shows a write to
   `chunkTypeCache`; this is vanilla bookkeeping, not Phoenium ownership or
   proof of complete chunk installation.
2. The first confirmed usable live chunk/holder footprint is
   `ChunkStatusTasks.lambda$full$0` on `Server thread` (FULL enter 216 fresh,
   160 reload). Its bytecode constructs/reuses `LevelChunk`, replaces the proto,
   sets full status, runs post-load, marks loaded, registers block entities and
   tick containers, and installs the unsaved listener.

The FULL seam is not the whole footprint. Reload observed POI consistency,
pending/data unpack, light retain/queue, and map bookkeeping before application
returned. Holder futures, later future callbacks, ticking/readiness/send paths,
and save/unload paths may have independent effects.

## 7. Future observer, exceptional path, and candidates

`CHUNK_FUTURE_RETURN` is downstream of initial application and FULL conversion:
fresh 226 and reload 167. The existing Phoenium `getChunkFuture` observer is
**DIAGNOSTIC ONLY**; it records the public future and metadata-only counters but
does not control `ChunkMap`, POI, light, holder, FULL, or readiness callbacks.
Vanilla 26.2 itself has `managedBlock`/`CompletableFuture.join` in the
main-thread branch; the added trace introduces no join/get/wait.

**Exceptional load path: NOT OBSERVED.** Failure symbols are mapped and
instrumented, but no unsafe world corruption or injected parse failure was
used. No fallback/recovery claim is made.

**Real supersession: NOT OBSERVED.** No safe natural request/unload race was
reproduced. Deterministic `ChunkCustody` tests remain model evidence only.

| Candidate | Classification |
| --- | --- |
| `ChunkMap.lambda$scheduleChunkLoad$3` | Best initial seam; insufficient alone because request incarnation and POI/light footprint are not carried |
| `SerializableChunkData.read` | Insufficient alone; side effects are early and holder/future admission is absent |
| `GenerationChunkHolder.completeFuture` / `replaceProtoChunk` | Good holder/publication sub-seams; insufficient for earlier/later effects |
| `ChunkStatusTasks.lambda$full$0` | Good FULL-conversion seam; insufficient for load/readiness custody |
| `getChunkFuture` return observer | Too late; diagnostic only |
| Ticking/send/failure callbacks | Unknown or not observed; require separate safe scenarios |

No candidate is approved for implementation by this evidence file.

## 8. Safety and next questions

Instrumentation alters vanilla semantics: **NO**. Future replacement or
authoritative callback wrapping: **NO**. Second worker: **NO**. Parallel
gameplay: **NO**. Fake chunks/AIR/BEDROCK: **NO**. C2ME: **not integrated**.

Final machine-readable flags are:

```text
authoritativeChunkOwnershipImplemented=false
vanillaFutureReplaced=false
futuresJoinedOrWrapped=false
secondWorkerIntroduced=false
parallelGameplay=false
workers=1
rChunk01=PARTIAL
stage3=PARTIAL
stage4=NOT_STARTED
```

Per-lifecycle logs report zero `ownershipViolations`, `unleased`, `fallback`,
`bypass`, `barriers`, and `producerChunkMutations`. Existing
`chunkAdmissions=1` / `chunkApplications=1` are observer counters, not
authoritative installation.

The next implementation task must answer:

1. Where can the vanilla request be bound to actual holder/task incarnation,
   ticket cancellation, downgrade, unload, and shutdown state?
2. What complete map/POI/light/holder/LevelChunk/block-entity/tick/save
   footprint belongs to one custody claim?
3. How can initial application, FULL conversion, and readiness be admitted
   exactly once while preserving the original future/error contract without an
   owner wait?
4. What safe disposable mechanism induces genuine failure recovery, and what
   legitimate scenario reaches ticking/readiness/send paths?

Until then:

`R-CHUNK-01 = PARTIAL`

`Stage 3 = PARTIAL`

`Stage 4 = NOT_STARTED`
