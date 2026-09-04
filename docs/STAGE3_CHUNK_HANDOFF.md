# Stage 3 chunk-only handoff

## Current FULL callback decision — 2026-09-04

**Cleanup defect: FIXED**, dedicated commit
`a99d647c1ed39b1a96d5267e6f08f06ba17d8945`. **Scoreboard decision: accepted
amendment, not automatically implemented. FULL callback closure: INCOMPLETE.
Production FULL binding: NOT ENABLED. Integrated FULL implementation ready: NO.**

The [current FULL review](STAGE3_FULL_CONVERSION_REVIEW.md) now records the pinned
vanilla/installed-Fabric dispatch, named world-registry and connection-ordering
additions, and the combined preclaim order: connection/session directory,
scoreboard, named world turns, then one converged region. Logical reservations
are distinct from short state critical sections; no state lock spans callbacks
or completion. Original synchronous sends, returns and partial failures remain
observable. Nine additional real-method semantic witnesses cover the specific
review edges; they do not establish a production FULL binding or live FULL
acceptance.

Final verification: `gradlew.bat clean build compileClientGameTestJava --no-daemon`
is BUILD SUCCESSFUL, **221 tests, 0 failures/errors/skips** (206 baseline, 212 at
the cleanup commit, plus nine callback witnesses). The clean-built production
JAR still hashes to `347E1EB30F078CBFF4CBB969244AED6E0324A5C0486DA61B894566D46210F796`,
identical to the accepted isolated live probe. This review changes only tests
and these existing documents; no additional live FULL claim is made. Final
build/evidence manifest: `run/full-callback-closure/final-build.log` and
`run/full-callback-closure/final-evidence.json`. Preserved normal final-artifact
runtime captures are in `run/terminal-cleanup/normal-final/`.

**Next task: resolve D1–D3 in that review before integrated FULL implementation.**
D1 needs exact authority/lifetime for synchronous cross-dimension live entity
references. D2 needs approved timing/failure semantics for legacy owner-name
lookup and synchronous cache persistence inside loading. D3 needs an enforced
ready-input invariant, precise in-progress chunk access, or an atomicity decision
for conversion during Tadpole loading and its potentially self-dependent FULL
query. These branches remain explicit gaps; no behavior is disabled to claim
closure. The accepted scoreboard contract is unchanged and no isolated service
scaffold was added. Server-only; workers=1; parallel gameplay=OFF;
R-CHUNK-01/Stage 3=PARTIAL; Stage 4=NOT STARTED.

## Terminal cleanup correction — 2026-09-04

**Cleanup defect: FIXED.** Starting HEAD `8c0072ac559bf53dee5672ee97c29f76020834c8`
was clean; the rebuilt baseline was 206/206. The corrected build is **212/212**.
The original 34-cancellation reproduction now requires **0** orphan records,
and eight batches of 34 cancellations on one service root leave **0** after
each settlement. Historical 2/68 assertions remain identified in the test
comment and in the committed scoreboard decision; the original logs are intact.

`ChunkLoadGraph.abort` retires its existing WorkId under custody before exposing
terminal completion. It preserves holder evidence and already-winning claims.
`disarmLocked` and `barrierLocked` cannot recreate absent records without active
work entitlement; late `bindInput` cannot retain a retired payload. Final
reclamation waits for the claim map, including APPLIED-before-claim-exit, rather
than treating a terminal stage-state value as proof that its lease has exited.
No new lifecycle authority, permanent tombstone, or lock across completion was
introduced. Demand cancellation still does not retire shared work.

The reproduced sequence was: `ChunkStageDeliveryPump` missing-route control →
`PendingVanillaStage.control(CANCELLED)` completes its outcome → `finishPoi`
enters `ChunkLoadGraph.abort` → outer abort controls parsed apply → its inline
completion handler reenters settlement → terminal future completion and
`cleanupIfSettled` → `Environment.settled` →
`ChunkPrerequisiteServices.releaseFinishedWork` reclaim the lifecycle/work →
outer abort resumes its LOAD_RECOVERY and STATUS_PUBLISH controls → old
`disarmLocked` creates two absent stage records. The corrected abort publishes
custody retirement before its first outward completion, and the later controls
cannot recreate authoritative metadata for that retired WorkId.

