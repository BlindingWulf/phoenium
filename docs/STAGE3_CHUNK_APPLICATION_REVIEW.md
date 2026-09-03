# Stage 3 base chunk application review

Review date: 2026-09-02 (America/Chicago). Read
[the movement decision](STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md) first. The Minecraft
26.2 artifact identity and local disassembly reproduction details are recorded there.
Stage 3 and **R-CHUNK-01 remain PARTIAL**. No C2ME implementation was inspected or changed.

## Finding

The narrow normal disk-load application boundary is the **second**
`CompletableFuture.thenApplyAsync(Function, Executor)` in
`net.minecraft.server.level.ChunkMap.scheduleChunkLoad(ChunkPos)`, bytecode offset 55.
It uses `ChunkMap.mainThreadExecutor` (field read at 52) and invokes
`lambda$scheduleChunkLoad$3(ChunkPos, Optional)`.

Ownership must be established **before that continuation runs**, not after it returns,
not merely before `markPosition`, and not at the downstream `getChunkFuture` observer.
The continuation calls `SerializableChunkData.read(ServerLevel, PoiManager,
RegionStorageInfo, ChunkPos)` at offset 38. That method can already mutate live POI
state and submit light-engine state before it returns a chunk.

A request-bound owner executor can control this dispatch without waiting or replacing
Minecraft's externally returned future. However, **this one dispatch is not a complete
chunk-ownership boundary**. Exceptional load handling, holder publication, POI unpacking,
FULL conversion and readiness application have additional paths. Those are identified
below. No safe single-hook takeover of the complete pipeline is established by this review.
Installing only that hook would not justify R-CHUNK-01 PASS or even readiness for the
full live gate. No application Mixin is added here.

## Exact successful and exceptional load trace

`ChunkMap.scheduleChunkLoad` descriptor:

```text
(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;
```

The local `ChunkMap-verbose.txt` BootstrapMethods table resolves the invokedynamic
functions to the lambda methods below, confirming the binding rather than inferring it
from synthetic names. Offsets in this table are method-relative bytecode offsets.

| Phase | Method and offsets | Authority consequence |
| --- | --- | --- |
| Request/read | `scheduleChunkLoad`: `readChunk` at 2 | A request identity, current holder incarnation, status and generation must be captured upstream of producer work; the return observer is too late. Holder/task creation itself needs declared ownership. |
| Background parse | `thenApplyAsync` at 21 using the `parseChunk` background executor; `lambda$scheduleChunkLoad$0` maps through `$1`, which calls `SerializableChunkData.parse` at 12 | Parsed data is not installed by this parse call. Producer access to height/container/registry context is not an owner lease and needs a read-only or otherwise explicit calculation contract. |
| POI prerequisite | `PoiManager.prefetch` at 30; `thenCombine` at 41; `$2` returns its first argument | Both futures are prerequisites. Prefetch completion means packed POI data is ready, not necessarily that live POI application has been owned. |
| Normal application | `thenApplyAsync` at 55, executor field at 52, `$3(ChunkPos, Optional)` | First identified application of the *parsed chunk data* into live services. Acquire current owner admission before the entire lambda. |
| Successful read | `$3`: `SerializableChunkData.read` at 38, `markPosition` at 51 | A hook only at `markPosition` misses earlier POI/light effects. |
| Legitimately absent stored chunk | `$3`: `createEmptyChunk` at 59 | Vanilla begins generation of a real ProtoChunk for absent persisted data. This is different from Phoenium substituting fake world state for a foreign/missing owner. |
| Load exception | `exceptionallyAsync` at 69, executor field at 66, `$4(ChunkPos, Throwable)` | Must use a request-bound owner executor too. `$4` calls `handleChunkLoadFailure` at 3. |
| Recovery/error application | `handleChunkLoadFailure`: `markPositionReplaceable` at 121 in the Error branch; otherwise failure reporting and `createEmptyChunk` at 153 | Ownership rejection must never be fed into this disk-error recovery and turned into an empty chunk. Real disk errors keep their explicit vanilla error semantics under valid authority. |

