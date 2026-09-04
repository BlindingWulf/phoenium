# Stage 3 authoritative chunk custody architecture

## 1. Decision

**DECISION: A — COORDINATED MULTI-STAGE ADMISSION.**
One logical custody covers a chunk lifecycle and its shared vanilla work; separately claimed stages authorize each regional mutation.
The unit of atomic execution is a synchronous vanilla mutation continuation with a closed footprint, not the lifetime of a chunk future.
Detached computation, regional application, and coordinated service work have different authorities; being on the server thread grants none of them.
Architecture confidence: **MEDIUM**. The decision is implementable; adapter behavior and the unobserved live paths still require acceptance evidence.
This document is the implementation authority, together with Luna's runtime evidence. Requirements below describe the future implementation, not current capabilities.
Stage 1/2 and all accepted non-chunk Stage 3 gates stay PASS. R-CHUNK-01 and Stage 3 stay PARTIAL; workers=1, parallel gameplay=OFF, Stage 4=NOT STARTED.
No production hook, C2ME integration, Stage 4 service implementation, or Stage 3 PASS tag is authorized by this review.

2026-09-04 bounded refinement: the
[FULL scoreboard service decision](STAGE3_FULL_SCOREBOARD_SERVICE_DECISION.md)
amends sections 6/9/10 for synchronous scoreboard access and ordered connection
submission. Its reservation is explicit service authority, independent of a
region lease; it does not close the rest of the FULL callback footprint or
enable FULL. The historical architecture and evidence below are preserved.

### Focused D1–D3 review amendment — 2026-09-04

[FULL D1–D3 decisions](STAGE3_FULL_D1_D3_DECISIONS.md) distinguishes the old
contract and the selected changes explicitly. D1-F refines §§6/7/9/10: the named
identity service may supply the enumerated owner-published, versioned identity
and target-validation facts at original synchronous reference calls. All
contributing writers participate before their enclosing claims. The actual
foreign pointer remains an alias, without live-read/mutation rights; no second
region lease or cross-dimension geographic convergence is authorized. Existing
unamended registry turns alone still do not grant these fact-service rights.

D2-P (early profile/cache/persistence effects and earlier identity observation)
and D3-X (private exact-object access allowing a self-dependent saved conversion
to complete) are **proposals requiring user approval for observable changes**.
They do not silently replace this record's synchronous/ready-input rules. Their
combined diagram retains C before S before ordered world turns, with a proposed
short cache validation grant between C and S and no I/O under any such grant.
No production binding is installed. FULL closure/readiness remains
INCOMPLETE/NO, Stage 3/R-CHUNK-01 PARTIAL and Stage 4 NOT STARTED.

## 2. Evidence basis

Reviewed at clean HEAD `e992f830601c366a17e9df3cc034a8430012b381` on 2026-09-03.
Initial `git status --short` was empty; `git diff --check` passed; the ten-commit history agrees with the supplied checkpoint.
Read in order: [chunk handoff](STAGE3_CHUNK_HANDOFF.md), [runtime evidence](STAGE3_CHUNK_RUNTIME_EVIDENCE.md), [runtime map](STAGE3_CHUNK_RUNTIME_MAP.md), [application review](STAGE3_CHUNK_APPLICATION_REVIEW.md), [handoff](HANDOFF.md).
The current local Loom artifact was rehashed, then the implicated methods were disassembled again; old offsets were not used as authority.
Artifact: `C:\Users\Admin\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`.
SHA-256: `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4` — exact match.
Accepted runtime facts include fresh/reload differences, early POI/light effects, late holder publication, and later callbacks carrying A's context for a B target.
`ServerChunkCache.getChunkFuture` remains **DIAGNOSTIC ONLY**; its existing observer cannot create authoritative custody retroactively.
Small source correction: this artifact's `getChunkFuture` server branch calls `managedBlock` and returns its future; the nearby synchronous `getChunk` also calls `join` and caches the result.
That correction does not change the observed late-return finding or the prohibition on owner waits.

Additional exact semantic findings supporting this decision:

| Current local symbol | Consequence |
| --- | --- |
| `GenerationChunkHolder.getOrCreateFuture`, `failAndClearPendingFuture` | A status slot may be cleared/recreated on the same holder; pending public status futures use the vanilla `UNLOADED_CHUNK` result on downgrade. |
| `GenerationChunkHolder.rescheduleChunkTask` | Replacing/cancelling a generation task does not necessarily invalidate shared status work already started. |
| `ChunkMap.updateChunkScheduling`, `lambda$scheduleUnload$0` | A holder can be recovered from `pendingUnloads`; the unload callback rechecks save-future identity and conditional map removal. |
| `ThreadedLevelLightEngine.lightChunk`, `lambda$lightChunk$2` | The API writes `setLightCorrect(false)` immediately and its POST_UPDATE supplier writes `true`; the whole method is not detached computation. |
| `LayerLightSectionStorage.swapSectionMap`, `ServerChunkCache.lambda$onLightUpdate$0` | Engine map publication is followed by callbacks that mutate holder light-change flags and broadcast bookkeeping. |
| `ChunkMap.isExistingChunkFull`, `save` | The type cache prevents proto saves from overwriting full chunks; a cache miss can synchronously read/join storage. |
| `LevelChunk.postProcessGeneration`, `ServerLevel.startTickingChunk` | Readiness can execute fluids, neighbor-shape updates, block writes, pending block entities and upgrade work; `startTickingChunk` unpacks ticks. |
| `ChunkHolder.scheduleFullChunkPromotion`, `demoteFullChunk`, `updateFutures` | Readiness has separate futures, promotion cancellation and ticket transitions beyond the generation-status array. |

Core inspected: `ChunkCustody`, `ChunkWorkRequest`, `ChunkCompletionGateway`, `TaskPayload`, chunk-only `PhoeniumLiveRuntime` paths, `RegionDirectory`, `OwnerGateway`, `RegionScheduler`, `OwnershipContext`, leases, `FailureBarrier`, `TopologyCoordinator`, `FrozenRegionAuthority`, and existing chunk tests.
Existing core is a serial model: its separate current-request check/claim, post-submit QUEUED update, lease acquisition and topology gate are not an implemented two-worker atomic admission protocol.
Those are corrections required below, not evidence that the locked ownership principles are wrong.
No Folia comparison, compatibility-mod search, new runtime run, or production implementation was needed for this decision.

## 3. Logical custody identity

