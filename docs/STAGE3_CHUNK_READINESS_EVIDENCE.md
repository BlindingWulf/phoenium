# Stage 3 chunk readiness evidence

Status: **observed, implementation still absent**. This is the follow-up empirical record for the real Minecraft 26.2 client-demand path. It supplements the historical RCON-only report in
[`STAGE3_CHUNK_RUNTIME_EVIDENCE.md`](STAGE3_CHUNK_RUNTIME_EVIDENCE.md) and
updates the readiness rows in
[`STAGE3_CHUNK_RUNTIME_MAP.md`](STAGE3_CHUNK_RUNTIME_MAP.md).

The result is deliberately narrower than an ownership claim:

| Result | Observation |
| --- | --- |
| Fresh worlds | 2 independent fresh dedicated-server worlds, distinct seeds |
| Target | `minecraft:overworld` chunk `(4,-4)`, outside the startup/spawn view |
| Route | `stage3-fixture-a`, topology generation `1`, owner epoch `0` |
| Initial state | No server `LevelChunk` and no exact client `FULL` cache entry |
| Readiness | `FULL` accessibility, ticking preparation, ticking conversion, world ticking, and send-ready observed in both runs |
| Client receipt | Exact `ClientChunkCache.getChunk(4,-4,FULL,false)` returned a `LevelChunk` in both runs |
| Client removal | Same exact lookup returned `null` after the client left the tracking view in both runs |
| Demand removal | Tracking-view removal and `dropChunk` observed in both runs |
| Direct target-holder demotion/unload | **NOT OBSERVED** within the bounded 300-tick leave window; final server holder remained present |
| Gate labels | `R-CHUNK-01 = PARTIAL`; `Stage 3 = PARTIAL`; `Stage 4 = NOT STARTED` |

The solid blue/empty-looking screen seen during the run is consistent with the real client being in spectator mode at Y=320 over the target area before its target chunk arrived. It was not used as evidence of a void, a crash, or a frozen process. The evidence below comes from exact server events and
non-forcing client cache lookups.

## Reproduction and environment

Run from the `Phoenium` project directory:

```text
./gradlew.bat runStage3ChunkReadinessTrace --no-daemon
```

The task builds the production jar, copies it into an ignored disposable
external Fabric dedicated server, launches the account-free Fabric Client
GameTest harness, opens a real TCP connection to `127.0.0.1:25565`, and drives
the test server through local RCON. The target demand is caused by real player
tracking after a server-side teleport; no test-only server chunk request is
issued for the readiness path.

| Item | Value |
| --- | --- |
| Minecraft / Java | `26.2` / `25` |
| Fabric Loader / API | `0.19.5` / `0.159.0+26.2` |
| Loom / Gradle | `1.17.20` / `9.5.1` |
| Server | Real external Fabric dedicated server: **YES** |
| Production Phoenium jar | **YES**; client-test and `net/minecraft/client` jar entries: `0` |
| TCP client | **YES**; two clean login/disconnect lifecycles |
| Microsoft authentication / Launcher | **NO** / **NO** |
| GUI automation | **NO** |
| Workers / parallel gameplay | `1` / `OFF` |
| Trace bound | 10,000 events per server JVM; 300 ticks after leave |

The pinned merged Minecraft artifact was
`minecraft-merged-deobf-26.2.jar`, 37,399,549 bytes, SHA-256
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.
The production server jar was 239,443 bytes, SHA-256
`0952DE617A8C29718B12DC3C33FB63483AB3503619DDA12DDE63888D4C693E3F`.

## Target selection and preconditions

The readiness fixture uses A at chunk coordinates `x=-4..4, z=-4..4` and a
startup view that does not include `(4,-4)`. The target is therefore a same-
owner edge coordinate, not a cross-owner probe and not an artificial reach
override. The setup response for both runs confirmed
`targetChunkOutsideSpawnArea=true`.

Before login/demand, the read-only test command reported:

```text
chunkTrace inspect world=minecraft:overworld chunk=4,-4 route=stage3-fixture-a generation=1 ownerEpoch=0 latestStatus=UNRESOLVED holderPresent=false holderIdentity=null ticketLevel=UNRESOLVED fullFutureDone=false tickingFutureDone=false sendSyncDone=false getChunkNow=null hasChunk=false vanillaPath=read-only-inspection
```

The client-side precondition was also exact: `ClientChunkCache.getChunk(4,-4,
FULL,false)` returned `null`. A partial server holder is not treated as a
loaded chunk; the precondition is absence of a loaded `LevelChunk` and absence
of the exact client cache entry.

A preliminary probe of the earlier B target `(32,4)` closed itself with the
existing safety error `routing update crosses object owner; use transitionOwner
after a quiescent publication boundary`. The trace did not alter the pure
custody model, force a topology transition, add an owner adapter, or bypass
that guard. The readiness evidence consequently uses the same-owner A target.

## Exact instrumented methods

The descriptors are from the pinned Minecraft 26.2 artifact. The hooks are
observation-only and return without changing arguments, futures, tickets, or
continuations.

| Lifecycle point | Exact method and JVM descriptor | Observed thread/result |
| --- | --- | --- |
| Scheduling | `ChunkMap.updateChunkScheduling(JILnet/minecraft/server/level/ChunkHolder;I)Lnet/minecraft/server/level/ChunkHolder;` | `Server thread`; ticket transitions and holder identity |
| Holder future update | `ChunkHolder.updateFutures(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/Executor;)V` | `Server thread`; promotion decisions |
| FULL accessibility | `ChunkMap.prepareAccessibleChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;` | `Server thread`; observed in both runs |
| Full promotion | `ChunkHolder.scheduleFullChunkPromotion(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;Lnet/minecraft/server/level/FullChunkStatus;)V` | `Server thread`; `FULL`, then `BLOCK_TICKING`/`ENTITY_TICKING` |
| Ticking preparation | `ChunkMap.prepareTickingChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;` | `Server thread`; observed in both runs |
| Ticking result map | `ChunkMap.lambda$prepareTickingChunk$1(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/server/level/ChunkResult;)Lnet/minecraft/server/level/ChunkResult;` | `Server thread`; successful result path |
| Ticking conversion | `ChunkMap.lambda$prepareTickingChunk$2(Lnet/minecraft/server/level/ChunkHolder;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunk;` | `Server thread`; `listSize=9`, immediate branch |
| Post-processing | `LevelChunk.postProcessGeneration(Lnet/minecraft/server/level/ServerLevel;)V` | `Server thread`; exact `LevelChunk` identity |
| World ticking | `ServerLevel.startTickingChunk(Lnet/minecraft/world/level/chunk/LevelChunk;)V` | `Server thread`; full status `ENTITY_TICKING` |
| Map readiness | `ChunkMap.onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;)V` | `Server thread`; holder and `LevelChunk` both visible |
| Cache readiness | `ServerChunkCache.onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;)V` | `Server thread`; send-sync done |
| Tracking add | `ChunkMap.lambda$applyChunkTrackingView$0(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V` | `Server thread`; player context/lease visible |
| Tracking removal | `ChunkMap.lambda$applyChunkTrackingView$1(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V` | `Server thread`; player context/lease visible |
| Pending send | `ChunkMap.markChunkPendingToSend(ServerPlayer,ChunkPos)` and static `markChunkPendingToSend(ServerPlayer,LevelChunk)` | `Server thread`; post-readiness send bookkeeping |
| Client drop | static `ChunkMap.dropChunk(ServerPlayer,ChunkPos)` | `Server thread`; observed after leaving target view |
| Candidate direct demotion | `ChunkHolder.demoteFullChunk(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/server/level/FullChunkStatus;)V` | Not observed for the final ready holder |
| Candidate server unload | `ServerLevel.unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V` | Not observed for the final ready `LevelChunk` |
| Client receipt/removal | `ClientChunkCache.getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;` | Client thread; `FULL,false` exact lookup |