`SerializableChunkData.read` calls `PoiManager.checkConsistencyWithBlocks` at 180,
`LevelLightEngine.retainData` at 238 and `queueSectionData` at 261/281; it also constructs
chunk objects and post-load processors. Its return type is `ProtoChunk`, including the
imposter wrapper case. It is not just deserialization into detached immutable values.
Light submission is not proof that every eventual lighting action is owner-safe; its
producer/application contract remains a separate dependency of a full implementation.

Nor is `SerializableChunkData` deeply immutable merely because it is a Java record.
Its fields include arrays, maps of arrays, section data, NBT compounds and mutable
container data. The viable handoff is **exclusive detached-result custody** after the
producer relinquishes it, or an actual immutable copy. Do not put it into the immutable
core envelope and relabel it immutable. A normal `ChunkAccess` generation result needs
its own custody proof because earlier holder futures can already expose that object.

## Additional application paths and races

### Holder future installation is independent

`GenerationChunkHolder.applyStep(ChunkStep, GeneratingChunkMap, StaticCache2D)` calls
`GeneratingChunkMap.applyStep` at 30 and attaches **non-async** `CompletableFuture.handle`
at 42. Its `lambda$applyStep$0` calls `completeFuture(status, chunk)` at 27 on success;
on failure it calls `BlockableEventLoop.relayDelayCrash`. `completeFuture` publishes the
chunk through the holder's futures array (compare-and-set at 41 or future completion
at 51) and rejects duplicate successful completion. Atomic collection operations do
not substitute for Phoenium ownership.

The completion and registration race matters: if the application future finishes before
`handle` is registered, the registering thread can execute the handler. If registration
comes first, the completing thread can execute it. Java's non-async completion policy
does not guarantee Phoenium owner context. See the
[Java 25 CompletableFuture contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html).
Therefore an owner executor at disk-load `thenApplyAsync` does not by itself own holder
installation. Each stage's publication continuation needs explicit owner dispatch, for
example a narrowly audited `handleAsync` at that registration site using the same
request identity. This is a proposed scheduling binding, not an implemented replacement
of the public future/result contract. Its failure behavior must be tested as well.

`ChunkMap.runGenerationTask` submits to `worldgenTaskDispatcher` at 34. The constructor
builds that dispatcher around the `worldgen` `ConsecutiveExecutor`, separate from the
`mainThreadExecutor` field. `lambda$runGenerationTask$0` executes `runUntilWait` and
reschedules through `thenRun`. There is no basis for assuming all holder-stage handlers
run inside a Phoenium gameplay lease just because the request began on the server thread.

### POI is a second branch, not a pure completion flag

`PoiManager` inherits `SectionStorage`. Its `prefetch` records pending loads under the
existing load lock; it returns the packed-data future. `SectionStorage.tick` can call
`unpackPendingLoads`, which uses `getNow` and then calls `unpackChunk(ChunkPos, PackedChunk)`
at 83. The latter writes the section-storage map at 39/127 and invokes section-load/dirty
callbacks. This can run independently of the chunk read continuation and can apply POI
before it. The previous trace's phrase "first live application boundary" must be read as
the first **parsed chunk-result** application identified, not the first mutation in every
associated service or scheduling branch.

`getOrLoad` can also call `unpackChunk(ChunkPos)`, whose bytecode contains a `join` at 70.
The load chain's prefetch prerequisite can make that future ready on this path, but a
general owner operation must not rely on an implicit blocking call. A controlled POI
application stage must settle the packed-data readiness and own the unpack before
`SerializableChunkData.read` enters consistency checks. Other direct POI load callers
remain outside the proof. This review adds no lock or wait and does not approve the
vanilla load lock as a regional gameplay-ownership mechanism.

### Generated results and FULL conversion