**Phoenium logical incarnation is APPROVED as an admission identity, never as an invented vanilla request token.**
Use `LifecycleId = (serverSession, worldInstanceSequence, chunkKey, holderActivationSequence)`.
Use `WorkId = (LifecycleId, workSequence)` and `StageId = (WorkId, StageKind, statusOrSection, stageSequence)`.
Topology generation, owner epoch, expected owner and route snapshot are validation metadata, not equality components of any of these identities.
Correlation identifies diagnostics/subscribers, not authority. An actual retained holder reference, compared by `==`, is evidence attached to the lifecycle.
Never use `System.identityHashCode`, a world resource name alone, a task pointer alone, or an observed public request count as an incarnation.

Create lifecycle metadata at the chunk holder's first active-map admission, before any work starts; bind the actual holder and world instance there.
Register a work record when vanilla wins a new status-work reservation (`acquireStatusBump`/actual `applyStep` invocation); separately bind demand-slot subscriptions and the originating stage future when available.
Propagate this explicit record through the `GeneratingChunkMap.applyStep` call into load/generation/FULL registration; no asynchronous thread-local inference.
A temporary lexical binding may carry arguments across an immediate private call, but every retained callback must capture its own immutable handle before returning.
Every dependent holder in a generation cache has its own lifecycle/work record. The center request does not authorize neighbors' publication.
Repeated requests sharing a vanilla status future share the corresponding work record; asking for FULL must not cancel an existing EMPTY/LIGHT dependency.
Different statuses are dependency-linked work/stage identities in one lifecycle, not competing coordinate-wide requests.
Observe pending status-slot replacement and promotion-future replacement: increment a slot/promotion sequence, not the entire lifecycle for every ticket adjustment.
Started work remains valid through task rescheduling while its holder lifecycle and vanilla work reservation remain valid; demand-slot cancellation alone does not revoke that reservation.
`startedWork` is monotonic, and `ChunkGenerationTask.runUntilWait` waits for already-scheduled layers before releasing cancelled-task claims; cancelling such work would strand vanilla's reservation.

Before downgrade clears slots, fence those demand/promotion subscriptions and preserve already-applied prerequisites and still-running shared work.
Lifecycle states are ACTIVE -> RETIRING -> RETIRED; pending unload closes NEW work, but already-reserved generation/application stages may finish under current owner while vanilla references protect that lifecycle.
Keep a declared retirement route until those stages and save dependencies drain; otherwise the save-sync/unload wait would depend on work forbidden to finish.
If vanilla rescues the same holder before actual removal/unload commits, resume the SAME lifecycle and valid work reservations; refresh only the demand/promotion slots vanilla actually replaced.
A pending-unload rescue is not an unload/reload. Do not replay committed load/FULL registrations, cancel shared producers, or allocate a false new work incarnation on rescue.
Actual conditional unload removal after the reference fence commits RETIRED; actual unload/reload, holder replacement, world teardown or a fresh world instance then creates a new lifecycle.
Old-lifecycle continuations cannot be retargeted to that new lifecycle. Strong references prevent address reuse while records exist; monotonic sequences prevent ABA after cleanup.
Owner changes never create requests. A generation-only route refresh never supersedes valid work, including when the logical owner remains A.
Retain terminal tombstones until all registered deliveries/producers settle; then release holders, results and futures. Allocate sequences with overflow fail-closed behavior.

## 4. Stage model

Keep the existing monotonic intent, but separate work lifetime from the state of each named stage.
`REQUESTED -> READY -> QUEUED -> CLAIMED -> APPLIED` is per StageId; add precise terminals from section 11 and an INVOKING witness for diagnostics.
READY means all prerequisites, including service input readiness and footprint information, are available; it does not mean a producer thread owns application.
QUEUED is published before making a delivery visible. Duplicate producer notifications or queue deliveries observe the same record instead of regressing its state.
APPLIED records the stage outcome; it does not close the whole custody at public FULL completion, because later POI/light/readiness callbacks can still arrive.
Stages form the original dependency graph, not a newly imposed total ordering. Independent service branches remain independent.
Custody registration precedes producer launch; the earliest regional application gate is POI_INSTALL if that independent branch is ready first, otherwise LOAD_APPLY before `lambda$scheduleChunkLoad$3` enters its body.
That lambda is the earliest parsed-chunk application seam, not a universal first mutation across branches. Holder lifecycle/publication metadata is fenced from its creation as described above.

| Stage family | Atomic unit / relationship |
| --- | --- |
| LIFECYCLE | Holder activation, status-slot reservation/demotion and retirement metadata; regional publications use owner admission. |
| POI_INSTALL(section-load sequence) | One chunk's prepared packed POI installation, including local section callbacks; shared by read and background-drain paths. |
| LOAD_APPLY | Entire `lambda$scheduleChunkLoad$3`; its POI prerequisite is installed before entry, and synchronous consistency/light submissions remain nested subactions. |
| GENERATION(status) | Detached generation dependency work; publication is separate, and any live side effect uses its own stage. |
| LIGHT_INPUT / LIGHT_APPLY(batch) | Validated service-input handoff / post-compute regional flags or notifications; flags on exclusively borrowed unregistered generation objects follow the computation contract below. |
| STATUS_PUBLISH(status) | Original holder `applyStep` success handler and `completeFuture`, once for that status-work reservation. |
| FULL_CONVERT | Entire `lambda$full$0`, including nested proto replacement and all synchronous post-load registrations. |
| FULL_PUBLISH | STATUS_PUBLISH(FULL) after FULL_CONVERT returns; never folded into an arbitrary earlier callback. |
| TICK_READY(promotion) | Entire original ticking continuation, post-processing, tick unpacking and its immediate send branch, with a complete admitted footprint. |
| SEND_READY(promotion) | Original delayed send continuation when the send prerequisite was incomplete; shares the same send-stage identity as the immediate branch. |
| SERVICE_CALLBACK(sequence) | Later POI install, light notification, type-cache update, structure/debug notification or registration; target-specific and independently accounted. |
| LOAD_RECOVERY | Original genuine vanilla load-failure continuation, admitted only for a still-valid lifecycle. |

