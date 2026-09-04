# Stage 3 reload light-input ordering decision

## 1. Decision and scope

**DECISION A — LIGHT INGRESS IS A POSTCONDITION / SUB-OPERATION OF OWNER-ADMITTED PARSED_APPLY.**
Confidence: **HIGH for the architecture contract**; authoritative Minecraft binding and live acceptance remain pending.
An accepted `LightInputReceipt` is not a prerequisite for claiming PARSED_APPLY on reload.
The original synchronous `SerializableChunkData.read` submits light inputs through the coordinated service at its existing call sites.
Successful PARSED_APPLY completion consumes the actual receipt; no successful reload result escapes without it.
Pre-claim service registration/availability is required, but no `LightInputPlanReceipt` or second preparation-receipt protocol is introduced.
The accepted architecture already calls these operations nested LOAD_APPLY subactions (the implemented semantic name is PARSED_APPLY).
**thenComposeAsync Decision A: UNCHANGED. Architecture contradiction: NO.** The metadata correction is also unchanged.
This is a docs-only decision. Do not install composition, the holder registrar, FULL, ticking, sending, C2ME or ScalableLux integration here.
R-CHUNK-01=PARTIAL; Stage 3=PARTIAL; Stage 4=NOT STARTED; workers=1; parallel gameplay=OFF.

## 2. Verified checkpoint and evidence