The delayed callback
`ChunkMap.lambda$prepareTickingChunk$3(ChunkHolder,LevelChunk,Object)V` was
also mapped and instrumented, but neither ordinary fresh-world run needed it.
Both runs completed through the immediate send-sync branch.

## Per-run results

| Run | World / seed | Holder identity | Server `LevelChunk` identity | Client receipt identity | Events |
| --- | --- | --- | --- | --- | ---: |
| `fresh-generation-1` | `STAGE3-CHUNK-READINESS-1-1788472908795` / `-4966688072561929527` | `ChunkHolder#1878335698` | `LevelChunk#2138815383` | client `LevelChunk#635884888` | 1,456 |
| `fresh-generation-2` | `STAGE3-CHUNK-READINESS-2-1788472934995` / `-1217979177269415904` | `ChunkHolder#1245927444` | `LevelChunk#1117641223` | client `LevelChunk#1826939919` | 1,487 |

The server and client identity labels are process-local diagnostic strings; the
identity hashes must not be compared across processes. Within each server run,
the holder identity remained stable from FULL accessibility through ticking and
send-ready. Both raw traces had `droppedEvents=0` and no event exception.

## Ordered event anchors

Sequence numbers are monotonic only within one server JVM. They are evidence
anchors, not a scheduler contract.

| Lifecycle point | Fresh world 1 | Fresh world 2 | Thread / evidence |
| --- | ---: | ---: | --- |
| FULL accessibility enter | 796 | 755 | `ChunkMap.prepareAccessibleChunk`, `Server thread` |
| FULL promotion (`status=FULL`) | 798 | 757 | same `ChunkHolder` as later readiness |
| Tracking view add | 885 | 844 | A route, context A, lease epoch 71 / 86 |
| Pending send | 886 | 845 | `ChunkMap.markChunkPendingToSend` |
| Ticking preparation | 890 | 849 | `ChunkMap.prepareTickingChunk` |
| Ticking conversion | 1192 | 1145 | `listSize=9`, `sendBranch=IMMEDIATE` |
| `LevelChunk.postProcessGeneration` | 1193 | 1146 | server `LevelChunk#2138815383` / `#1117641223` |
| `ServerLevel.startTickingChunk` | 1195 | 1148 | `fullStatus=ENTITY_TICKING` |
| `ChunkMap.onChunkReadyToSend` | 1197 | 1150 | same holder and server `LevelChunk` |
| `ServerChunkCache.onChunkReadyToSend` | 1199 | 1152 | send-sync done |
| Tracking view remove | 1387 | 1230 | A route, context A, lease epoch 83 / 93 |
| `ChunkMap.dropChunk` | 1388 | 1231 | demand removal after leaving view |

The exact client samples were:

```text
fresh-generation-1 receipt: targetLoaded=true, targetAddedInUpdateSet=false, targetRemovedInUpdateSet=false, chunkIdentity=net.minecraft.world.level.chunk.LevelChunk#635884888
fresh-generation-1 leave:   targetLoaded=false, targetAddedInUpdateSet=false, targetRemovedInUpdateSet=false, chunkIdentity=null

fresh-generation-2 receipt: targetLoaded=true, targetAddedInUpdateSet=false, targetRemovedInUpdateSet=false, chunkIdentity=net.minecraft.world.level.chunk.LevelChunk#1826939919
fresh-generation-2 leave:   targetLoaded=false, targetAddedInUpdateSet=false, targetRemovedInUpdateSet=false, chunkIdentity=null
```

The update-set booleans are intentionally auxiliary. They are per-tick sets and
the sampling call did not always land on the insertion/removal tick. The exact
position cache lookup is the asserted receipt/removal evidence; no broad
`ClientLevel.hasChunk` result was used.

## Route, context, and identity findings

Both route commands returned the same-owner result:

```text
route=RESOLVED world=minecraft:overworld chunk=4,-4 region=stage3-fixture-a generation=1 ownerEpoch=0 explicitFixture=true
```