FULL_CONVERT and LOAD_APPLY contain subactions; do not schedule each individual field write or nested registration as a new future.
If a nested operation needs unavailable foreign authority, it must be discovered before the stage starts; do not suspend midway, release/reacquire, or replay its prefix.
Detach means exclusive computation custody or an actual immutable snapshot, not merely “not FULL yet.”
`SerializableChunkData` contains mutable arrays/NBT/containers; hand off exclusive ownership, never put those values in the core envelope.
Retain vanilla generation dependency/write-radius rules over shared ProtoChunks; holder visibility to generation consumers is not permission for gameplay or concurrent save snapshots.
Use `WorldGenRegion`/`ChunkStep` dependency identities to fence every mutable generation input; a wrapped live LevelChunk is not writable detached state.
LOAD_APPLY may construct an unregistered chunk and return it to generation custody while committing separate POI/service effects; application of those effects does not make every generated field live gameplay state.
An explicit generation borrow records all mutable chunk aliases/reservations; regional readers, save snapshots and FULL conversion cannot consume those fields until the relevant borrow is released.
Such a borrow is data-custody metadata, not a region lease, and survives topology-only movement. Vanilla dependency sharing is retained, not replaced by a claim of deep immutability.
Generated POI/structure/light callbacks leave computation through typed stages. Preserve read-only generation access and vanilla dependency ordering; do not move the generation algorithm onto region workers.

## 5. Stage classification table

Classification applies to the semantic operation, not the observed thread; mixed methods list the necessary sub-boundary in their reason.

| Method / operation | Classification | Reason |
| --- | --- | --- |
| `ServerChunkCache.getChunkFuture` observer | DIAGNOSTIC ONLY | The observed return is after installation; request lifecycle is captured upstream at holder/work creation. |
| `ChunkMap.prepareAccessibleChunk` | COORDINATED SERVICE | It orchestrates range/status dependencies; the ensuing accessible-holder publication has its own owner stage. |
| `ChunkMap.runGenerationTask` | COORDINATED SERVICE | Dispatcher/task bookkeeping controls detached workers without conferring regional authority. |
| `GenerationChunkHolder.applyStep` | COORDINATED SERVICE | Status reservation is shared generation metadata, while the success handler performs separately owned publication. |
| `ChunkMap.scheduleChunkLoad` | COORDINATED SERVICE | It constructs existing future edges joining disk, parse, POI and admitted application. |
| `ChunkMap.readChunk` | DETACHED COMPUTE | Storage I/O/data fixing returns detached input and has no regional application permission. |
| `SectionStorage.prefetch` | COORDINATED SERVICE | It mutates pending-load bookkeeping under a load protocol; its packed-data completion is not POI installation. |
| `lambda$scheduleChunkLoad$1` | DETACHED COMPUTE | Parsing creates exclusively retained serializable input using stable read-only context. |
| `lambda$scheduleChunkLoad$3` | REGION-OWNED MUTATION | It converts the parsed/absent result and causes POI/light/map effects before returning. |
| `SerializableChunkData.read` | REGION-OWNED MUTATION SUB-STAGE | POI reconciliation and light submissions are synchronous subactions of LOAD_APPLY. |
| `PoiManager.checkConsistencyWithBlocks` | REGION-OWNED MUTATION SUB-STAGE | It reconciles target section records against chunk blocks and emits derived-index changes. |
| `SectionStorage.unpackChunk` (both forms) | REGION-OWNED MUTATION | Packed records become live section records; either trigger must claim the same POI_INSTALL. |
| `ThreadedLevelLightEngine.retainData` | COORDINATED SERVICE | It submits engine-owned light-storage work; the command must carry a validated lifecycle/input sequence. |
| `ThreadedLevelLightEngine.queueSectionData` | COORDINATED SERVICE | It transfers light payload custody to the serialized engine, not to the submitting thread. |
| `ThreadedLevelLightEngine.lightChunk` | COORDINATED SERVICE | It spans flag writes needing explicit compute custody or regional admission plus engine computation; thread identity proves neither. |
| `ChunkMap.markPositionReplaceable` | COORDINATED SERVICE | Its type-cache write affects save safety and must be ordered against storage/lifecycle changes. |
| `ChunkMap.markPosition` | COORDINATED SERVICE | It updates the same shared cache and returns its previous byte, requiring atomic ordered access. |
| `GenerationChunkHolder.completeFuture` | REGION-OWNED MUTATION | It publishes chunk references and may immediately invoke dependent handlers. |
| `GenerationChunkHolder.replaceProtoChunk` | REGION-OWNED MUTATION SUB-STAGE | Its CAS replacements are inside FULL_CONVERT, with the same authority and no yield. |
| `ChunkStatusTasks.full` | COORDINATED SERVICE | It schedules the original conversion supplier; the scheduling call is not the conversion. |
| `ChunkStatusTasks.lambda$full$0` | REGION-OWNED MUTATION | Conversion, loaded state, listeners and registrations must complete coherently. |
| Post-load LevelChunk registration | REGION-OWNED MUTATION SUB-STAGE | Entities, block entities and tick containers require the enclosing footprint plus declared registry ownership. |
| Later POI/map callbacks | COORDINATED SERVICE | Each callback carries its real target and service/lifecycle sequence, with POI record application re-entering the target owner. |
| Light engine arrays / propagation | COORDINATED SERVICE | Cross-chunk light storage has one service owner and a versioned input/publication contract. |
| `ServerChunkCache.lambda$onLightUpdate$0` | REGION-OWNED MUTATION | Holder light flags require the target's owner; broadcast-set bookkeeping uses the map service. |
| Immutable route/correlation reads | GLOBAL-SAFE | These are immutable metadata reads with no regional effect or application privilege. |
| `prepareTickingChunk` / `$2` / `$3`, send and promotion | UNKNOWN / REQUIRES LIVE EVIDENCE | Bytecode locates owned work, but the chunk-only run did not demonstrate either readiness branch or its footprint. |
| `handleChunkLoadFailure` live behavior | UNKNOWN / REQUIRES LIVE EVIDENCE | Source defines the recovery split; a bound exceptional-path acceptance test is still required. |

No observed mutable map, POI, holder or light method is approved as unconditionally GLOBAL-SAFE.

## 6. Lease/admission semantics

The closed integration API retains live objects privately: `ChunkStageBinding`, `PendingVanillaStage`, `ChunkStageOutcome`, `ChunkStageFootprint`, `ChunkServiceReceipt` and `ChunkLifecycleRegistry`.
Core envelopes carry only WorkId/StageId, route validation, correlation and typed outcome metadata. There is no generic cross-owner Runnable gateway.
Only named adapters can register an original vanilla function/supplier/consumer and its dependent future; arbitrary mods/callers cannot submit closures.