Start HEAD: `78334954078ecb8cb190204b9e8cd2a6f235c5dc`; `git status` CLEAN; expected fifteen-commit history; `git diff --check` clean.
Metadata implementation: `4065883746456ce84e550689a438b9a4b64e5801`; light-order evidence: the start HEAD above.
Re-executed `./gradlew.bat test --rerun-tasks --offline`: **134 PASS, 0 failures/errors/skips**, counted from JUnit XML.
Read the ownership, future, prerequisite, metadata, implementation and handoff documents and the nine requested service/admission classes.
Also inspected the existing ordering witness, invocation adapter and custody fences to distinguish approved contracts from current unbound code.
Exact Minecraft 26.2 artifact: `C:/Users/Admin/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
Rehashed SHA-256: `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4` (matches).
Fresh `javap -c -p` inspection was limited to read, its application caller, light ingress/storage/dispatch, and the POI dependency discussed below.

| Exact artifact evidence | Contract consequence |
| --- | --- |
| `ChunkMap.lambda$scheduleChunkLoad$3`: read at 38, markPosition at 51 | Reconstruct/submit first; ordered metadata mutation follows read, before the application result returns. |
| `SerializableChunkData.read`: section-array assignment at 171, POI consistency at 180 | These are conditional per-section actions before that section's light calls. |
| Same read: retain at 238, BLOCK at 261, SKY at 281, loop back at 284 | One retain before the first applicable input; BLOCK then SKY within each serialized section occurrence. |
| Same read: LevelChunk constructor at 379 / ProtoChunk constructor at 437, later returns at 703/827 | Light ingress precedes chunk construction and substantial remaining application work; receipt alone cannot prove application success. |
| `ThreadedLevelLightEngine.retainData` / `queueSectionData`: addTask at 34/35 | Both submit PRE_UPDATE work with priority supplier 0; they return without waiting for light processing. |
| `addTask` -> `ChunkTaskDispatcher.submit` -> `PriorityConsecutiveExecutor.schedule` | The physical acceptance boundary is the existing service mailbox, not execution of the later storage mutation. |
| `StrictQueue.FixedPriorityQueue` uses concurrent queues; `AbstractConsecutiveExecutor.schedule` pushes before scheduling | A short synchronous submission is possible; queue acceptance does not prove that dispatch or computation has finished. |
| `LayerLightSectionStorage.retainData`, `queueSectionData`, `markNewInconsistencies` | Set membership and replaceable queued layers have no logical input deduplication token. |

**Dependency cycle confirmed: YES.** Current `LoadApplyPrerequisites.evaluate` asks `light.receipt(batch)` before claim;
the actual input needed for that receipt is submitted by read inside the still-unclaimed stage. A registered nonempty batch cannot open that gate.
The existing two passing ordering witnesses establish this defect; they are not evidence of implemented authoritative acceptance.
The exact order is a loop: POI(section 0), retain if needed, BLOCK/SKY(section 0), POI(section 1), BLOCK/SKY(section 1), and so on.
It is not an all-POI phase followed by an all-BLOCK phase followed by an all-SKY phase.

## 3. Registration, receipt and successful-completion contract

Use the existing `LightInputService.BatchId` and its registered batch as the pre-ingress concept.
Add a non-blocking registration-readiness query, proposed `registeredForParsedApply(batch, stage)`, distinct from `receipt(batch)`.
Registration binds the actual world/light-service instance, LifecycleId, WorkId, exact PARSED_APPLY StageId and its retained load input.
It records the required retain flag and ordered scalar section/layer/occurrence identities; it means the endpoint is prepared to accept this stage's calls.
Registration creates no engine side effects, accepted-input bits or authority, and is not a receipt proving prepared/completed work.
This refines the existing declaration ledger; it does not add B's independent plan producer and plan-receipt dependency.

Derive the declared shape from the already exclusively retained `sectionData`, requested target position and stable dimension `hasSkyLight`.
BLOCK is required exactly when that entry's `blockLight` is non-null; SKY exactly when skylight is enabled and its `skyLight` is non-null.
Retain(true) is required once iff any such input exists. Light-only entries still count even when `chunkSection` is null.
Preserve list order and distinguish repeated section coordinates with the occurrence/input sequence; never collapse legitimate vanilla calls into a set entry.
The existing `SectionInput.inputSequence` can distinguish occurrences; the batch also needs an ordered cursor, not just unordered membership.
Keep the same original DataLayer references at each call. Resolve them through the retained input; do not copy payloads or treat them as deeply immutable.
Do not add early reconstruction, POI reconciliation, light computation, or data validation that moves a genuine read failure before its vanilla side effects.

Before claim, original combined upstream readiness, current lifecycle/work, POI installation, metadata readiness and light registration must all hold.
Claim atomically pins this synchronous transaction and its prepared ingress entitlement against shutdown/retirement, using the existing admission fence.
Inside read, each exact retain/BLOCK/SKY call passes through the typed service adapter, which invokes the original virtual ingress operation once.
The adapter must intercept the existing call, not enqueue an additional copy and then allow the original call to run again.
Do not intercept the later PRE_UPDATE superclass call as a second independent producer submission.
The service preserves original argument identity, call order, priority, PRE_UPDATE/propagation/POST_UPDATE sequencing and exception behavior.

`LightInputReceipt(batch)` means **every required original ingress occurrence of that exact declared batch was accepted**.
Record each acceptance only after its real mailbox insertion is established and the synchronous ingress call returns normally.
Produce the receipt immediately after the last required call returns and the ordered batch is complete; never on registration or method entry.
For a genuinely zero-input reload, close the declared empty batch on normal read return; no retain/BLOCK/SKY invocation is invented.
No receipt certifies propagation, dispatch completion, light-correct flags, callbacks, holder readiness, or success of later read/metadata work.
Receipt validation consults the service's authenticated batch state and exact binding; constructing the current public record alone proves nothing.

**First consumer: PARSED_APPLY successful-completion gate**, after the original `$3` returns, before APPLIED/inner success is exposed.
For the reload-success branch require the actual batch receipt and the original returned object, then release the lease/context before completing futures.
A missing receipt after a normal nonempty reload return is an impossible adapter state: FAILED_BARRIER, not NOT_READY or a wait after partial mutation.
`STATUS_PUBLISH(EMPTY)` is the next separately admitted stage; it validates the carried reload-success evidence and lineage before holder publication.
It cannot publish that reload result without the receipt validated by completion; it never resubmits inputs or waits for propagation.
Keep the evidence until that consumer settles. Existing later lighting-completion prerequisites remain separate and unchanged.
No new `ChunkStageKind` is needed: PARSED_APPLY and STATUS_PUBLISH suffice; LIGHT_INPUT already exists for independent service handoffs.
Do not create a separately queued reload LIGHT_INPUT stage, or add LIGHT_INPUT_ACCEPTED, between synchronous read subactions.

## 4. Lease, service authority and fencing

**Safe: YES** — owner lease -> short retain/queue submission -> call returns -> acceptance recorded -> PARSED_APPLY later exits/releases its lease.
**Lease retained across asynchronous lighting: NO.** The service does not acquire, grant, capture or retain a RegionLease.
It transfers input custody into an enqueue-only endpoint; engine storage/computation remains service-owned, with regional callbacks separately admitted.
No owner waits for queue draining, service responses, lighting futures, propagation, I/O, another owner, or a prerequisite that becomes unavailable mid-read.
Any required service capacity/readiness is established before claim; no caller-runs propagation or synchronous callback execution under the owner is allowed.
Short identity/cursor updates use the existing coordinated metadata protocol; original Minecraft calls and future completions run outside its lock.
An attempt reservation plus the pinned enclosing claim closes the check-to-submit gap without holding a metadata lock over vanilla code.

Validate world/session/instance/target, exact work reservation, stage and service input version, not coordinate, thread or newest work sequence alone.
Record topology/owner validation at claim. A later topology request does not transfer authority in the middle of read.
The claim belongs to the frozen authority transaction; it finishes once, including remaining light submissions, before the affected topology can commit.
An unrelated generation publication or A->A generation change does not invalidate it. Subsequent stages resolve the current route independently.
An old lifecycle/work rejected before claim submits nothing; never relabel its batch into a newer lifecycle or recover private control as vanilla corruption.
Demand-only cancellation, another valid status WorkId, and pending-unload rescue do not revoke valid shared work.

The service carries only immutable lineage/version/operation identifiers into asynchronous commands, never an ambient ownership context or owner closure.
Serialize old-command processing/disposal and new-lifecycle inputs in the service's input-version order; validate again before service publication.
An old command cannot reinstall layers or publish notifications over a newer lifecycle; cleanup targets its version, never an unqualified coordinate.
Closing new admissions cannot clear a batch or close its engine while its already-claimed transaction is still submitting.
Pinning is existing synchronous stage entitlement, not a second authority system or a lease lasting until engine completion.

## 5. Partial ingress, duplicates and genuine failure

Required per-occurrence progression: `UNATTEMPTED -> INVOKING -> ACCEPTED`, or `INVOKING -> FAILED_VANILLA/FAILED_BARRIER`.
Reserve INVOKING before crossing the physical call boundary, then release the metadata gate. An attempted occurrence is never reset.
The batch records accepted prefix, failed/possibly-enqueued occurrence, and unattempted suffix as small typed state, not a partial LightInputReceipt.
Duplicate readiness/delivery observes the original claim/outcome and performs zero calls; it is normal DUPLICATE bookkeeping.
A second physical invocation attempt through the adapter for the same occurrence is an invariant breach, blocked before the call.
Unexpected/reordered input or payload binding, a forged receipt, and re-registering a terminal batch are also barriers.
Authenticate issuance against the bound stage and keep terminal state until all registered deliveries settle; forgetting a map entry cannot permit resurrection.

For retain success + BLOCK success + SKY throwing E: retain/BLOCK stay ACCEPTED, SKY is failed/possibly enqueued, suffix stays unattempted.
**LightInputReceipt exists: NO. PARSED_APPLY retry permitted: NO. Already-submitted ingress may be submitted again: NO.**
Do not retry even if the throwing method may have enqueued before throwing; reserve-before-call records that uncertainty without replaying it.
Do not complete the missing suffix, roll back earlier effects, replay read's POI prefix, or issue compensating retain(false)/null-layer commands.
Already accepted commands remain accounted in their existing vanilla service order; genuine failure does not itself revoke their work lineage.
Ordinary read/light data or application exception E is FAILED_VANILLA, including when earlier side effects succeeded.
Preserve E by reference and existing CompletionException transport into the original, separately admitted load-recovery edge; no retry or extra wrapping.
If genuine recovery returns its normal new ProtoChunk, its typed recovery provenance permits the recovery-success/EMPTY publication path.
That is not a successful reload or a receipt for the failed batch. Never gate genuine recovery on the failed reload receipt.
Fatal errors and recovery failures retain vanilla's original policy; private barrier/shutdown/supersession controls never invoke recovery.
If all inputs were accepted but later reconstruction/metadata fails, the receipt remains an ingress fact; the failed reload still cannot publish success.

| Event | Exact classification / settlement |
| --- | --- |
| Ordinary Minecraft/light exception during the call | FAILED_VANILLA; preserve accepted prefix and original error/recovery; no whole-body retry. |
| Later PRE_UPDATE/propagation exception | Genuine service/vanilla failure through its existing error path; ingress acceptance is not retroactively computation success. |
| Normal obsolete delivery or fence winning before claim | Authenticated SUPERSEDED/SHUTDOWN control, zero invocation; STALE_ROUTE alone reroutes the same unclaimed stage. |
| Wrong lifecycle/WorkId/world token used to attempt ingress; foreign/unleased mutation | FAILED_BARRIER before mutation; normal rejected obsolete delivery is distinct from attempting unauthorized physical work. |
| Duplicate physical invocation, changed payload/order, impossible receipt state | FAILED_BARRIER; close admission and use the private bound settlement path, zero vanilla recovery. |
| Unexpected adapter/dispatcher rejection or lost acknowledgment | FAILED_BARRIER, or authenticated SHUTDOWN if its fence owns settlement; never a fabricated vanilla data error or replay permission. |

Minecraft's `AbstractConsecutiveExecutor` can push a task and then log a scheduling rejection without throwing to its caller.
Therefore a plain method-return counter is insufficient: the adapter must witness insertion and account for rejection/terminal disposal at that bound dispatch.
It must never requeue the same ingress to repair a scheduling failure. This is the existing future decision's rejection-accounting requirement.
Service progress failure is separate from input acceptance; LIO-12/13 must exercise the push-before-failure case.

| Operation | Duplicate same logical payload: safe / unsafe / unknown | Evidence and required rule |
| --- | --- | --- |
| retainData(true) | UNSAFE as an unconditional retry contract | An adjacent isolated set-add is idempotent, but another retain(false)/retirement can intervene; replay can re-enable retention. No replay. |
| queueSectionData(BLOCK) | UNSAFE | put replaces queued data and sets inconsistencies; consumption removes the entry and can install/change a layer. Replay can overwrite newer queued/processed data. |
| queueSectionData(SKY) | UNSAFE | Same storage protocol and interleaving risk as BLOCK; no input-version deduplication exists in vanilla. |

These classifications concern allowed asynchronous/lifecycle schedules, not a claim that two adjacent isolated puts always differ.
`markNewInconsistencies` installs queued references then removes them; `getDataLayerToWrite` can copy/change the updating layer.
Vanilla has no intrinsic exactly-once API. Phoenium guarantees at most one attempt per original occurrence and exactly one acceptance on successful reload.
Two distinct original occurrences, even for the same section/layer, are preserved separately; duplicate delivery of either occurrence is suppressed.

## 6. Required state table

There is no separate plan. R denotes the existing registered batch/declaration; A is the accepted prefix; E is the declared ordered input sequence.
The retain/BLOCK/SKY rows illustrate one applicable section; later sections can remain outstanding. Absent layers never become invented requirements.
Later eligibility below means reload-success publication; all original non-light prerequisites and separate current-owner admission still apply.

| State | Plan / registration state | Receipt state | PARSED_APPLY eligibility | Later-stage eligibility | Cleanup requirement |
| --- | --- | --- | --- | --- | --- |
| FRESH generation | No reload R/plan | N/A | Original upstream + current lifecycle/work + metadata | After successful fresh application | Release ordinary stage references. |
| RELOAD before PARSED_APPLY | R ready, A empty; no plan receipt | Absent | READY with installed POI, metadata, upstream and service readiness | No | Keep R while pending; fence/settle if cancelled. |
| RELOAD during PARSED_APPLY before retain | R pinned to CLAIMED transaction | Absent | Already CLAIMED; no re-claim | No | Keep R until synchronous exit. |
| After retainData | Retain ACCEPTED; section prefix empty | Absent for nonempty E | Same CLAIMED transaction continues | No | Preserve attempt/acceptance history. |
| After BLOCK input | That occurrence ACCEPTED | Absent unless it was last required input | Same transaction continues | No, even if ingress is complete | Keep evidence; do not replay BLOCK. |
| After SKY input | That occurrence ACCEPTED | Present iff all E accepted | Same transaction continues | No until application success | Keep evidence; do not replay SKY. |
| After all required ingress accepted | R complete, A=E, required retain accepted | Actual receipt present | Still executing remaining read/metadata | No until success gate | Release transferred payload ownership; keep scalar evidence. |
| After PARSED_APPLY returns normally | R complete; completion gate validates it | Present, carried with reload success | APPLIED once; no retry | STATUS_PUBLISH(EMPTY) may claim if current | Release input; retain receipt evidence through consumer settlement. |
| Shutdown before ingress, fence before claim | R terminal/disarmed | Absent | SHUTDOWN; no body | No | Release unsubmitted input and settle all retained future roles. |
| Supersession before ingress, fence before claim | R terminal/disarmed | Absent | SUPERSEDED; no body | No | Dispose old R; never alter replacement lifecycle. |
| Shutdown/supersession after claim but before first call | R remains pinned; fence pending | Absent, may become complete | Winning transaction finishes once | New publication is fenced | Delay batch destruction/engine close until synchronous exit. |
| Failure halfway through ingress | R terminal partial, attempted prefix retained | Absent; typed partial state only | FAILED_VANILLA or explicit barrier; no retry | No reload success; genuine recovery may separately claim | Account accepted commands; release suffix/payload refs; no replay/rollback. |
| Genuine zero-input RELOAD | R declares no retain or sections | Issued on normal read return | Normal reload preconditions still apply | After completion gate | Small R/evidence released normally; zero physical calls. |

Shutdown after partial ingress cannot cancel a winning synchronous transaction mid-body: it finishes or throws, then no new stage starts.
The control lane disarms callbacks and settles retained roles without waiting under an owner; queued service payloads drain or receive versioned disposal accounting.
Do not immediately clear ledgers while an invocation or acknowledgment can still arrive; do not execute vanilla continuations on the stop caller.
Expected after terminal acknowledgments/consumer settlement: zero retained registration/receipt/payload/lease references from this protocol.
Actual live leak counts are NOT MEASURED in this docs-only pass.

## 7. POI, metadata and exact prerequisite amendment

**POI CONTRACT ALREADY NON-CYCLIC. Same circularity: NO.** `PoiInstallReceipt` proves packed POI storage installation, including known-empty sections.
`SectionStorage.unpackChunk(pos, PackedChunk)` installs section records/empty entries without needing reconstructed chunk block data.
The independent prefetch/drain branch can supply this installation before read; read-triggered unpack and background drain share one POI_INSTALL identity.
`PoiManager.checkConsistencyWithBlocks` first calls getOrLoad, then refreshes an existing section or populates one from blocks.
Those block-consistency mutations are nested PARSED_APPLY work, not part of the receipt required before entry.
The installed receipt must cover every relevant getOrLoad section; no owner-side unpack join, no early loaded flag and no second installation.
The bridge name `installOrReconcile` does not make consistency an installation-receipt prerequisite; bindings must preserve the documented distinction.
LIO-01 includes real installed/known-empty POI cases and proves consistency can still run after admission. General POI architecture is unchanged.

`LoadApplyPrerequisites.Required.fresh()` stays lifecycle/work + metadata, with the original upstream future graph unchanged.
`Required.reload(poi, lightBatch)` keeps those plus the valid installed POI receipt and **prepared registration/availability for lightBatch**, not light.receipt.
Keep the existing factory arguments if useful; change only the light requirement's meaning and readiness query, with exact stage binding checked at claim.
Missing prepared registration is NOT_READY; wrong lineage is STALE; closed admission is SHUTDOWN; a terminal prerequisite failure must settle explicitly, never park forever.
READY is a non-blocking observation, not authority. The claim rechecks prerequisites and pins service entitlement under the existing fence.
Resource/template/error-report prerequisites already required by the future decision remain required; this correction removes none of them.
Metadata precondition remains before claim; the actual reload markPosition remains after read at `$3` offset 51.
Fresh/recovery markPositionReplaceable and current authorized FULL->REPLACEABLE semantics remain exactly as corrected at `4065883`.

## 8. Alternatives, decision matrix and stop conditions

B is feasible but unnecessary: the parsed record exposes section coordinates/layer references, and stable dimension context supplies skylight eligibility.
It is not deeply immutable: records contain mutable lists, DataLayers, sections and NBT. A safe plan would need the same exclusive-input custody as A.
B could make an immutable descriptor plan/receipt without engine effects, then submit once inside read; it provides no missing authority or dependency here.
A already needs a small existing batch declaration to define actual receipt completeness; making that declaration a second completed-work receipt adds no benefit.
C is rejected: moving ingress before read crosses per-section array assignment/POI consistency and their possible failures.
Even identical payload references and suppressing original calls cannot prove identical observed ordering or effects when a later POI section fails.
D is rejected: the proposed broad prepare/POI/light/finalize phases do not match the interleaved loop; a resumable split must preserve locals and failure prefixes.
No existing clean authoritative split was established; copying/reimplementing the 828-byte read body is unnecessary when same-site interception suffices.

PASS below means the specified design satisfies the property, not an already-passing production test; compatibility remains conditional on future adapters.

| Property | A Postcondition | B Plan + Receipt | C Extract ingress | D Split read |
| --- | --- | --- | --- | --- |
| Breaks dependency cycle | PASS | PASS | PASS | PASS |
| Preserves original call ordering | PASS | PASS | FAIL | CONDITIONAL |
| No duplicate physical ingress | PASS | PASS | CONDITIONAL | CONDITIONAL |
| No owner blocking | PASS | PASS | PASS | CONDITIONAL |
| No lease across async lighting | PASS | PASS | PASS | PASS |
| Keeps LightInputReceipt meaning | PASS | PASS | PASS | PASS |
| Failure semantics preservable | PASS | PASS | FAIL | CONDITIONAL |
| C2ME compatible later | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL |
| ScalableLux compatible later | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL |
| Low implementation complexity | PASS | CONDITIONAL | FAIL | FAIL |
| Low maintenance burden | PASS | CONDITIONAL | FAIL | FAIL |

E is not selected: (1) ingress is inside read, not fundamentally required before it; (2) interception preserves its semantic point;
(3) input acceptance needs no computation wait; (4) partial failure settles without retry; (5) no read reimplementation is needed;
(6) pinned lineage/versioned service submission supports fencing; (7) POI installation is independently available and non-cyclic.
These are contract conclusions. Failure to implement an enqueue-only, exactly-once, fenced adapter keeps that binding disabled, not permission to weaken them.

## 9. Future producers and retention cost

C2ME/ScalableLux compatibility is a design inference, not tested integration: producers prepare data under exclusive/snapshot custody elsewhere;
Phoenium validates lifecycle/work; coordinated lighting accepts inputs; regional mutations require separate owner admission. No producer becomes owner.
No identity or authority depends on vanilla's worker thread, server thread, engine implementation name, or executor scheduling speed.
Allocate only on demand: reuse BatchId/SectionInput metadata, add an ordered cursor, attempt states and a small terminal/receipt witness.
Cost is O(actual input occurrences), with no full serialized-chunk copy, extra DataLayer copy, persistent planning ledger or per-world section preallocation.
The existing load binding retains serialized input only for its original application lifetime; the service must not add another full-chunk retention root.
After submission, engine ownership may retain required DataLayers as vanilla does; Phoenium drops its payload aliases and later its scalar evidence.
Discard terminal tombstones after registered deliveries/service acknowledgments and the EMPTY consumer settle; disarmed terminal stages prevent re-registration afterward.
This bounded lifetime is suitable for Android/low-RAM targets; no Android runtime/performance evidence is claimed here.

## 10. Terra deterministic tests (planned, not executed)

Use controlled executors/acknowledgments and the actual read/ingress methods where side effects or throwable semantics matter; no sleep-based race proof.
Preserve the bytecode witness; replace the old cyclic-ledger expectation when implementing the amended contract, not in this docs-only commit.

| Test | Required assertion |
| --- | --- |
| LIO-01 | Reload with upstream/POI/metadata/registration ready claims while actual receipt is absent; real consistency then runs. Include installed/known-empty POI, multi-section order, no-sky, light-only, repeated coordinates and genuine zero-input cases. |
| LIO-02 | Fresh branch remains eligible without reload registration/receipt, returns its exact vanilla object and preserves metadata behavior and original prefetch dependency. |
| LIO-03 | Real retain(true) is attempted once before first applicable input, never for a zero-input load; distinguish original call from its later PRE_UPDATE superclass action. |
| LIO-04 | Every required BLOCK occurrence submits once with the identical original DataLayer and requested section; no declaration-time submission or bypass. |
| LIO-05 | Every required SKY occurrence submits once after that section's BLOCK when present; absent/disabled SKY submits zero times. |
| LIO-06 | Only all required successful call acceptances produce an authenticated receipt for the exact batch; forged, mismatched and premature receipts fail. |
| LIO-07 | Hold engine processing pending after mailbox insertion: receipt and successful PARSED_APPLY may complete; propagation/flags/notifications remain incomplete, with zero retained lease. |
| LIO-08 | Duplicate readiness/delivery before/during/after claim observes one outcome and zero extra physical calls; attempted repeated physical input is a barrier before invocation, including after terminal cleanup. |
| LIO-09 | Replace lifecycle before claim: old batch submits zero inputs, private supersession settles; an explicit wrong-lifecycle physical attempt is a barrier and cannot affect replacement data. |
| LIO-10 | Supersede the actual WorkId before claim while lifecycle remains: zero ingress. Another valid status/demand-only cancellation must preserve valid shared work. |
| LIO-11 | Request A->B topology change during claimed read and vary unrelated/A->A generation changes: frozen transaction finishes once, no transfer/replay; subsequent publication revalidates its route. |
| LIO-12 | retain/BLOCK succeed, SKY throws exact E; also throw after queue insertion and after complete ingress during later read/metadata. No replay, correct receipt presence, genuine cause/recovery identity, zero barrier for ordinary E. |
| LIO-13 | Shutdown before claim/ingress, after claim before ingress, and after a prefix: zero losing-claim calls, winning synchronous unit finishes, terminal dispatch rejection accounted without resubmission or owner wait. |
| LIO-14 | Supersession before ingress versus after claim: fence winner invokes nothing; claim winner finishes once, old publication fenced, no newer-lifecycle layer overwrite or broad cleanup. |
| LIO-15 | Success, partial failure, supersession, shutdown and late delivery release payloads, batch/receipt evidence and futures after consumers/acknowledgments; no retained lease or tombstone resurrection. |
| LIO-16 | Normal reload cannot pass PARSED_APPLY completion or STATUS_PUBLISH(EMPTY) without its actual receipt, even if light-correct/compute flags say ready; fresh and genuine recovery keep their distinct valid paths. |

## 11. Terra next implementation and live proof

**TERRA READY: YES**, for this bounded prerequisite/ingress correction; no current authoritative binding is accepted.
First implement registration readiness, ordered attempt/acceptance/terminal states, completion evidence and LIO-01..16.
Integrate the existing claim pin with service closure/cleanup; correct the already-documented invocation adapter's blanket barrier treatment of genuine failures.
This includes typed CLAIMED->FAILED_VANILLA settlement: the current custody model's `failVanilla` rejects an already-claimed stage.
Current retain/section methods are ledger acknowledgments, not engine invocations; the unordered batch and immediate shutdown clear are insufficient production adapters.
Then resume the already-approved atomic application/recovery/immediate EMPTY publication binding with exact call-site wrappers; retain FUT-01..18 gates.
Do not install an isolated composition change or a light hook that leaves a direct duplicate ingress path.

Next live proof: use a disposable persisted world after clean stop, with stored BLOCK and SKY payloads, in the existing headless dedicated-server harness.
Load a retained target through the real `SerializableChunkData.read`, recording exact lifecycle/work/stage, frozen lease and section/occurrence sequence.
Trace actual retain call/return, each exact BLOCK submission, each exact SKY submission, mailbox acceptance and the resulting service receipt.
Verify original payload reference identity inside the test adapter, original per-section POI/light order, one intercepted physical call and zero duplicate/direct bypass calls.
Verify PARSED_APPLY returns its original reconstructed object and passes completion with that receipt; separately observe EMPTY publication consuming that evidence.
Prove no owner wait/lease survives input submission, preserve original genuine recovery behavior with deterministic real-method fixtures, and stop cleanly.
Report terminal input/receipt/reference accounting. No GUI/manual client is necessary for this reload/EMPTY proof; no new FULL/ticking/send acceptance is sought.
No new live run occurred here. LIO and FUT binding acceptance remain pending; the 134 passing baseline tests do not certify them.
**STOP. R-CHUNK-01 PARTIAL; Stage 3 PARTIAL; Stage 4 NOT STARTED.**