Deterministic coverage includes inline/reentrant completion, handler registration
on completed futures, duplicate abort, late old tokens, same-coordinate new
lifecycles, unrelated active work, claimed reload/light completion and all
required future outcomes. Missing-route classification remains CANCELLED.

Live equivalent: after `gradlew.bat prepareStage3ClientServer`, run
`python scripts/test_missing_route_cleanup.py`. It copies the production server
into a new ignored directory and uses the unchanged finite fixture with an EMPTY
request at (1000,1000). With the explicit test-only diagnostics flag this yields
route=UNRESOLVED, one cancellation, the actual unloaded result, no parsed or
recovery invocation for that request, zero retention counters and clean stop.
Accepted evidence: `run/terminal-cleanup/missing-route-20260904-155234/acceptance.json`.
JAR SHA-256: `347E1EB30F078CBFF4CBB969244AED6E0324A5C0486DA61B894566D46210F796`.

An earlier `/forceload` probe reached cancellations but waited for unbound FULL;
its watchdog/failed shutdown is preserved in `missing-route-20260904-154315`.
It is not accepted evidence. The direct EMPTY probe does not enlarge topology.
Normal fresh/fresh/reload and account-free real-client regression passed on the
final artifact. One client attempt overshot the harness's same-owner movement
window; its JSON/log are preserved in `run/terminal-cleanup/client-movement-failed`,
and the unchanged harness passed on rerun. This is separate from invalid-demand
acceptance. Logs: `run/scoreboard-service-review/cleanup-{build,live-regression,client-rerun}.log`.

Scoreboard decision remains the accepted amendment, not automatically
implemented. FULL callback closure is INCOMPLETE for D1–D3 in the current review;
production FULL is **NOT ENABLED**. Workers=1, parallel gameplay=OFF;
R-CHUNK-01/Stage 3=PARTIAL; Stage 4=NOT STARTED.

## Historical scoreboard review at 8c0072a

> **Current review decision, 2026-09-04: AMENDED.** Read
> [STAGE3_FULL_SCOREBOARD_SERVICE_DECISION.md](STAGE3_FULL_SCOREBOARD_SERVICE_DECISION.md)
> before another FULL implementation. The existing scoreboard has one explicit
> coordinated authority; reserve its operation turn before the regional claim,
> update membership synchronously, and preserve the original ordered send
> submissions and failure prefixes outside state critical sections. All
> conflicting team readers/writers, dirty/save access and login initialization
> participate. Connection snapshots must bind exact sessions to the existing
> connection lifecycle; the current entity projection does not supply this.
>
> Repairs/evidence were preserved first in `4d4e804`; see
> [preservation record](STAGE3_FULL_REVIEW_PRESERVATION.md). Rebuilt baseline:
> 203 PASS. With two additional vanilla semantic witnesses and one deterministic
> defect reproduction: **206 PASS**, BUILD SUCCESSFUL. Production artifact hash
> is unchanged. Historical live evidence is preserved, not newly rerun.
>
> **Missing-route retention: OPEN WITH REPRODUCTION.** A synchronous terminal
> callback can reclaim custody before the outer abort loop finishes; two later
> controls recreate LOAD_RECOVERY/STATUS_PUBLISH stage records. The new test
> reproduces 2 entries per cancellation / 68 for 34, matching the original log.
> Its assertions characterize the defect; no cleanup fix is claimed. Missing
> coverage is not supersession or an indefinite topology wait.
>
> **Exact next task:** close the named synchronous waypoint, tracking/pairing,
> debug and concrete entity/block-entity callback footprint gaps in decision
> section 6; repair and rerun terminal cleanup with zero-retention assertions;
> then implement the approved service together with the actual FULL supplier,
> holder/work lifetime extension and separate FULL publication. No disconnected
> service scaffolding. Complete FULL implementation readiness is **NO** until
> those specific callback authority prerequisites are closed. FULL authority is
> **NOT ENABLED**. Server-only, workers=1, parallel gameplay=OFF;
> R-CHUNK-01/Stage 3=PARTIAL; Stage 4=NOT STARTED.