Admission ordering is mandatory:

1. Producer readiness records the stage and relinquishes its result; the dispatcher publishes QUEUED before exposing its typed envelope.
2. Resolve the current directory, active holder/slot and complete synchronous footprint; acquire any service turn/readiness receipts without holding a region lease.
3. At scheduler admission, use the existing topology coordinator's metadata gate to linearize lifecycle/shutdown fences, route validation and lease reservation.
4. Inside that short gate, verify participant admission is open and lifecycle entitlement allows the stage, reserve exactly one owner lease, and CAS the eligible stage to CLAIMED; no vanilla code, future completion or callbacks execute there.
5. Release the metadata gate; install the target OwnershipContext, require the live lease, and revalidate the captured lifecycle, stage, route/epoch and footprint before INVOKING.
6. Run the original synchronous continuation once; its thin function/supplier wrapper records the return/failure witness before returning to CompletableFuture's completion machinery; release the lease/context in `finally` after that invocation unwinds.
7. Downstream asynchronous stages acquire their own authority. Retire service reservations and publish typed acknowledgments without holding the topology gate.

Claim follows lease reservation, not the reverse: a stale queue delivery must not consume the only execution claim before an owner can execute it.
Cancellation, holder demotion and shutdown use that same admission linearization; a winning claim pins its synchronous stage until it exits.
Revalidation after a successful reservation must agree; disagreement is an invariant breach, not permission to retry partial work.
The current serial scheduler can implement this on its one control lane; future multiworker lease/accounting publication needs synchronization under the same gate, not a second topology authority.
No lease spans I/O, computation, service-response waiting, a future lifetime, or another owner. Awaiting prerequisites is a parked record, never an occupied owner.
Nested known stages may use an existing lease only after exact target/domain/lifecycle validation and a distinct successful stage claim; they never enter a second OwnershipContext.
A B callback encountered under A can enqueue B work only. It cannot replace A's context, recursively advance B, or treat “server thread” as a lease.

## 7. Owner/topology transition semantics

Topology-only movement follows the chunk to the newly declared owner with the same WorkId/StageId and detached result.
Refresh expected generation/epoch even for A -> A; do not invalidate data merely because some unrelated directory entry changed.
If a closed synchronous footprint spans A and B, request existing quiescent convergence before claim; keep the stage READY/QUEUED and release all reservations.
Do not invent a composite A+B lease or enlarge an active frozen domain. Service input reads use their separate snapshot/read protocol, not foreign region pointers.
Publication of a split/merge occurs only after participant leases drain; reconcile lifecycle routing metadata before reopening admission and releasing mailboxes.
Already-applied stages remain APPLIED across topology publication, and dependencies retain their original results. A split between N and N+1 cannot replay N.
Missing routes park behind a real pending topology admission; a permanently missing route has an explicit terminal/barrier disposition, never a world-default fallback.

Two-worker proof, for the proposed contract rather than the present implementation:

| Schedule | Queue, claim and result |
| --- | --- |
| X starts in A; parse finishes; X republishes to B | LOAD_APPLY carries the same lifecycle/work/stage identity; any A inbox copy fails route validation before claim and routes to B; one B claim runs. |
| FULL and a light callback become ready after X -> B | Both carry X's lifecycle plus distinct stage IDs; they queue to B, and FULL is eligible only after its original lighting prerequisites; duplicate deliveries lose their stage CAS. |
| Callback arrives while A is executing unrelated work | It records readiness only; A's ambient context grants no B mutation and no nested scheduler drain occurs. |
| Merge requested while A/B leases are active | Admission closes under the metadata gate; existing stages finish in their frozen domains; coordinator advancement returns pending until both drain, then republishes and requeues. |
| Split after stage N | N stays terminal; N+1 resolves the new domain and either claims there or parks for convergence if its own footprint spans owners. |
| Shutdown between two claims | The earlier claim may finish its synchronous unit; the later claim loses to the fence, invokes nothing, and enters explicit terminal settlement. |

For each schedule: zero stale-owner mutation, zero duplicate invocation, no A+B lease pair and no synchronous peer wait.
Service-engine internal computation may continue through a topology change because it owns service state, not a region; resulting regional callbacks always re-resolve.

## 8. Future semantics

Keep the vanilla future graph and result objects. Bind the executor argument of the existing continuation registration, retaining its original operation and the future that registration creates.
Do not return a replacement public future, create a competing success pipeline, fabricate a ChunkAccess, or complete public success before the original mutation returns.
Arm a PendingVanillaStage before dispatch can execute: registration can race upstream completion and can call its executor before returning the dependent future.
The executor can capture readiness immediately, but invocation/terminal settlement waits for the registration record to receive that exact dependent future.
Preserve all original dependency edges, success values, genuine failure handling, cancellation winners and same-call nested ordering; wall-clock timing and unspecified sibling execution order are not guarantees.
For `GenerationChunkHolder.applyStep`, replace only the `handle` registration with a request-bound `handleAsync` edge invoking the original handler on success/genuine vanilla failure.
This is a necessary scheduling change at the same graph edge, not wrapping the returned future with a parallel graph; the edge's returned future remains the one given to vanilla.
Test both “upstream completes before registration” and “after registration”; otherwise a registering worker can still publish unowned state.
An asynchronous boundary may defer dependents; never detach a void publication call from its enclosing returning handler and let that handler complete early.

Completing a future can synchronously run handlers. Every regional handler must therefore be bound/guarded independently; completing under B does not authorize an A dependent.
The original invocation's return allows its original CompletableFuture machinery to complete; any nested same-target handler uses validated staged sub-admission, and foreign handlers only enqueue.
Terminal completion also occurs outside the topology gate and uses the same callback controls; the shutdown thread must not execute pending mutation Runnables.
Every retained registration owns a settlement action for success, genuine failure, vanilla unload, private cancellation and barrier; an executor that silently drops a Runnable is forbidden.