For non-EMPTY steps, `ChunkMap.applyStep` retrieves the parent chunk through the cached
`GenerationChunkHolder` and calls `ChunkStep.apply` at 80. `ChunkStep.apply` invokes
the task and can attach a non-async `thenApply`; `completeChunkGeneration` changes a
ProtoChunk's persisted status at 31. Intermediate generation commonly operates on
mutable chunks, not fresh immutable result snapshots. The holder dependency cache,
`ChunkStep` dependency radii and write radius require explicit compute custody and
application classification. This review does not proclaim them detached or regionalize
the generation algorithm.

`ChunkStatusTasks.full(WorldGenContext, ChunkStep, StaticCache2D, ChunkAccess)` dispatches
`lambda$full$0` with `CompletableFuture.supplyAsync` at 38 using
`WorldGenContext.mainThreadExecutor()` at 35. That lambda:

- wraps or constructs the LevelChunk and calls `GenerationChunkHolder.replaceProtoChunk`
  at 66;
- calls `runPostLoad` at 87 and `setLoaded(true)` at 93;
- registers block entities at 98 and tick containers in the level at 105;
- assigns the unsaved listener at 114.

That is an additional mandatory live application seam. It must validate the request and
the whole installation footprint before construction/holder replacement and callbacks,
not just the center chunk's RegionId. The executor is supplied through WorldGenContext;
replacing the `ChunkMap` field globally is not a reliable per-request hook.

Later `ChunkMap.prepareTickingChunk` has another `thenApplyAsync` at 24 on
`mainThreadExecutor`. Its nested `$2` calls `postProcessGeneration` at 23 and
`ServerLevel.startTickingChunk` at 31. It can defer a send-ready callback through
`thenAcceptAsync`. These prove that first application and tick-ready application are
different boundaries. Listing them here does not authorize implementing later tick or
tracking categories in this review.

## Proposed Phoenium contract

For each named application stage, the integration must satisfy:

```text
owner-admitted request / reserved target identity
    -> producer calculation with defined result custody
    -> immutable completion envelope (identity + stage + outcome only)
    -> resolve current owner and current request/holder incarnation
    -> current owner lease, closed-topology/readiness checks
    -> claim this application stage exactly once
    -> original vanilla application continuation
    -> original future's normal result/error propagation
```

The request identity must include world incarnation, chunk key, holder incarnation,
request generation, requested status/stage and correlation ID. Topology generation is
recorded for routing, but changing topology alone does not obsolete a still-valid request.
Unloading/replacing the holder or superseding its generation does. Do not recover request
identity from the current chunk key after the producer finishes.

A possible narrow binding for **the disk-load application dispatches** is a request-bound
executor argument at `scheduleChunkLoad`: normal `thenApplyAsync` ordinal 1 (ordinal 0 is
parse), argument index 1, and `exceptionallyAsync` ordinal 0, argument index 1. Use exact
descriptors and required match counts against the inspected artifact. Preserve the
existing Function objects and future chain. Do not replace the entire blockable event
loop, route every server Runnable, or identify requests via a producer thread-local.
The holder handler and FULL conversion require their own named stage bindings.

The private integration boundary may retain the original continuation/result in a sealed,
request-keyed pending record. Only its opaque identity crosses the typed gateway. This
is a finite set of audited vanilla operations, not a general cross-owner Runnable API.
Producer enqueueing grants no authority. At execution, recheck holder identity, current
route, stage, request generation, footprint and admission state, then atomically claim
the stage. Release the metadata gate before invoking any vanilla continuation.

For a topology-only change, retain the same request and reroute to its current owner;
never use a captured old runtime. A live holder cannot be swapped out of a retained
continuation: if the captured holder incarnation is obsolete, that continuation must
not run. Its original request must reach the corresponding terminal cancellation/unloaded
or failure outcome through an audited vanilla request lifecycle. Running it on a new
holder just because the coordinates match would apply stale data.

