# Stage 3 chunk-only handoff

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