Stale internal `CompletableFuture<ChunkAccess>` cannot carry `UNLOADED_CHUNK` because that is a different type.
Settle an uninvoked private edge with a sealed `CustodyAborted(WorkId, reason)` marker, and recognize only that typed, bound marker at BOTH the load-recovery edge and the holder-handler edge.
Load recovery propagates it without invoking `handleChunkLoadFailure`; the holder edge converts it to the already-authorized vanilla UNLOADED result for an actually retired/disallowed reservation, without calling `completeFuture`.
For normal retirement, complete/clear affected public demand slots through the vanilla lifecycle transition first; never overwrite an already successful future or a newer slot.
Superseding a subscriber does not abort shared work: vanilla `completeFuture` may populate a subsequently recreated slot from the SAME still-current work reservation; validate the work lineage, not equality to a cancelled subscriber future.
For an active still-demanded reservation, staleness is a reroute/reconciliation event, not permission to invent unload; an unexplained loss of binding trips the barrier.
For FAILED_BARRIER, settle retained dependents exceptionally with the recorded cause through the private control path and stop admission; do not invoke vanilla recovery or crash handlers as a fallback execution route.
No broad `Throwable`/`CancellationException` filtering: real vanilla cancellation and exceptions retain their original behavior unless this exact registered custody terminal owns settlement.

No owner may enter vanilla `managedBlock`, synchronous chunk acquisition, POI load joins, or cache-miss disk joins with unfinished prerequisites.
Preflight/load prerequisites before claiming; use only already-available results within a stage. Do not release A's lease underneath an unfinished synchronous caller.
The serial server loop needs a narrowly typed chunk-admission pump at a no-OwnershipContext event-loop point, including vanilla managed-block polling outside owners, so startup/RCON chunk requests progress without waiting for another world tick.
An unfinished synchronous request from an owner is a contract violation caught before the wait; this design does not claim the entire Stage 2 whole-level tick is ready for parallel execution.

## 9. POI/light/map handling

### Map cache and shared bookkeeping

`chunkTypeCache` is a **global coordinated metadata service**, not a region-owned chunk container and not an unsynchronized safe map.
`markPosition*` writes and `isExistingChunkFull` reads must share one atomic per-key protocol; distinct keys in a plain fastutil hash table can still race during insertion/rehash.
Use a private concurrent metadata table with atomic previous-value semantics, plus lifecycle/storage-operation sequence checks; preserve `markPosition`'s previous-byte return contract.
Order cache changes against accepted load/save operations for that coordinate; a late old-lifecycle marker cannot overwrite a newer full-chunk observation.
Reserve a storage observation/read before owner application when the cache is unknown; do not return “not full” as a missing-owner/cache substitute or join a cache miss under a lease.
ChunkMap active/pending holder maps, pending generation bookkeeping, broadcast membership and shared registration indexes need a declared serialized coordinator or atomic metadata facade as appropriate; a per-chunk lease does not protect their table internals.
Their adapters expose finite metadata operations, never arbitrary world execution. Existing serial control-lane access remains the Stage 3 implementation baseline.

### POI

POI records/sections are regional; pending I/O bookkeeping and the cross-section distance index are coordinated services.
Before LOAD_APPLY admission, require an installed, versioned POI receipt for this chunk as well as the original combined prerequisite; preserve prefetch's original packed-data future and keep the extra receipt inside admission metadata.
Both `unpackPendingLoads` and read-triggered `unpackChunk` claim the same POI_INSTALL sequence; completion of one makes the other an idempotent observation.
Remove the application call from inside SectionStorage's `loadLock` drain: extract a prepared entry under its metadata protocol, release it, route application, then acknowledge successful installation.
Do not set loadedChunks before actual installation, and do not hold the load lock while calling section/dirty callbacks, obtaining a lease, or completing a future.
The synchronous `unpackChunk(ChunkPos)` path must consume its installed receipt; it must not call `join` during the chunk-owned read.
Use a concurrent section-directory facade for container integrity; each mutable PoiSection is still accessed only by its current region owner.
Dirty/section-load notifications publish immutable `(section, lifecycle, recordVersion, occupancySummary)` messages to the single distance-index service; it must not read live foreign PoiSections to compute a source level.
Chunk application preserves immediate section-record consistency; derived distance-index consumers must observe the corresponding sequence before using an index answer.
No broad POI lock and no owner waiting for an index answer. A caller requiring an answer must obtain its readiness receipt before regional execution.
These are the narrow Stage 3 storage/install/index-ingress contracts; general villager/query regionalization remains Stage 4, and no parallel gameplay is enabled while those callers remain serial.
The future two-worker chunk pipeline is valid only through this facade; leaving a worker-side direct POI record/index path is a failed gate, not an accepted exception.

### Lighting

Retain the engine's serialized PRE_UPDATE / propagation / POST_UPDATE ordering and async execution; do not put propagation or I/O under a region lease.
Engine arrays, pending light layers and visible light maps have one declared coordinated-service owner; chunk flags, holder dirty flags and regional callbacks have region owners.
LIGHT_INPUT transfers versioned section data/exclusive DataLayers to the engine; retain/queue calls nested in reload carry the same lifecycle provenance.
For an unregistered ProtoChunk or newly deserialized unregistered wrapped LevelChunk, both light-correct flag writes may stay in the existing generation/engine continuations under the exclusive generation borrow.
The actual reload `SerializableChunkData.read` constructs that LevelChunk; nevertheless `ImposterProtoChunk.setLightCorrect` forwards to its wrapped chunk regardless of allowWrites, so the wrapper type alone is never proof of detachment.
For an already registered/live chunk, the immediate false write requires current owner BEFORE calling `lightChunk`; arrange the existing upstream admission accordingly, and its later true write is a separate owned LIGHT_APPLY.
Do not queue the immediate setter and return as if it ran, or bridge a delayed synchronous method call with a replacement future. A live-input caller lacking prior authority fails admission; the observed initial-generation path uses the explicit borrow.
The worker must use detached generation inputs or versioned read-only light-input views, not unrestricted mutable LevelChunk pointers obtained from `getChunkForLighting`.
Provide the light service a chunk-input directory keyed by lifecycle and input version; gather required live inputs by typed owner snapshot requests before the relevant engine batch, with no owner held while gathering.
Invalidate the engine's cached chunk views on input-version/lifecycle replacement; preserve service queue order and copy-on-write visible map publication.
A service command validates its lifecycle/input sequence when accepted and before publication; superseded input cannot overwrite a newer lifecycle's visible light state.
If a batch depends on invalidated input, discard/recompute its detached result within the existing pending stage; do not replay an already-applied regional flag/notification.
The service may publish its own coherent light snapshot; it has no permission to set a chunk flag, publish a holder or execute a world callback.
For live input, the original POST_UPDATE supplier and its original returned future completion run as LIGHT_APPLY under current owner after the engine's completion receipt; for borrowed generation input they release that compute borrow before owned status publication.
`onLightUpdate` routes by every affected section's actual chunk, with its own StageId/input version, then performs holder/broadcast effects under the declared authorities.
Cross-chunk propagation does not mean the center chunk owns neighbors. Preserve every affected chunk's lifecycle and service version, including already-loaded neighbors.
General gameplay light-update producers remain outside this review; the chunk-facing input facade is mandatory and must coexist with the existing serial producer protocol before PASS.