> **2026-09-04 follow-up:** FULL conversion/publication remains unbound following
> a reproduced synchronous scoreboard/foreign-dimension dispatch ownership
> question. The pending service-contract proposal, POI race repair, fixture
> fixes, final 203-test result, five passing server lifecycles and artifact hash
> are in [STAGE3_FULL_CONVERSION_REVIEW.md](STAGE3_FULL_CONVERSION_REVIEW.md).
> This is not a completed FULL checkpoint. The accepted load checkpoint below
> remains historical context; the new report distinguishes final regression
> evidence from the unresolved FULL decision.

> **Latest implementation checkpoint:** Part A is independently committed with
> 178 tests. Part B now binds the real Minecraft 26.2 EMPTY load registrar,
> original `$3` parsed apply, `$4` recovery, POI installation, read-time light
> ingress, controlled metadata writes, the exact R/holder edge, and a separate
> EMPTY publication stage. FUT-01..18 and the complete 196-test suite pass.
> A production Fabric server completed two clean fresh worlds with distinct
> fixed seeds and a final saved-world reload. Each fresh run completed 1,258
> exact EMPTY graphs; the reload completed 49 exact graphs and observed 41
> RETAIN, 29 BLOCK and 36 SKY batches in original read order. All three shutdowns
> reported every chunk-binding and prerequisite-service retention counter at
> zero, with no recovery, private control, duplicate, fallback, unleased access,
> or barrier. See
> [STAGE3_LIGHT_INGRESS_IMPLEMENTATION.md](STAGE3_LIGHT_INGRESS_IMPLEMENTATION.md)
> and [STAGE3_PARSED_APPLY_IMPLEMENTATION.md](STAGE3_PARSED_APPLY_IMPLEMENTATION.md).
> Workers remain 1 and parallel gameplay remains OFF. R-CHUNK-01 and Stage 3
> remain PARTIAL because FULL and later status/readiness stages are outside this
> pass. Stage 4 and C2ME remain NOT STARTED.

## Decision A implementation boundary

The exact `ChunkMap.applyStep` EMPTY registration supplies actual holder and
winning `startedWork` evidence. `ChunkBindingRuntime` creates the unchanged P/Q/U
producers, installs `thenComposeAsync` parsed application and
`exceptionallyComposeAsync` recovery, and binds the exact returned future by
reference at `GenerationChunkHolder.applyStep`. Future identity is only a finite
locator; lifecycle, WorkId, holder and reservation identity remain authority.

Typed pending stages are delivered from an owner-free event-loop pump and claim
through the shared custody fence plus current `RegionDirectory` route and short
RegionLease. Stale delivery reroutes the same StageId. Supersession, shutdown and
barrier controls use the graph-private authenticated transport, skip vanilla
corruption recovery and holder crash handling, and unload only the old bound
holder/slot. Ordinary Minecraft exceptions remain FAILED_VANILLA and reach the
original recovery once.

Reload claims require the exact POI install receipt, metadata validity and light
batch registration. The original `SerializableChunkData.read` keeps retain,
BLOCK and SKY calls in their original section order. Actual mailbox insertion is
acknowledged before an occurrence becomes ACCEPTED; normal read return seals the
batch and creates the ingress-only receipt. Parsed success and the later EMPTY
publication both validate that receipt. No lease survives into asynchronous
lighting propagation.

Terminal settlement removes the returned-future and POI locators, holder graph,
pending callbacks, light batch/occurrences/receipt, POI receipt, metadata shadow,
work reservation and finite holder lifecycle. The synchronous chunk-access guard
rejects an owner wait on unfinished work while preserving already-ready access.

Do not expand this checkpoint into FULL_CONVERT, FULL publication,
BLOCK_TICKING, ENTITY_TICKING, TICK_READY, SEND_READY or DEMOTE. The next review
may authorize FULL_CONVERT plus FULL status publication. It must retain this
load graph's identity, private-control, no-wait and service-ordering contracts.