Exactly-once applies **per application stage**, not merely per original request. A request
can legitimately contain POI application, chunk read, holder publication, FULL conversion
and readiness stages. A stage lifecycle is REQUESTED -> READY -> QUEUED -> CLAIMED ->
APPLIED, with explicit FAILED/CANCELLED terminals. Duplicate delivery cannot invoke
the application again; duplicate detection must not strand a dependent future.

If a stage is deferred, return immediately from dispatch and retain its pending identity.
The public future stays pending until the actual stage runs. A missing owner must cause
pending topology admission or a terminal classified failure, never inline vanilla
execution. Supersession/cancellation, shutdown, scheduler failure, exceptional completion
and request-ledger cleanup all need a terminal-accounting proof. Merely dropping the
executor's Runnable leaves a future pending forever.

In particular, throwing an ownership/cancellation exception into the existing load
chain is insufficient: `exceptionallyAsync` can recover it through `createEmptyChunk`.
Ownership failures require the Phoenium barrier and explicit terminal cleanup, outside
the genuine storage-error recovery policy. A continuation that has partially mutated
live state must not be retried. The exact safe terminal mapping is still an implementation
blocker; this review does not claim it solved by an executor wrapper.

The completion claim/admission must linearize with topology closure, and its lease must
freeze its entire mutable footprint for the continuation. A requested application that
requires new adjacency waits for convergence before applying. An inactive foreign region
is not permission to mutate its holder. POI, lighting and shared map/index structures
also need declared partitions or explicit service protocols; a center-chunk route alone
does not make an ordinary shared HashMap safe for two workers.

## Future C2ME boundary

The same semantic contract applies to a later C2ME integration: parallel computation
does not authorize first live application. The producer must establish immutable data
or exclusive non-authoritative custody, current request identity and completion/error
semantics. Every first authoritative application and holder publication is owner-admitted.
No C2ME fork, adapter, capability claim or compatibility test is created here.

## Observer disposition and gates

**Existing `ServerChunkCache.getChunkFuture` return observer: DIAGNOSTIC ONLY.** It may
remain to measure the public future while preserving its object and result contract.
It is not moved and promoted into an application owner merely by changing its name.
Current code observes only the first future per world; the `chunkApplications` diagnostic
counts metadata admission, not actual Minecraft installation. Neither counter proves
all load requests are intercepted.

Required deterministic tests for the later adapter:

| Case | Controlled order and expected evidence |
| --- | --- |
| Normal load | Complete parse and POI packed-data futures manually; assert no read/POI/holder application before the relevant lease, then one application and the genuine future result. |
| Completion registration race | Complete the upstream future both before and after holder-handler registration. Assert identical owner checks and publication thread/context. |
| Topology change | Queue an application, close A, commit B, then drain. Original request remains valid; exactly one B application and zero A applications. |
| Holder/request supersession | Replace the holder incarnation at the same coordinate before completion. Old continuation never runs, old future reaches its audited terminal outcome, new request remains independent. |
| Failure recovery | Real parse/I/O failure uses the owned vanilla failure continuation; ownership/stale-request failure cannot invoke `createEmptyChunk`. |
| Duplicate and shutdown | Duplicate each named stage; stop between READY/QUEUED/CLAIMED. Account for all futures and retained records without unowned execution or replay after partial mutation. |
| FULL/readiness | Force FULL conversion and tick-ready/post-load work; verify the footprint includes all touched holders, POI/light/entity/index data, not just center-chunk metadata. |

These designs are not executed evidence. The current core completion test only increments
a mock application counter and does not exercise these continuations. R-CHUNK-01 remains
**PARTIAL**. The load dispatch seam is architecturally located; full owner application
is not yet ready for a live PASS attempt. The next chunk implementation must prove
request custody, terminal accounting and each relevant application path before live
forced completion/stale-completion tests. No P1 redesign is shown to be unavoidable,
but the earlier single-main-executor interpretation was incomplete.