## 10. FULL/holder/readiness

FULL_CONVERT is one coherent synchronous stage: construct/reuse LevelChunk, `replaceProtoChunk`, set full status, post-load entities, loaded flag, block entities, tick containers, unsaved listener.
`replaceProtoChunk` is a guarded nested substage, never a separately queued callback. The persisted ImposterProtoChunk branch legitimately skips replacement.
FULL_PUBLISH is separate because the holder's handler can be registered/executed on another thread; it depends on completed conversion and validates the work lineage/lifecycle and current vanilla slot disposition again.
Do not cancel/replay a converted LevelChunk merely because ownership moves before FULL_PUBLISH; transfer custody and publish under the current owner if vanilla demand is still valid.
Preflight post-load entity positions/passenger positions and all block-entity/tick registrations; a center coordinate alone does not prove this footprint.
Shared registry insertion belongs to its named coordinator; local records belong to the region. Any synchronous foreign object effect requires prior topology convergence, not an asynchronous reorder of the vanilla post-load body.
Preserve original `replaceProtoChunk` ordering inside conversion; its lower-status slot replacements are not proof that FULL/readiness is already usable.

TICK_READY owns the complete post-processing closure, including fluids, neighbor callbacks, upgrades, random-source/service access and tick unpacking.
The existing `getChunkRangeFuture` radius of one proves a prerequisite range, not a bound on all synchronous mutable effects.
Derive/check the transitive footprint before claim using the pending post-processing/upgrade data and vanilla callback bounds; converge the required chunks before entry.
If no finite supported closure can be established, reject admission before mutation and keep this live gate blocked; a runtime escape guard is a barrier backstop, not a successful implementation of valid gameplay.
Promotion/demotion are fenced by the exact `pendingFullStateConfirmation`/ticking/send generation; terminal FULL work does not authorize a later promotion after downgrade.
The immediate and delayed send branches preserve vanilla order: the immediate branch executes inside TICK_READY if all required authorities are ready; the delayed branch reacquires them as SEND_READY.
Send readiness uses a tracking-service recipient snapshot and typed per-connection notifications; a chunk lease never authorizes mutable foreign-player access.
Snapshot packet/chunk data under the chunk authority; connection queue bookkeeping has its own serialized connection authority. Keep the existing packet/movement architecture unchanged.
The architecture is fixed now; **one additional precise live readiness trace is mandatory before accepting the Step 6 binding**, not before the pure model work.
Scenario: an offline real client approaches one absent target chunk at a fixture ownership edge; generate it, obtain FULL, then ticking/send-ready, verify the client receives it, and leave to trigger demotion.
Record post-processing footprint, promotion/send future identities, current target route/lease, and both send-sync branches (a test-only latch may delay send-sync completion without changing its result).
Include natural fluid/post-processing at the edge or a test-only legitimate queued post-processing fixture; an empty post-processing list cannot certify closure coverage.

## 11. Failure/supersession

| Outcome/event | State and action |
| --- | --- |
| STALE route/generation | Nonterminal delivery disposition; refresh/reroute the same stage, with no vanilla invocation. |
| SUPERSEDED subscriber/lifecycle | Terminal for an obsolete subscription or actually invalidated work lifecycle; settle through the fenced vanilla unload/control path, never relabel old work as a new lifecycle. |
| CANCELLED | Normal authorized lifecycle/shutdown cancellation before claim; account for every retained future and reference. |
| FAILED_VANILLA | Record original producer/mutation failure and invoke only the original applicable vanilla error edge under authority; no ownership exception substitution. |
| FAILED_BARRIER | One-way failure for broken authority/binding or partial mutation of unknown validity; close admission, preserve evidence and settle retained stages without recovery. |
| Duplicate delivery/claim | Normal no-op with a counter; return/observe the recorded outcome, without a second invocation or new dependent future. |
| Foreign mutation attempted / unexpected prior invocation | Invariant breach: barrier before mutation where possible; never silently count it as a duplicate. |

Claimed work cannot be revoked midway: a concurrent normal supersession records a pending fence and waits asynchronously for stage exit before retiring it.
Vanilla failure and successful recovery are different stage outcomes: a failed parse may feed a successful LOAD_RECOVERY; the original exception need not become the public result if vanilla recovers it.
In the inspected artifact, `handleChunkLoadFailure` reports non-Error failures and calls `createEmptyChunk`; an Error path marks replaceable and throws ReportedException.
Preserve those genuine vanilla policies and causes, including a legitimate new ProtoChunk after missing data or real recoverable storage failure.
**Phoenium-caused fake EmptyChunk/AIR/BEDROCK is forbidden.** Never feed stale custody, rejected admission or barrier exceptions into that recovery function.
An exception after a regional stage starts is never a retry permission; record partial execution and keep the appropriate vanilla crash or Phoenium barrier behavior.
Barrier reporting must be explicit inside wrappers because CompletableFuture captures callback exceptions; do not rely solely on `RegionScheduler` catching them.
An ownership guard failure inside a continuation is translated to the bound private control marker with FAILED_BARRIER and its original cause, so it cannot enter vanilla recovery as an ordinary thrown load error.
Use one safe disposable exceptional test: inject one IOException into the existing detached read future for the target, without editing a region file, then run the real recovery edge and compare the vanilla outcome/cause.
In the same bound-adapter test suite, inject private custody cancellation and assert zero recovery calls. Fatal Error handling can use a deterministic bound-method test to avoid crashing a live world.
Natural supersession is optional to reproduce live; deterministic tests must use actual holder/slot downgrade semantics, not only a counter-only core mock.

## 12. Shutdown