> **IMPLEMENTATION AUTHORITY:** read
> [STAGE3_CHUNK_OWNERSHIP_ARCHITECTURE.md](STAGE3_CHUNK_OWNERSHIP_ARCHITECTURE.md)
> with Luna's runtime evidence/map. It selects **A — coordinated multi-stage
> admission** and supersedes earlier proposals where they differ. The source
> model described below is historical/current-code context, not the complete
> approved implementation contract. R-CHUNK-01 and Stage 3 remain **PARTIAL**.

This is the compact resume point for the only substantial Stage 3 work left. It
supersedes the need to reread the historical Stage 3 reports for this task. The
scope of this closeout is everything that can be accepted without implementing
authoritative Minecraft chunk application.

## A. Current accepted checkpoint

### Pure custody-model implementation status (2026-09-03)

- The accepted coordinated multi-stage admission design now has a pure,
  producer-agnostic deterministic model: lifecycle, shared-work, demand, and
  semantic-stage identities are separate; topology generation is admission
  validation rather than identity.
- `ChunkCustodyModel` and `ChunkStageAdmission` prove queue-before-delivery,
  single-owner lease-plus-claim admission, stale reroute, exactly-once stage
  invocation, lifecycle replacement/rescue, demand cancellation separation,
  shutdown fencing, and vanilla-vs-barrier failure classification.
- This is intentionally **not** a Minecraft adapter. No authoritative chunk
  Mixins, future-graph changes, C2ME work, second worker, or parallel gameplay
  were added. R-CHUNK-01 and Stage 3 remain **PARTIAL**.

- Historical code/test checkpoint before this chunk-readiness closeout:
  `c76d449` (`test: aim stage 3 cross-owner interaction at target`), on top of
  `685690e` (`test: close stage 3 cross-owner interaction gate`).
- The final closeout commit is intentionally not embedded here because
  embedding its own SHA would make the file self-referential; delivery reports
  the final HEAD separately.
- Deterministic suite: **87 passed, 0 failed, 0 errors, 0 skipped**.
- Stage 1: **PASS**.
- Stage 2 serial live tick: **PASS**.
- Stage 3: **PARTIAL**, only because R-CHUNK-01 is unresolved.
- Simulation workers: **1**.
- Parallel gameplay: **OFF**.
- R-CHUNK-01: **PARTIAL**.
- Stage 4: **NOT STARTED**.

Authoritative evidence paths (ignored disposable outputs):

- `build/client-gametest/evidence/STAGE3-CLIENT.json`
- `build/client-gametest/evidence/STAGE3-CLIENT-world-seeds.txt`
- `build/client-gametest/evidence/HARNESS-SMOKE-01.json`
- `run/stage3-client-gametest/logs/latest.log`
- `.stage2-server-live1/stage3-p2-normal-20260903-072138.log`
- `.stage2-server-live1/stage3-p2-failure-20260903-072434.log`

For empirical Minecraft 26.2 chunk runtime stage evidence, read
`docs/STAGE3_CHUNK_RUNTIME_EVIDENCE.md` and
`docs/STAGE3_CHUNK_RUNTIME_MAP.md`. The generated summary and raw selected
traces are under `build/client-gametest/evidence/` after
`runStage3ChunkTrace`.

Latest readiness-specific evidence is in
`docs/STAGE3_CHUNK_READINESS_EVIDENCE.md`. Its companion task is
`runStage3ChunkReadinessTrace`; the generated summary is
`build/client-gametest/evidence/STAGE3-CHUNK-READINESS.json`, with two fresh
world raw traces beside it. That trace used a real TCP client and observed
FULL-to-ticking-to-send-ready, exact client receipt, exact client removal, and
tracking-demand removal. It did not observe direct teardown of the final server
holder within the bounded leave window and did not implement chunk custody.
The gate remains `R-CHUNK-01 = PARTIAL`, `Stage 3 = PARTIAL`, and
`Stage 4 = NOT STARTED`.