The tracking add/remove events carried `contextRegion=stage3-fixture-a`,
`ownershipContext=true`, `regionLease=true`, with lease epochs 71/83 in the
first run and 86/93 in the second. Core generation, FULL conversion, ticking,
and send-ready events did not carry a Phoenium ownership context or lease. This
is an observation about the current thread-local context footprint, not a
custody decision or an authorization to route vanilla work.

The trace assigned `correlationId=1` and `requestIncarnation=1` per server JVM
for grouping. The JSON explicitly labels that value as trace-assigned because
Minecraft did not expose a vanilla request or holder incarnation at these
seams. The original public `ServerChunkCache.getChunkFuture` was not called by
the real client tracking path, so `originalFutureObserved=false` and
`futureSuccessObserved=false` are expected. No future was joined, replaced,
wrapped, or used as a substitute for the client packet path.

## Leave, demotion, and unload boundary

The practical leave observation succeeded in both worlds:

- the client moved back to the spawn chunk;
- the exact target cache lookup changed from a target `LevelChunk` to `null`;
- `CHUNK_MAP_APPLY_TRACKING_VIEW_REMOVE` and
  `CHUNK_MAP_DROP_CHUNK_TO_CLIENT` were recorded;
- the final target-holder diagnostic remained `latestStatus=minecraft:full`,
  `ticketLevel=31`, `fullFutureDone=true`, `tickingFutureDone=true`,
  `sendSyncDone=true`, `getChunkNow=LevelChunk#...`, `hasChunk=true`.

The direct `ChunkHolder.demoteFullChunk` and `ServerLevel.unload` hooks did not
fire for that final ready holder within 300 ticks. The earlier coordinate-level
trace could see an unrelated startup holder unload at the same coordinate; the
corrected status now correlates demotion/unload to the last ready holder/chunk
identity and reports `serverDemoted=false`, `serverUnloaded=false` in both
runs. This is why the summary field
`allRunsDemotionOrUnloadObserved=false` is retained rather than overstated.

## What the evidence does and does not establish

It establishes that, on this pinned 26.2 build, real player tracking can drive a
target from an absent initial state through FULL accessibility, ticking
promotion, `LevelChunk` ticking setup, send-ready callbacks, and an exact client
cache receipt. It also establishes exact client eviction and server tracking
demand removal after the player leaves.

It does not establish authoritative Phoenium chunk custody. In particular, it
does not prove stale-request fencing, holder-incarnation fencing, exactly-once
ownership application, exceptional future settlement, delayed send completion,
or server-side unload of the final holder. The trace remains observation-only
and leaves the accepted coordinated multi-stage admission architecture and
`ChunkCustodyModel` unchanged.

## Artifacts and next gate

The latest machine-readable summary is
`build/client-gametest/evidence/STAGE3-CHUNK-READINESS.json`. The raw selected
events are:

- `build/client-gametest/evidence/STAGE3-CHUNK-READINESS-fresh-generation-1.json`
- `build/client-gametest/evidence/STAGE3-CHUNK-READINESS-fresh-generation-2.json`
- `build/client-gametest/evidence/STAGE3-CHUNK-READINESS-world-seeds.txt`
- `build/client-gametest/evidence/STAGE3-CHUNK-RUNTIME-fresh-generation-1-latest.log`
- `build/client-gametest/evidence/STAGE3-CHUNK-RUNTIME-fresh-generation-2-latest.log`

The generated summary reports `success=true`, two distinct fresh seeds, stable
semantic ordering, receipt/removal in both runs, immediate send branch observed,
delayed branch not observed, and clean server/client lifecycle shutdowns.

The implementation gate is unchanged:

```text
R-CHUNK-01 = PARTIAL
Stage 3 = PARTIAL
Stage 4 = NOT STARTED
```

The next task remains the reviewed pure custody/state-model binding followed by
request-bound proof of the named Minecraft stages. No readiness hook in this
report is an implementation approval.