Publish the chunk admission fence atomically with scheduler quiescence; unlike normal pending unload, shutdown admits no new claim, producer registration or service application after it.
An already-claimed synchronous stage finishes or fails under its existing lease; shutdown does not roll back applied vanilla work or start its next stage.
READY/QUEUED stages become CANCELLED with explicit settlement; already completed public futures retain their outcomes.
Shutdown settlement may complete retained future state through its lifecycle coordinator after callbacks are disarmed; it must not clear/rewrite holder fields or perform vanilla unload/registration without an already-admitted lease.
Detached computation may finish and release data, but its terminal custody prevents regional application and prevents new service input publication.
Disarm pending original mutation callbacks before exceptional/control completion can trigger dependents; bound dependent wrappers perform metadata settlement only when fenced.
Drain terminal bookkeeping through a designated control lane, never by running retained vanilla Runnables on the stop caller. No owner `join`, recursive owner drain or raw shutdown executor.
Observe completion/refcount releases asynchronously before final registry disposal; retained pending futures, service commands and tombstones must have bounded, reported terminal accounting.
Keep save/unload metadata cleanup distinct from chunk installation; no ownership failure is “recovered” by executing unowned application to let shutdown finish.

## 13. C2ME compatibility contract

Producer-agnostic boundary: explicit work identity -> detached/exclusive result plus dependencies -> typed ready stage -> current-owner validation/claim -> original application -> original outcome.
Producer executor names, thread identity and the vanilla public-future observer are not inputs to authority decisions.
Any future producer must respect result aliasing, service-input versions, supersession and terminal settlement; faster computation cannot bypass an application stage.
C2ME is NOT INTEGRATED and NOT STARTED here; this is a contract only.

## 14. Deterministic test matrix

Require **20 test families**; parameterize stages/registration orders rather than duplicating implementation-shaped tests.

| ID | Controlled adversary and required assertion |
| --- | --- |
| C-A01 | A -> A complete pipeline: every applicable stage invokes once and preserves the original result/future identity. |
| C-A02 | A request, route changes to B before application: same work follows B; stale A mutation=0. |
| C-A03 | N under A, split then N+1 under B: N remains APPLIED, never replayed; original dependency edge preserved. |
| C-A04 | Two deliveries/producer completions for one StageId: one claim and vanilla invocation; QUEUED cannot regress APPLIED. |
| C-A05 | R1 work lifecycle actually retired before claim, R2 current: R1 mutation=0, old public future unloads, R2 independent; subscriber-only cancellation is tested separately in C-A12. |
| C-A06 | Replace holder, actually unload/reload, and rescue pending unload in separate variants: old lifecycles reject, legitimate rescue preserves current work without replay or stranded reservations. |
| C-A07 | Shutdown after READY before CLAIMED: zero invocation, all registered futures/refcounts settle. |
| C-A08 | Genuine IOException and fatal error: original recovery/crash policy and cause preserved; private staleness never calls recovery. |
| C-A09 | Foreign-owner execution, missing lease and escaped footprint: hard barrier before unauthorized mutation, no fallback. |
| C-A10 | New directory generation, same A owner: revalidate same custody without unnecessary cancellation/reparse. |
| C-A11 | Upstream completion before/after holder-handler registration and before registration arming: same owned publication and no lost future. |
| C-A12 | Concurrent status demand, downgrade/recreated slot and cancelled task: shared startedWork survives, scheduled layers drain, and only its valid lineage may fill the new demand slot. |
| C-A13 | Merge closes admission during A/B leases; race claim/closure/publication: exactly one winner and no premature B authority. |
| C-A14 | Claim wins shutdown/supersession: synchronous body finishes once, next stage cannot begin, no partial replay. |
| C-A15 | POI drain races read-triggered unpack: one installation, loaded receipt after effect, no application under loadLock and no join. |
| C-A16 | Distinct-key type-cache updates plus stale same-key save/load marker: safe table access, previous-byte semantics and no stale overwrite. |
| C-A17 | Cross-boundary light batch and lifecycle replacement: no live foreign input read, old result discarded, each notification admitted by its target. |
| C-A18 | FULL constructor/post-load/replacement ordering and reload-imposter branch: no early FULL success and no repeated registration. |
| C-A19 | Immediate/delayed send, demotion and post-processing footprint: one send readiness per promotion, zero foreign-player/world effects. |
| C-A20 | Future completion under foreign ambient context, barrier swallowed by CompletableFuture, startup pump and terminal cleanup: no nested authority, no owner wait, no orphan. |

Existing supersession tests remain useful; extend them instead of discarding them. Add bound-adapter tests with real vanilla holder/future methods where model-only tests cannot establish semantics.

## 15. Live acceptance matrix and R-CHUNK-01 PASS

| Evidence | Requirement |
| --- | --- |
| Fresh generation of an absent chunk, repeated with a second fresh world | MANDATORY FOR P3 PASS: real producer, application, POI/light inputs, all applicable statuses and future result. |
| Clean-stop persisted reload of the first world | MANDATORY FOR P3 PASS: parsed branch, actual POI install/consistency, retain/queue light data and reload conversion. |
| FULL conversion + holder publication, including registration-order probes | MANDATORY FOR P3 PASS live normal paths; deterministic adapter probes supplement race coverage. |
| Real client approach, ticking/readiness/send and leave/demotion from section 10 | MANDATORY FOR P3 PASS; no counter-only substitute for observed client receipt and live post-processing. |
| Controlled topology change between load and FULL/light stages | MANDATORY FOR P3 PASS in the serial live harness with latches; two-worker schedules use deterministic substitutes. |
| Natural request supersession/holder unload race | OPTIONAL live race; DETERMINISTIC SUBSTITUTE ACCEPTABLE only through bound actual lifecycle/slot operations. |
| Genuine exceptional load branch | MANDATORY behavior; safe disposable injected-I/O live test preferred; DETERMINISTIC SUBSTITUTE ACCEPTABLE using the real recovery/future adapter, not a mock policy. |
| Shutdown with pending application and injected ownership breach | MANDATORY FOR P3 PASS: clean terminal accounting, zero raw execution; normal/error process lifecycles remain distinct. |

All tests follow the existing account-free harness/testing instructions; this review runs none of them and changes no test acceptance status.
PASS requires every following condition, backed by traces distinguishing producer, service and regional execution rather than observer admission counters:

- Producer-thread regional mutation, foreign-owner application, stale-request application, stale-holder application and duplicate authoritative invocation are all zero.
- Ownership failure -> fabricated EmptyChunk/AIR/BEDROCK, raw shutdown application, peer locks, broad gameplay/POI locks and blocking owner waits are all zero.
- Every regional mutation has the current lifecycle/slot, full footprint, current target lease/context and a successful StageId claim.
- POI records, light inputs/results and map/registry containers have declared owners, safe concurrent access and versioned service ordering; cross-chunk effects do not inherit center authority.
- Original public future identity/results and genuine vanilla failure/cancellation/dependency semantics survive both registration orders and every terminal path.
- No callback escapes by inline completion, no live input alias is mislabeled detached, and no partial stage is replayed after failure or topology change.
- All retained stages/futures/refcounts settle; lifecycle tombstones and strong payload references are reclaimed, and already-applied work is not rolled back.
- Admission/claim/lease reservation is atomic against topology closure, lifecycle retirement and shutdown; no mutation or completion runs under the metadata gate.
- Readiness/send, fresh generation, reload, FULL and publication are observed live; unresolved required evidence keeps R-CHUNK-01 PARTIAL.

## 16. Terra implementation sequence and interception surface

| Step | Changes / new interfaces | Required evidence / fixed decision |
| --- | --- | --- |
| 1 | Correct `ChunkCustody`, `ChunkWorkRequest`, typed payload and gateway: LifecycleId/WorkId/StageId, strict monotonic transitions, explicit terminals, queue-before-delivery and atomic current-check/claim; integrate scheduler/topology admission reservation. | Start with no Minecraft hooks; preserve one worker and the existing topology authority. Topology is validation, shared statuses do not supersede one another. |
| 2 | Add custody/admission tests and private `ChunkLifecycleRegistry` / `PendingVanillaStage` model with registration arming and terminal settlement. | C-A01–14 and C-A20; exact vanilla downgrade/slot-future tests before claiming lifecycle integration. Do not invent a second future graph. |
| 3 | Bind holder activation/status work and the two load continuation registrations; add the typed no-owner event-loop pump and guards. | Normal/absent load, original future identity, no wait, no fast-registration race; this hook alone cannot earn PASS. |
| 4 | Add `ChunkPoiBridge`, `ChunkLightBridge`, `ChunkMapMetadata` and service receipts/input versions; route POI drain/read competition, light flag endpoints and section notifications. | C-A15–17, fresh/reload effects; no broad service lock or worker authority, no moving propagation to owners. |
| 5 | Bind FULL supplier and original holder completion handler; add nested replacement/registration assertions. | C-A11/C-A18; retain one FULL_CONVERT body plus separate FULL_PUBLISH. |
| 6 | Extend the existing chunk trace with the single section-10 client scenario, then bind ticking, promotion/demotion and both send paths. | C-A19 and live footprint evidence before accepting this binding; no assumption that range radius=mutation closure. |
| 7 | Finish lifecycle supersession/unload rescue, private control-marker filtering, explicit barrier reporting and stop settlement. | C-A05–09/C-A14/C-A20 and real-method failure tests; no owner-failure recovery or ignored futures. |
| 8 | Run the existing harness with authoritative stage counters, frozen footprints, service versions and actual vanilla future/holder identities. | All mandatory rows in section 15; clean build after code/test changes, preserve all existing regressions. |
| 9 | Audit field/callback coverage and acceptance artifacts against section 15, then report the chunk gate for separate review. | No automatic P3 tag, Stage 4, second worker or C2ME. Failed evidence leaves PARTIAL. |

Smallest defensible interception surface is **seven semantic adapter groups**, not one executor replacement and not every traced method:

1. Holder/work lifecycle: active-map admission/retirement/rescue and status-slot/promotion fences, using `ChunkMap`/`GenerationChunkHolder`/`ChunkHolder` accessors and exact reservation sites.
2. Load: existing normal `thenApplyAsync` application edge and `exceptionallyAsync` recovery edge in `scheduleChunkLoad`; retained binding, executor arguments and private terminal filter.
3. POI: SectionStorage pending-ready extraction/install acknowledgment plus consistency/index-ingress bridge; raw observers are not reused as application authority.
4. Lighting: INPUT borrow/flag/handoff, original POST_UPDATE flag supplier, and `ServerChunkCache.onLightUpdate` dispatch; input-directory/cache binding is part of this service adapter.
5. FULL/publication: `ChunkStatusTasks.full` supplier executor and `GenerationChunkHolder.applyStep` handler registration; `replaceProtoChunk` needs an assertion, not a separate scheduler.
6. Readiness: `prepareTickingChunk` continuation, send continuation registration, immediate send branch and holder promotion executor/fence.
7. Metadata/progress: type-cache access facade and finite shared-registry operations, plus the no-owner chunk pump. No wholesale wrapping of mainThreadExecutor or routing arbitrary server tasks.

These groups contain multiple injection sites where independent branches make that unavoidable; do not pretend “seven groups” means seven Mixins or a verified final injection count.
Prefer enclosing registration/call-site wrappers with exact descriptors and required match counts; use accessors only for the named lifecycle/slot state.
Observational hooks: existing dormant Luna traces. Diagnostic hook: existing getChunkFuture observer. Authoritative hooks: the named admission/service/lifecycle bindings above.
Do not change packet/movement design, copy upstream code, use identity-hash lookup as authority, replace public futures, or generalize the private API into a Runnable bus.

## 17. Unresolved evidence and review disposition

**No demonstrated Stage 0 contradiction; no additional pre-model empirical blocker.** A coherent multi-stage contract is possible without a monolithic lease.
Terra may implement the pure model and named adapters now; readiness closure, service input aliasing and vanilla lifecycle settlement remain acceptance gates, not claims of existing correctness.
The exact minimal additional live readiness experiment is section 10; genuine exceptional behavior has the safe section-11 test/substitute. Real natural supersession need not be manufactured unsafely.
If the readiness trace/source closure cannot bound synchronous effects before entry, mark **BLOCKER: TICK_READY footprint**, preserve this decision's other stages, and do not hide it behind a center-only lease.
Likewise, a discovered POI/light callback that cannot satisfy the specified narrow service contract is a blocker to the implementation gate, not authorization to start general Stage 4 services.
The implementation may refine exact injection mechanics; changing identity, service ownership, future settlement, stage atomicity or lease lifetime requires architecture review, not an undocumented Terra redesign.
Review delivery changes documentation only. `git diff --check` is required; no build/test rerun is claimed for unchanged production code.
Stop at this decision: R-CHUNK-01=PARTIAL; Stage 3=PARTIAL; Stage 4=NOT STARTED.