## B. Completed P3 gates

| Gate | Result | Evidence |
| --- | --- | --- |
| Deterministic ownership | **PASS** | `Stage3OwnershipTest`, `Stage3MovementTest`, `RealClientReadinessTest` in `build/test-results/test` |
| Topology enclosure | **PASS** | `Stage3MovementTest`; explicit live route queries in `STAGE3-CLIENT.json` |
| Movement admission | **PASS** | `Stage3ClientTest`; `STAGE3-CLIENT.json` movement snapshots |
| Player initialization | **PASS** | `RealClientReadinessTest`; live login snapshot |
| Real client login | **PASS** | `STAGE3-CLIENT.json`, `HARNESS-SMOKE-01.json` |
| Same-owner movement | **PASS** | `same-owner-before/after` snapshots |
| Boundary rejection | **PASS** | `boundary-before/after` snapshots; server remains in A |
| Same-owner interaction | **PASS** | interaction snapshots, target removal response |
| Cross-owner interaction | **PASS** | near-boundary packet and rejection snapshots |
| Fresh P2 exactly-once | **PASS** | `.stage2-server-live1/stage3-p2-normal-20260903-072138.log` |
| Failure barrier | **PASS** | `.stage2-server-live1/stage3-p2-failure-20260903-072434.log` |
| Repeated lifecycle | **PASS** | first/second lifecycle fields in `STAGE3-CLIENT.json` |
| Authoritative chunk application | **NOT REQUIRED** for this closeout | Remains the open R-CHUNK-01 task |

The cross-owner fixture is a real vanilla-valid geometry, not a reach override:

- player: `(78.5, 80, 8.5)`, chunk `(4,0)`, live route
  `stage3-fixture-a`;
- target: `(80.5, 80, 8.5)`, chunk `(5,0)`, live route
  `stage3-fixture-b`;
- measured center distance: `2.0` blocks;
- normal client packet observed: **YES**;
- cross-owner rejection delta: `2`;
- unauthorized vanilla lookup delta: `0`;
- foreign owner execution/mutation delta: `0`;
- replay/effect delta for the rejected packets: `0`.

Fresh P2 evidence from the current artifact:

- Normal: `intercepted=8088`, `dispatched=8088`, `executed=8088`,
  `duplicate=0`, `bypass=0`, `fallback=0`, `ownershipViolations=0`,
  `unleased=0`, `barriers=0`; clean RCON `stop`.
- Injected failure: `scheduler=FAILED`, `615 intercepted`, `10 dispatched`,
  `9 executed`, `barriers=1`, `bypass=0`, `fallback=0`,
  `ownershipViolations=0`, `unleased=0`; later calls were consumed and clean
  RCON `stop` succeeded.

## C. Current zero-counter guarantees

The current authoritative runs and deterministic guards show zero for:

- ownership violations and unleased execution;
- vanilla fallback and bypass;
- packet recursion, duplicate replay, stale packet mutation, and foreign
  interaction mutation;
- mid-lease authority expansion and boundary foreign mutation;
- world-default regional fallback in the explicit fixture;
- peer-region locks, broad gameplay locks, and blocking owner waits;
- producer-thread regional chunk mutation;
- chunk admissions/applications/reroutes/rejections in the movement/interaction
  run.

The last chunk line describes the movement/interaction run only; no chunk
completion was exercised in that run. The separate readiness trace now records
the real client-demand lifecycle, but `chunkApplications=0` in the existing
diagnostics is still not proof of R-CHUNK-01, and the current observer does not
control base-game installation.

## D. Chunk architecture already decided

These are accepted facts from `STAGE3_CHUNK_APPLICATION_REVIEW.md` and the
XHigh review. Do not reopen them as design questions in the next task:

- The existing `ServerChunkCache.getChunkFuture()` return observer is
  **DIAGNOSTIC ONLY**. It observes a public future and records metadata; it does
  not own or replace Minecraft's application result.
- In Minecraft 26.2, the important base application path discovered in the
  review is `ChunkMap.scheduleChunkLoad(ChunkPos)`.
