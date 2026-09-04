# Stage 3 PARSED_APPLY future semantics decision

## 1. Decision and scope

**DECISION A: APPROVE THENCOMPOSEASYNC FLATTENING.**
Confidence: **HIGH for this composition decision**; production admission remains unimplemented/unaccepted.
Keep A — coordinated multi-stage admission, RegionDirectory authority, and short synchronous owner leases.
This approves a precisely bounded graph transformation, including its necessary recovery and holder companions.
It does not approve enabling PARSED_APPLY with the current infrastructure unchanged.
R-CHUNK-01=PARTIAL; Stage 3=PARTIAL; Stage 4=NOT STARTED; workers=1; parallel gameplay=OFF.
C2ME is neither integrated nor started. No production source is changed by this review.

This decision refines the executor-only wording in architecture sections 8/16 and the adapter document's open future gate.
The original typed body remains synchronous; its **registration** becomes asynchronous composition.
No already-created/publicly exposed vanilla future is replaced, and no competing load pipeline is created.
Internal dependent-future identity across two versions of the program is not an equivalence requirement.
Adjacent recovery must also support owner completion; a lone thenApplyAsync-to-thenComposeAsync edit is insufficient.

## 2. Verified checkpoint and evidence

Start HEAD: `0aecc38d1765b866e39ca3d5f44082c732800859`; initial status clean; diff check clean.
The ten-commit history matches the supplied checkpoint. Re-executed deterministic suite: **99 passed, 0 failed/errors/skipped**.
Read the five requested architecture/adapter/runtime-map/runtime-evidence/handoff documents and the eight named adapter classes.
Inspected the current artifact, not alternate versions or another server implementation:
`C:\Users\Admin\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`.
SHA-256: `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4` (matching).
Runtime: Temurin `25.0.4.1+1-LTS`; local JDK src.zip checked for encoding, application, composition and recovery.
Public references: [Java 25 CompletableFuture](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
and [OpenJDK 25 source](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java).
Exact wrapping conclusions below are grounded in the installed JDK source and execution, rather than a moving upstream branch.
An ignored, test-only `build/parsed-apply-review/FutureSemanticsProbe.java` passed **44 JDK cases**.
It compares exception/cancellation encodings and immediate/deferred stages without joins, waits or Minecraft execution.
Run locally with `java build/parsed-apply-review/FutureSemanticsProbe.java`; it is disposable review evidence, not a committed test gate.
The required bound Minecraft/real-worker FUT tests below have **not** been implemented or claimed as passing.

## 3. Exact current vanilla graph

```text
readChunk(pos)
  .thenApplyAsync(parseFunction, Util.backgroundExecutor().forName("parseChunk"))
  -> P: CompletableFuture<Optional<SerializableChunkData>>
poiManager.prefetch(pos) -> Q: POI packed-data prefetch future
P.thenCombine(Q, keepParsedData) -> U: parsedAndPoiFuture
U.thenApplyAsync(originalParsedApplyFunction, mainThreadExecutor) -> D
D.exceptionallyAsync(originalLoadRecoveryFunction, mainThreadExecutor) -> R
return R: CompletableFuture<ChunkAccess>
GenerationChunkHolder.applyStep attaches handle(originalHolderHandler) -> H
H: CompletableFuture<ChunkResult<ChunkAccess>> -> ChunkGenerationTask
```

The parse Function is `lambda$scheduleChunkLoad$0`, which maps through parser `$1`; combiner `$2` returns its first argument.
PARSED_APPLY is `lambda$scheduleChunkLoad$3(ChunkPos, Optional)`; recovery `$4` calls `handleChunkLoadFailure`.
scheduleChunkLoad bytecode: parse registration 21, prefetch 30, combine 41, application 55, recovery 69, return 72.
Only P/Q occupy locals; D flows directly on the operand stack into recovery registration.
There is no vanilla store, reference comparison, alias escape or separate subscriber to D before that registration.
ChunkMap.applyStep's EMPTY branch calls scheduleChunkLoad at offset 19 and returns R at 22.
GenerationChunkHolder.applyStep calls the map at 30, attaches handle at 42 and returns H at 45.
Its successful reservation is acquireStatusBump; a losing caller resolves the shared status future instead.

## 4. Equivalence contract

**MUST PRESERVE:**

- Read/parse and POI branches, their join dependency, exclusive input custody, and the original mutation bodies.
- Success by reference identity: D/R receive the exact ChunkAccess returned by `$3`, including the fresh ProtoChunk or reload ImposterProtoChunk.
- No successful completion/publication before the relevant original body finishes; no replay or duplicate invocation.
- For eligible work, original genuine failure/recovery meaning, exception cause identity and existing CompletionException nesting.
- Recovery's exact input throwable once produced by D, its original result object or thrown failure, and no second recovery of recovery itself.
- Independent holder publication authority, shared work surviving demand-only cancellation, and existing completed-future winners.
- No fabricated ChunkAccess, corruption recovery for private controls, old-lifecycle publication into a new holder, or abandoned retained future.
- No owner wait, blocking admission, main-thread authority fallback, foreign lease, or lease across asynchronous work.

**EXPECTED INTENTIONAL CHANGE:**

- Mutation occurs later on the admitted current owner; the main executor executes a readiness/registration wrapper only.
- D completes when the returned inner stage completes, rather than when the wrapper returns that stage.
- Owner completion can run bookkeeping/relay code and awaken downstream wrappers on a different thread.
- Profiling thread attribution, wrapper stack frames, dependent counts, internal allocations and unspecified sibling order may differ.
- Explicit private terminal transport for actually retired/shutdown work, consumed as vanilla unload at the holder boundary.
- Adjacent recovery/holder edges may flatten asynchronous stages while preserving their original semantic roles.

This is equivalence for the inspected vanilla consumers and the accepted lifecycle contract, not all conceivable reflective CF observers.
For active eligible work, there is no added success/failure meaning beyond the intentional scheduling change.
For retired work, raw R is exceptionally terminal with private control; H/status subscribers receive authorized UNLOADED.
It would be false to claim that raw R looks like an ordinary successful ChunkAccess during that terminal extension.

## 5. A/B/C comparison

PASS means the specified design satisfies the property; CONDITIONAL names implementation proof still required.
B evaluates the inspected native outer/task edges without reconstructing the same application graph.
C evaluates retaining synchronous thenApplyAsync while adding a private settlement future.

| Property | A thenComposeAsync | B alternate seam | C bridge |
| --- | --- | --- | --- |
| No blocking | PASS | PASS | FAIL |
| Current-owner execution | CONDITIONAL | FAIL | FAIL |
| Result identity | PASS | PASS | CONDITIONAL |
| Failure cause preservation | PASS | PASS | CONDITIONAL |
| Vanilla recovery compatibility | CONDITIONAL | FAIL | FAIL |
| Control outcome filtering | CONDITIONAL | FAIL | FAIL |
| Holder/work identity available | PASS | PASS | CONDITIONAL |
| Exactly once | CONDITIONAL | FAIL | CONDITIONAL |
| No public future replacement | PASS | CONDITIONAL | FAIL |
| C2ME-compatible later | CONDITIONAL | CONDITIONAL | FAIL |
| Implementation complexity | CONDITIONAL | CONDITIONAL | N/A |

B: ChunkMap.applyStep is composition-capable and has a holder, but invoking its original EMPTY branch installs the unowned inner `$3` already.
Deferring that entire method under an owner only schedules future work; it cannot authorize the later application.
GenerationChunkHolder's handler is after mutation. ChunkStep.apply/ChunkStatusTask.doWork return futures, but EMPTY bypasses them entirely.
Status-task/full registration therefore cannot intercept this load; moving EMPTY into that abstraction reconstructs more orchestration than A.
No materially safer native alternate seam satisfying all five requirements was found. A finite lexical registrar is packaging of A, not B.
C: Function<T,ChunkAccess> cannot return a CompletableFuture<ChunkAccess>; attaching one cannot delay its own return value.
Returning null/fake data, waiting, or replacing the public result violates the contract. Returning a stage changes the operation to composition: A.
A requires a finite load registrar, two adjacent completion adapters and lifecycle settlement; no new generic scheduler is justified.
C2ME compatibility remains a producer-contract inference, not a compatibility test or integration approval.

## 6. Exception and cancellation proof

Let N(E) be E itself if E is a CompletionException, otherwise the JDK's single CompletionException whose cause is E.
Local encodeThrowable and encodeRelay implement this rule; UniApply/UniCompose both propagate upstream failures via completeThrowable.
UniCompose relays an already-complete inner result or installs UniRelay for an incomplete inner result; both normalize identically.
The exceptionallyAsync function receives D's stored throwable directly, without an extra exception wrapper.

| Event | Original thenApplyAsync D | Composed D / recovery input |
| --- | --- | --- |
| U exceptional with E | N(E); mapper never invoked | N(E); wrapper never invoked |
| Synchronous mapping function throws E | N(E) | N(E) if registration wrapper throws E |
| Owner invokes original body, which throws E | Original body would produce N(E) | inner.completeExceptionally(E), then relay N(E) |
| Inner already/delayed exceptional with E | No distinct inner in original | N(E), equal cause identity and nesting to throwing E |
| U cancelled with CancellationException C | N(C); D.isCancelled=false | Same N(C); wrapper not invoked |
| Inner cancelled with C | Analogous to original mapper throwing C | N(C); inner.isCancelled=true, D.isCancelled=false |
| Existing CE E, including CE(CE(cause)) | Exact E retained | Exact E retained; never recursively unwrap |
| Direct cancellation of D | D stores raw C; recovery receives C | Same if explicitly cancelled; not the inner-cancel case |
| Cancellation of R | R stores raw C; predecessors continue | Same; never propagate R cancellation to shared work automatically |

Newly allocated outer CompletionException identity/stack is not equal across executions; its cause object must be identical.
An already-existing CompletionException retains its own identity and entire cause/suppressed structure.
Use a private ordinary CompletableFuture for the inner stage; do not add custom toCompletableFuture behavior or return a null stage.
Success values are passed directly to complete(value). Real failure E is passed directly to completeExceptionally(E).
No RuntimeException(E), new CompletionException(E) unconditionally, CompletionStage copy chain, or recursive cause stripping.
The current ChunkStageExecutor wraps all thrown vanilla bodies and ChunkStageAdmission trips the barrier; this cannot implement that rule.
Terra must record VanillaFailure(E) at the invocation boundary, distinct from an authority/binding breach, with no retry after invocation.
Unexpected adapter/queue failures are explicit bound barrier outcomes, not pretend vanilla data errors.

handleChunkLoadFailure unwraps **one** CompletionException, then **one** ReportedException, for classification/reporting.
An Error takes the replaceable-marker/crash path; other failures report and create the original empty ProtoChunk.
Extra wrapping could hide that Error and incorrectly recover, so nesting is a correctness requirement, not cosmetic.
A genuine CancellationException has vanilla's ordinary recovery behavior; only authenticated private controls are filtered.
Public cancellation can win while work continues, as before; the changed race timing follows deferred execution.
Suppressing shared work just because a subscriber cancelled would be a separate, forbidden semantic change.
Cancelling D before its readiness callback starts suppresses that callback through normal JDK rules.
After the callback starts, cancellation does not interrupt the logical invocation or cancel its inner producer; actual lifecycle fences still govern owner claim.

## 7. Approved registrations and recovery authority

```text
P, Q, U: unchanged operations and dependencies
D = U.thenComposeAsync(input -> parsedBinding.ready(input), mainThreadExecutor)
R = D.exceptionallyComposeAsync(error -> recoveryBinding.readyOrControl(error), mainThreadExecutor)
H = R.handle((value, error) -> holderBinding.readyOrControl(value, error))
     .thenCompose(identity)
```

Each ready method returns its own preallocated private completion stage and only records/enqueues typed readiness.
For PARSED_APPLY success/failure, translate Applied(v)/VanillaFailure(E) into the inner stage exactly as section 6 specifies.
The recovery companion is explicit: **exceptionallyAsync becomes exceptionallyComposeAsync**, at the same recovery role.
Merely wrapping its synchronous Function can filter control but cannot nonblockingly execute genuine LOAD_RECOVERY on an owner.
Genuine recovery preserves D's exact throwable as retained input; its own StageId claims under current ownership and invokes `$4` once.
Its output/exception settles R through the same relay rules. A recovery failure goes to H, never back through `$4`.
Holder success/fatal handling is likewise one admitted original handler; its small initial handle only returns a stage.
The composed H is the sole returned holder-step result; no original H is first created and subsequently swapped out.
Only the EMPTY load path and its immediate holder edge are included here; general status/publication migration stays separately scoped.

Changing recovery to composition can remove an implementation-only success relay hop on mainThreadExecutor.
In the local JDK, a pending UniExceptionally can claim its executor even on success; UniComposeExceptionally relays success directly.
Already-successful registration can bypass that hop even in vanilla, so no stable main-thread completion contract exists there.
Both forms invoke the actual recovery function zero times on success, and once for an eligible genuine failure.
Do not claim an identical JDK scheduling trace or retain unclassified inline holder mutation to conceal this difference.

## 8. Completion order and thread affinity

ChunkGenerationTask.scheduleChunk/waitForScheduledLayer uses getNow to distinguish pending from terminal H.
Pending work stays in scheduledLayer; an unsuccessful ChunkResult marks cancellation; runUntilWait drains scheduled layers before releasing claims.
Thus an asynchronously completed H and an authorized UNLOADED result are already native downstream shapes.
No inspected consumer requires D/R completion during the original main callback. Dependencies, not that callback's stack, govern progress.
Completing D/R/H may synchronously trigger handlers, so each regional downstream handler needs its own admission regardless of ambient context.
Completion happens outside topology metadata gates and after the original invocation's lease/context unwinds.

The actual `$3` increments the current-thread profiler, then either reads SerializableChunkData and marks type, or creates an empty ProtoChunk.
Read constructs unregistered chunk objects, reconciles POI sections, submits light data, and fills chunk-local status/heightmaps/structures/NBT.
Inspected ChunkAccess/ProtoChunk/reload LevelChunk constructors initialize retained objects; postLoadChunk only captures a later processor.
No unconditional server-thread assertion or dependence on the outer executor callback's stack was found in these bodies.
Profiler attribution changes to the worker; it must use that worker's valid profiler scope rather than sharing main's filler.
**This does not make unmodified vanilla containers safe across workers.** The accepted section-9 service contracts remain mandatory:

- POI getOrLoad reaches unpackChunk, including a join and mutable storage/loaded/pending maps: require an installed versioned receipt before claim.
- Read-triggered unpack must consume that receipt, not perform an owner-side load/join; independent POI installation remains separately admitted.
- retainData/queueSectionData enqueue PRE_UPDATE engine work; use validated lifecycle/input custody, ordered service ingress and no engine ownership transfer.
- markPosition/markPositionReplaceable write a shared fastutil table: use the ordered concurrent metadata facade, preserving previous-byte semantics.
- Frozen registries/resource inputs and structure-template dependencies must be ready under computation/service custody before entry; no deferred template I/O under a lease.
- Misplaced-data reporting and genuine recovery also reach shared error collection, I/O report submission and disk-space checks; their narrow service access needs readiness/coordination.

The artifact's report storage is already submitted to the I/O pool; direct diagnostic/shared service accesses still need the named service contract.
These are service/container/precondition requirements, not proof that `$3` fundamentally must run on the server thread.
Do not enable a bare worker-side relocation before those bindings are proven. No general Stage 4 service work is authorized here.

## 9. Explicit holder/work correlation

Choose an immutable `LoadRegistrationToken(LifecycleId, WorkId, holderReference, worldInstance, reservation)` with mutable settlement in its bound record.
Holder source is the enclosing ChunkMap.applyStep(holder, step, cache); verify reference equality against ChunkLifecycleRegistry evidence.
Work source is the actual successful acquireStatusBump -> applyStep invocation, resolved by beginOrResolveSharedWork.
For EMPTY, startedWork advances monotonically from null; demand-slot clearing does not create a second EMPTY reservation on that activation.
Allocate/reserve lineage at that actual winning call, not at each getChunkFuture, and retain it across task rescheduling/pending-unload rescue.
No identity is derived from thread, owner generation, position alone, identity hash or demand count.

At the EMPTY scheduleChunkLoad call site, a wrapper receives enclosing holder/step arguments and passes its token explicitly to a lexical load registrar.
That registrar constructs the section-3 graph with the section-7 substitutions using original read/prefetch operations and invokers for the original `$0/$2/$3/$4` bodies.
It replaces only the position-only orchestration call before any legacy graph is built; it does not copy/reimplement the mutation bodies.
This small registration helper is necessary to add an explicit parameter; an ordinary method-entry observer cannot attach it later safely.
Every retained application/recovery function captures this token in its own closure.
Bind R in an exact-future-identity index before returning; the adjacent holder registration retrieves that record using its exact receiver R.
Validate the indexed WorkId and `holder == evidence` again, attach H, then arm; the index is a locator, never authority or a coordinate lookup.
Alternatively expressing that same carrier as a lexical local during method extraction must preserve this exact token, not introduce a thread ambient binding.
Terra should use the explicit registrar plus exact-R index described here; do not grow a global current-holder API.

Reentrancy proof: A allocates token TA; every A closure captures TA before producers are launched/registrations exposed.
A nested B invocation allocates TB and registers RB independently; RA and RB are different receiver objects with different exact reservations.
Returning B cannot overwrite TA or the RA mapping; even same-position replacement has another lifecycle/holder reference.
No unwind/pop operation selects a callback's identity, so nested scheduling cannot attach A's LifecycleId to B.
Remove the exact-R index after H captures the token; retain the WorkId record/tombstone until all producer/delivery/settlement references drain.

## 10. Registration arming and terminal protocol

Create token, stage records, inner futures and terminal subscriptions **before** starting read/prefetch.
Capture each D/R/H immediately when its registration returns; mark the record ARMED only after all three and their filters are bound.
An already-ready or inline executor callback may record readiness and return its inner future before ARMED, but cannot expose owner delivery yet.
Upstream exception before arming similarly records recovery readiness. No owner invocation or mutation depends on registration unwinding on one thread.
Arming releases eligible queued work or replays a recorded terminal decision; all completions occur outside the metadata gate.
If graph construction throws synchronously before returning a future, preserve that synchronous vanilla failure channel and retire the partial binding.
Do not turn a partially registered graph into an orphan or a second fallback load; authenticated adapter failures use the explicit barrier protocol.

Keep typed Applied/VanillaFailure/Control/BarrierFailure as the primary internal channel.
For control crossing ChunkAccess-typed D/R, use a private final CustodyAborted carrying the **exact bound token**, WorkId, StageId and terminal reason.
Its constructor is closed to the adapter; validate the record's winning terminal state and token identity, not only its class/message/reason.
Permit only the raw bound marker or its documented CompletionException transport; do not traverse arbitrary cause chains.
A mismatched private marker is an explicit barrier/binding fault, never a silently accepted unload or ordinary corruption failure.
Do not recognize generic CancellationException, IOException, or any unrelated throwable as private control.

| Private disposition | Exact settlement |
| --- | --- |
| STALE_ROUTE | No inner/D/R/H completion. Keep QUEUED and same StageId/input; resolve the current route and redeliver. |
| SUPERSEDED | Only actually invalid work/lifecycle wins a terminal fence. Disarm its unclaimed stages; inner/D/R become bound-abort exceptional; H returns the canonical vanilla UNLOADED_CHUNK. |
| SHUTDOWN | Fence new registration/claims, disarm unclaimed bodies, record typed shutdown; terminalize the same registered futures and H/status subscribers without mutation. |
| DUPLICATE | Observe the original stage/outcome; do not allocate another inner or invoke another body. |
| FAILED_BARRIER | Close admission, retain/report the cause, bypass vanilla recovery/crash callbacks; exceptionally settle through the authenticated private path and separately fence pending subscribers. |

At recovery, authenticated control propagates the same transport exceptionally without invoking `$4` or creating a chunk.
At H, authenticated SUPERSEDED/SHUTDOWN converts to UNLOADED_CHUNK without invoking the original holder handler.
Otherwise that handler would call relayDelayCrash on an exception, or completeFuture on a fake success: **both filters are mandatory**.
For real failures, invoke the original applicable recovery/holder handler under its authority, preserving fatal/crash policy.
If retirement wins before recovery claim, record any earlier genuine error diagnostically but do not recover into a retired lifecycle.

Fence/settle captured demand slots first through the authorized vanilla lifecycle transition; never clear/complete a newer slot by coordinate.
failAndClearPendingFuture completes the old slot with UNLOADED then conditionally clears that exact reference; preserve its winner rules.
Do not call completeFuture on an aborted producer: it can install/fill another slot and is not cancellation settlement.
Demand-only downgrade/cancellation does not revoke valid shared work; later demand can consume its result on the same reservation.
Actual retirement must fence every dependent subscriber of that old reservation, not just the initiating EMPTY slot.
An invalidation after claim records a pending fence; the claimed synchronous body finishes once before retirement commits.

No terminal path relies on mainThreadExecutor accepting another callback after shutdown.
The lifecycle control lane first disarms all relevant original callbacks, then settles retained inner/D/R exceptionally and retained H/old status futures as authorized.
Use one pre-recorded abort transport, complete/completeExceptionally (never obtrude), and preserve futures already completed/cancelled.
If shutdown wins before arming, save terminal intent; each subsequently attached future is settled before the registration is released.
Late queued wrappers check the terminal record and perform bookkeeping only; no draining opaque JDK Runnables on the stop thread.
Stopped/rejecting executor submission is attributed at the exact bound submission and becomes private barrier/shutdown, not a generic recovered load error.
The control lane remains available until acknowledgments/references drain; no owner queue pumping, polling loop, latch or synchronous wait supplies progress.
Detached read/parse may finish later; release payloads through terminal callbacks and account for any producer still owned by vanilla I/O shutdown.
Do not promise that an arbitrarily hung external I/O task itself completes; all Phoenium-retained waiting application/result roles must settle without it.

## 11. Required thought experiments

**Two workers — PASS for the specified protocol:** X begins at A, U completes, main wrapper binds input and returns an incomplete inner future without mutating X.
Topology publishes X->B before claim; A's stale delivery fails route/generation validation without consuming the claim or invoking `$3`.
The pump delivers the identical StageId/input to B; B atomically reserves current authority/lease and claims once after service receipts are ready.
B invokes the original `$3` once, returns object V, releases its lease, and completes the inner with V; D and R receive `== V`.
Recovery function invocation count is zero. H separately admits publication; no A/main mutation, no dual lease and no wait.

**Genuine failure — PASS:** B claims and original `$3` throws E; record FAILED_VANILLA, release authority, inner.completeExceptionally(E).
D stores N(E); recovery wrapper retains that exact N(E), then its own current-owner LOAD_RECOVERY claim invokes `$4` with that same object once.
Vanilla's one-level unwrap observes E (or its original existing nesting); nonfatal recovery returns its original new ProtoChunk and R succeeds with that exact recovery object.
Fatal/recovery failure settles R exceptionally and reaches the original holder crash policy; no callback retries, and no second `$4` invocation.
If E is a Phoenium authority breach, it is a barrier instead, with no vanilla recovery.

**Stale reroute — PASS:** first A delivery returns STALE_ROUTE; no private result is completed and D/R remain pending; recovery count=0.
Same StageId goes to B, claims once, succeeds, and D/R succeed with the exact value; stale is never encoded as a throwable.

**Supersession — PASS:** L1 loses actual lifecycle/reservation validity to L2 before claim; fence L1's captured subscribers and record its bound abort.
L1's original `$3` and `$4` never run; R1 is abort-exceptional, H1/affected pending old slots unload, and scheduled old generation layers can drain/cancel.
No lookup follows L1's coordinate into L2, no chunk is fabricated, and no completeFuture invocation can publish into L2.

**Shutdown — PASS:** fence wins after READY but before CLAIMED; no body runs, even if the main callback or owner inbox is never serviced again.
Metadata terminal settlement closes retained roles as section 10 specifies; filters prevent both corruption recovery and delayed vanilla crash.
Late producers release data; already-successful/cancelled futures keep their results. A winning earlier claim instead completes its one synchronous unit.

## 12. Terra implementation plan and deterministic gates

Terra is ready to implement this bounded design, starting with tests; this review is not permission to declare the live chunk gate PASS.
1. Add the immutable reservation token, lexical load registrar, exact-R holder binding and registration/terminal arming protocol.
2. Correct invocation outcome classification; keep genuine E distinct from explicit guard/binding/barrier failures, preserving the original cause.
3. Implement the paired application/recovery flattening and immediate EMPTY holder publication/control adapter as one atomic enablement unit.
4. Bind only the required existing POI/light/metadata/readiness contracts before actual owner execution; keep unsupported paths dormant, never fall back to main authority.
5. Implement retirement/shutdown settlement and executor rejection accounting before enabling either successful or failing live loads.
6. Prove below against both registration orders; then gather narrowly scoped live evidence. Do not start FULL/readiness expansion, Stage 4 or C2ME in this proof.

Use controlled completion inputs and scripted executors; real-worker tests use separate A/B threads with asynchronous test-controller acknowledgments.
No sleep-based race assertion, owner-side join/get, synchronization latch in production, or counter-only substitute for real holder behavior.

| Test | Deterministic required assertion |
| --- | --- |
| FUT-01 success identity | Fresh-shaped and reload-shaped actual results: original return, inner, D/R and holder result payload are the same reference. |
| FUT-02 mapper genuine failure | Plain E, Error, ReportedException, CE(E), CE(CE(E)): genuine classification, exact cause/nesting; zero retry/barrier for ordinary E. |
| FUT-03 upstream failure | Fail read/parse and POI branches separately; `$3` count=0, recovery sees baseline throwable shape and identity of original cause. |
| FUT-04 stale pending | First A delivery stale: same pending StageId; inner/D/R/H not terminal; recovery/crash counts=0. |
| FUT-05 stale then success | Redeliver B, including A->A generation refresh variant; result identity, one claim/body, zero stale-owner mutation. |
| FUT-06 superseded lifecycle | Use actual old/new holders and slot transition methods; old H/status slots unload, old R aborts; L2 futures/state unchanged. |
| FUT-07 shutdown | Fence before claim, before callback, before/after arming, and with executor stopped: all retained roles terminal, zero mutation/crash/recovery. |
| FUT-08 callback once | Duplicate readiness/deliveries race with completion: one input/body/outcome, no spurious corruption or barrier for a duplicate. |
| FUT-09 completed-upstream race | U ready before registration, completion during D/R/H binding, inline executor, terminal before arm: no lost input/settlement or pre-arm owner invocation. |
| FUT-10 A->B before claim | Two actual independent worker threads, controlled topology commit and claim gate: A/main execute no body, B holds current lease for same StageId. |
| FUT-11 adjacent recovery once | Real `$4` wrapper for eligible E, including recovery returning/throwing and topology movement before recovery: exactly one invocation and baseline semantics. |
| FUT-12 no control corruption | All bound controls, mismatch marker, ordinary cancellation, buried/unrelated throwable: only exact authenticated controls bypass; no handleChunkLoadFailure for private control. |
| FUT-13 cancellation shapes | Upstream/inner cancel, mapper throws C, direct D cancel, public R/H cancel: compare stored throwables/isCancelled and predecessor non-cancellation. |
| FUT-14 reentrancy/identity | A schedules B before A unwinds; also same-coordinate L2: TA/TB, RA/RB and work reservations stay distinct; no position/thread lookup. |
| FUT-15 shared work | Actual downgrade/recreated demand slot and cancelled generation task: valid startedWork survives; superseded demand alone cannot abort EMPTY producer. |
| FUT-16 claim/terminal race | Claim wins versus shutdown/retirement wins; one synchronous body finishes or zero runs, never partial replay; recover/publication stages separately fenced. |
| FUT-17 rejection/cleanup | Reject each exact executor submission, orphan-free attach-after-terminal, late completion and ref cleanup; no recovery of infrastructure faults. |
| FUT-18 service/ordering | POI receipt precedes read; no owner wait; light/type ordering, independent publication; compare real immediate/delayed recovery/publication shapes. |

The 44 disposable JDK cases support encoding feasibility only; they are not FUT-01..18 or multiworker/holder integration acceptance.

## 13. Live evidence and stop boundary

After implementation, use the existing account-free disposable harness and pinned artifact; capture enabled hook descriptors/match counts and jar hash.
Fresh generation: genuinely absent target, original `$3` fresh branch once under current owner, exact returned ProtoChunk identity through D/R/H.
Persisted reload after clean stop: real parse branch, POI installed receipt, read/lighting/type effects and exact ImposterProtoChunk identity through D/R/H.
For each normal path record LifecycleId/WorkId/StageId, holder reference label, actual worker/context/lease, claim/invocation/return/completion sequence and zero recovery calls.
Labels are diagnostics only; identity assertions execute with references inside the adapter test, not identity-hash equality.
Genuine disposable failure: inject one IOException into the existing target read future, without damaging a region file; observe the real original recovery once and vanilla's result.
If safely binding that live injection is impractical, use the actual Minecraft recovery/holder methods in a deterministic fixture; fatal Error remains deterministic only.
Route reroute: deterministic FUT-10 with real workers is sufficient if a natural live race is impractical; do not demand a flaky timing window.
Supersession/shutdown: real-method deterministic FUT-06/07/15/16 suffice where live races are unsafe; normal live clean stop still needs terminal-reference accounting.
Live evidence must distinguish owner execution from existing diagnostic observer counters. Single-seam success does not certify unrelated Stage 3 gates.

No demonstrated architecture contradiction and none of the five requested stop conditions is established.
Future flattening solves the type mismatch; exact bound filtering supplies terminal shapes; explicit lexical tokens solve correlation.
Production/service/atomic-admission evidence remains a mandatory implementation gate, not an excuse to relax authority.
Delivery is documentation only; final HEAD remains the start HEAD unless separately committed. Commit: none.
**STOP: R-CHUNK-01 PARTIAL; Stage 3 PARTIAL; Stage 4 NOT STARTED.**