- Its documented application continuation uses `mainThreadExecutor`.
- `SerializableChunkData.read` already affects POI/lighting state before later
  bookkeeping, so a center-chunk future is not the whole mutation footprint.
- Holder publication is a separate path: `GenerationChunkHolder.applyStep` calls
  `GeneratingChunkMap.applyStep` and attaches a non-async completion handler that
  can publish through `completeFuture`.
- `PoiManager`/`SectionStorage` can unpack pending POI loads on another branch;
  POI application is not a pure completion flag.
- `ChunkStatusTasks.full` has a `mainThreadExecutor` continuation that can
  construct/replace a `LevelChunk`, run post-load work, mark it loaded, register
  block entities and tick containers, and install the unsaved listener.
- `ChunkMap.prepareTickingChunk` is a later readiness/ticking path with its own
  `mainThreadExecutor` continuation and `startTickingChunk` work.
- No C2ME integration, chunk application interception, fake chunk state, or
  parallel gameplay was accepted in this closeout.

## E. Known chunk application stages requiring proof

The next implementation/review must keep these stages distinct and establish
which are in scope for each request path:

1. deserialization and initial parsed-result application;
2. POI and lighting side effects;
3. holder publication and dependent future completion;
4. exceptional load recovery and its terminal outcome;
5. `FULL` conversion, post-load work, and shared registrations;
6. readiness/application, including tick-ready publication.

Already known: the current return observer is downstream of authoritative
installation, a non-async `CompletableFuture` handler may execute on either the
registering or completing thread, and `mainThreadExecutor` alone does not prove
Phoenium owner custody. Still to verify: exact request-bound seams, complete
mutable footprint, request/holder supersession behavior, terminal future/error
accounting, and the real installation thread/context for each applicable stage.

Do not independently decide that a single executor wrapper proves every stage.
The existing review's proposed load-dispatch seam is a starting point for proof,
not an implementation approval for all later holder/FULL/readiness paths.

## F. Existing `ChunkCustody` model

Current classes are `dev.phoenium.chunk.ChunkCustody`, `ChunkWorkRequest`, and
`ChunkCompletionGateway`. The model is metadata-only and deterministic:

- `ChunkWorkRequest` is issued before an async producer starts. It carries world,
  routing key, request generation/incarnation, requested topology generation,
  completion stage identity, and correlation ID. It never carries a mutable
  chunk or future into the core envelope.
- `ChunkCustody.CustodyId` is the stable request identity: world, chunk key,
  request generation, and requested topology generation.
- Stage identity is explicit in the completion kind and the staged ledger. The
  current ledger stages are `REQUESTED -> READY -> QUEUED -> CLAIMED -> APPLIED`
  with terminal `FAILED` and `CANCELLED` states.
- Request generation is the same-coordinate incarnation fence. A newer request
  supersedes/cancels the older request before it can apply.
- Topology generation records the generation used for admission. A topology-only
  change may reroute a still-valid request to the current owner; it does not make
  an old captured owner authoritative.
- Expected owner is resolved from the current immutable `RegionDirectory` route;
  current owner is the runtime that receives the envelope and is rechecked at
  claim time. `claimedOwners` records the actual validated claimant.
- `claim` is the exactly-once linearization point for the modeled application
  stage. A duplicate or superseded claim cannot apply a second time.
- Terminal state is explicit and absorbs/rejects later transitions rather than
  silently dropping a future or treating an ownership failure as disk failure.

Relevant deterministic tests are the same-coordinate R1 -> R2 supersession
witness and the terminal/exactly-once stage test in `Stage3OwnershipTest`.
They prove the model, not live Minecraft installation.

## G. Required R-CHUNK-01 PASS conditions

A future chunk implementation cannot claim PASS until it proves all of these
with controlled real or narrowly bound continuation tests:

- producer-thread regional mutation = `0`;
- stale request application = `0`;
- stale owner application = `0`;
- duplicate stage application = `0`;
- fabricated `EmptyChunk`/AIR/BEDROCK caused by Phoenium ownership failure = `0`;
- authoritative application executes only under validated current ownership,
  current request/holder incarnation, topology/readiness admission, and the
  proper leased owner context;
- normal and exceptional outcomes preserve the genuine vanilla future/error
  contract and account for cancellation, supersession, shutdown, and barrier
  terminal states.

## H. Forbidden chunk solutions

Do not introduce any of the following:

- blocking `join`/`get` to wait for an owner or chunk application;
- a broad world/gameplay lock;
- a peer-owner wait or peer-region lock ordering;
- fabricated chunks or AIR/BEDROCK semantic substitutes;
- producer-thread regional authority;
- a generic route for arbitrary server `Runnable` objects;
- C2ME work in this task;
- Stage 4 work, multiple simulation workers, or parallel gameplay.

`getChunkFuture()` may remain an observer until a reviewed, request-bound
application custody seam is implemented and proven.

## I. Stage 3 adapter infrastructure

The pure custody model now has a production-facing adapter infrastructure
layer. Read [Stage 3 chunk adapter infrastructure](STAGE3_CHUNK_ADAPTER_INFRASTRUCTURE.md)
after the architecture and runtime evidence documents.

It supplies lifecycle and shared-work registries, a typed pending continuation,
no-owner delivery pump, owner-inbox boundary, admission executor, private
control outcome, and POI/light/metadata bridge interfaces.

No Minecraft authoritative continuation is bound by that infrastructure pass.

## J. Exact next task

### Reload light ordering resolved; implementation remains pending

Read [STAGE3_RELOAD_LIGHT_INPUT_DECISION.md](STAGE3_RELOAD_LIGHT_INPUT_DECISION.md).
**Decision A: light ingress is a nested PARSED_APPLY sub-operation/postcondition.**
Prepared batch registration gates entry; actual retain/BLOCK/SKY acceptance
remains inside read and its receipt gates successful PARSED_APPLY completion,
then accompanies the separately admitted EMPTY publication. No replay after
partial ingress; original genuine recovery remains separate. POI installation
is non-cyclic. thenComposeAsync and corrected metadata decisions are unchanged.
Verified checkpoint `78334954078ecb8cb190204b9e8cd2a6f235c5dc`, clean start;
rerun baseline **134 PASS, 0 failures/errors/skips**. This decision is docs-only.
Terra next implements the narrow correction and LIO-01..16 before resuming
atomic binding; FULL/ticking/send remain out of scope and all stage gates unchanged.

### Metadata contract resolved; prerequisite correction precedes binding

Read [STAGE3_CHUNK_METADATA_TRANSITION_DECISION.md](STAGE3_CHUNK_METADATA_TRANSITION_DECISION.md).
**Metadata decision A:** current authorized vanilla operations may change FULL
to REPLACEABLE; stale lifecycles and superseded work must be fenced before any
write. Use vanilla bytes ABSENT=0, REPLACEABLE=-1, FULL=1 and validate WorkId for
load-associated operations at mutation admission, not only at prerequisite lookup.
PRQ-15 already covers stale lifecycle rejection: retain it as 15A and add 15B
(current replacement/previous-byte parity) and 15C (superseded same-lifecycle work).
The real Minecraft recovery contract test is a required regression; its current
service-divergence assertions change to parity together with the production fix.
Verified starting HEAD `40e999cb4bcf2a539a04faf1a0f4556420ae3d77`, clean tree;
fresh clean build **121 PASS, 0 failures/errors/skips**, including the real witness.
This decision is docs-only; the implementation mismatch still exists.
Terra next corrects the façade, lineage/atomic fences and regressions, reruns the
full suite, then resumes the existing atomic binding. thenComposeAsync Decision A
is **UNCHANGED**. No PARSED_APPLY is implemented by this decision; gates stay PARTIAL.

### Historical Decision A binding stop

The implementation attempt from `070a720` hit user stop condition 7 before
enabling any authority. The actual Minecraft 26.2 `$4` fatal recovery path
unconditionally changes a cached FULL marker to -1; the prerequisite façade
retains FULL for the same current lifecycle. The subsequent previous-value
result also differs. Byte encoding translation alone cannot reconcile this.
Read [STAGE3_PARSED_APPLY_IMPLEMENTATION.md](STAGE3_PARSED_APPLY_IMPLEMENTATION.md)
for exact bytecode anchors and the real-method JUnit witness. Clean build:
**121 tests PASS**, including all original 120; PRQ 18/18 remains PASS.
FUT acceptance is 0/18; no production binding or new live acceptance run is
claimed. The metadata decision above now resolves the contract; implement and
verify that prerequisite correction before resuming atomic binding. Decision A
composition remains approved; R-CHUNK-01/Stage 3 remain PARTIAL and Stage 4 NOT STARTED.

### Prerequisite-service checkpoint

The service prerequisite layer is now documented in
[STAGE3_CHUNK_SERVICE_PREREQUISITES.md](STAGE3_CHUNK_SERVICE_PREREQUISITES.md).
It provides world-scoped, demand-allocated POI install receipts, light-input
ingress receipts, ordered chunk-type metadata, and a non-blocking load
prerequisite query. It binds no Minecraft mutation and does not change the
approved Decision A future semantics.

Read [PARSED_APPLY future semantics decision](STAGE3_PARSED_APPLY_FUTURE_DECISION.md)
first. **Decision A approves precise thenComposeAsync flattening**, with the
adjacent recovery composition, exact holder control filter, explicit work-token
correlation and terminal settlement specified there. This refines the earlier
executor-only wording; a one-line operator change is not sufficient. The review
verified checkpoint `0aecc38` and **99 passing tests**; it changes documentation
only. Terra's bounded implementation must satisfy FUT-01 through FUT-18 before
acceptance. R-CHUNK-01/Stage 3 stay PARTIAL; Stage 4 stays NOT STARTED.

**NEXT MODEL TASK:**

Bind and prove the first authoritative Minecraft 26.2 continuation using the
infrastructure in `STAGE3_CHUNK_ADAPTER_INFRASTRUCTURE.md`. Start only at an
exact registration/invocation seam where the original upstream future,
dependent-future role, result, exception, ordering, and recovery behavior can
be preserved without a join, owner wait, generic Runnable path, or main-thread
fallback. Do not redesign the identity, lease, service, or future-settlement
contracts during that proof.

Do **not** revisit movement/packet architecture unless new evidence contradicts
the accepted results above. The only substantial Stage 3 engineering problem
remaining is authoritative chunk application / R-CHUNK-01.

### Metadata implementation checkpoint (current)

Part A now corrects the facade to vanilla bytes 0/-1/1, unconditional authorized
FULL -> REPLACEABLE, registered WorkId validation and atomic metadata fencing
against lifecycle/work retirement. PRQ-15A/B/C and bound real-Minecraft fatal
recovery parity pass. Clean build: 132 tests PASS. See the current section of
STAGE3_PARSED_APPLY_IMPLEMENTATION.md; historical mismatch text above is resolved.
Decision A production binding and FUT/live acceptance remain pending at this
separate metadata checkpoint. R-CHUNK-01/Stage 3 PARTIAL; Stage 4 NOT STARTED.

### Historical Decision A stop: reload light-order gate

Metadata checkpoint: `4065883746456ce84e550689a438b9a4b64e5801`, CLEAN.
Part B inspection found that B7 requires an accepted light receipt before
PARSED_APPLY, while real retainData and BLOCK/SKY ingress occur inside its
SerializableChunkData.read body. Directly binding those calls makes admission
wait for its own effects. No early light submission, receipt reinterpretation,
or mutation-body split is silently authorized. See the implementation record
for exact bytecode anchors, two deterministic witnesses and the required narrow
ordering decision. Final clean build: 134 PASS. FUT 0/18 accepted; no production
binding or new live acceptance. Resolve this gate before continuing Decision A;
FULL expansion is not next yet. R-CHUNK-01/Stage 3 PARTIAL; Stage 4 NOT STARTED.
