# Non-self initialized inputs: test-only decision checkpoint — 2026-09-05

Latest implementation result: **PARTIAL**, not integrated FULL acceptance.
The [production boundary interruption](STAGE3_FULL_PRODUCTION_BOUNDARY.md) and
[current ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md) record 334 passing
deterministic tests, installed/transformed semantic helpers, the independent
route correction and final-artifact runtime evidence. Authoritative FULL,
private neighboring access and shared-service bindings remain disabled/absent.
The original-seed vault tick is reproduced again and remains unresolved.
The prototype evidence below is preserved; it is not upgraded to production
acceptance by the method adapter or the approved amendment.

Current implementation request: the bounded production amendment is recorded in
[STAGE3_FULL_NONSELF_APPROVAL.md](STAGE3_FULL_NONSELF_APPROVAL.md). The historical
proposal and test evidence below remain intact. Its pending-approval/stop text
describes the prototype checkpoint; production correctness and enablement still
require the [implementation gates](STAGE3_FULL_APPROVED_IMPLEMENTATION.md).

**Tested design proposal; additional production approval is required.** Both
original X/Y conversions finish on proto and persisted-reload paths in both
orders. Neither uses a diagnostic stop as success or waits for the other's
unfinished public FULL result. Production FULL remains **DISABLED**; Stage 3 /
R-CHUNK-01 remains **PARTIAL**; Stage 4 is **NOT STARTED**. One simulation worker,
parallel gameplay OFF. Stop at this prototype-validation checkpoint.

## Recovered state and scope

Actual starting HEAD was `14dc1812c82106f9b9749f87e3ba83f348c8db4e`.
The interrupted checkout contained untracked `FullConstructionFragments`,
`FullNonselfPrototype`, and `FullNonselfInputDecisionTest`, plus supporting
`FullNestedQueryBoundaryTest` overloads. Its last XML had 22 passing tests.
The separate client diagnostic directory/seed support and
`scripts/reproduce_ticking_block_entity.py` were also recovered and preserved.
There was no later prototype decision/resume note. No reset, stash, source-name
change, production runtime change, version change, or concurrent agent editing
was used. The isolated client harness was compiled; no live campaign was run.

The build lock remains Minecraft 26.2, Temurin Java 25.0.4.1, Fabric Loader
0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20, Gradle 9.5.1, JUnit 5.12.2.
The [D2/direct-self D3 approval](STAGE3_FULL_D2_D3_APPROVAL.md) remains accepted.
It does not approve the neighboring access or construction split proposed here.
The [original cycle evidence](STAGE3_FULL_NONSELF_QUERY_CONFLICT.md), including
its intentionally caught diagnostic stops, remains intact and passing.

## Dependency graph before and after

Unchanged reproducer:

- X is chunk (0,0), saved tadpole (15.8,8,8), Age=24000, AgeLocked=true.
- Y is chunk (1,0), saved tadpole (16.2,8,8), same mature age/behavior.
- Original entity reading, team transfer, frog conversion/finalization,
  difficulty reads, size-change collision envelope and both search attempts run.

The prior graph has `finish X -> public FULL Y -> finish Y -> public FULL X
-> finish X`. Reordering cannot finish either supplier. The old cycle witness
stops diagnostically before the wait, and is not successful-conversion evidence.

The proposal gives each work one preparation receipt for its actual LevelChunk:

```text
prepare X ------+------> finish X ------> separate publish X
                |           ^
                +------> finish Y ------> separate publish Y
                            ^
prepare Y ------------------+ (also supplies finish X)
```

More precisely, `finish X` requires prepared X and Y; `finish Y` requires
prepared X and Y. Neither requires the other's finishing or publication.
Preparation has no nested saved-reader callback. A direct input window is
discovered and prepared before an owner claims its finishing operation. Shared
inputs reuse their receipt; no recursive owner execution or executor pump occurs.
The three-chunk witness preserves the original pair, adds corner entities and
Z=(1,1), and proves actual X->Y, Y->Z, Z->X collision accesses with all three
public results unfinished. The eight-work chain exercises overlapping direct
windows and a queue of two without requiring transitive callback completion.

## Exact pinned construction / finishing boundary

`FullConstructionFragments` reads the class already on the test classpath. It
is hard-coded to `ChunkStatusTasks.lambda$full$0` and its exact descriptor.
The loaded `ChunkStatusTasks.class` SHA-256 is asserted as
`AB9A75EC31F57DF5502EE65FFE413D8099097417C2ED1C928DA3890B523E3B05`.
The exact ordered invocation inventory, actual local index, control-flow jumps,
and absence of supplier exception handlers are asserted before extraction.
This is a test helper for this one pinned method, not a configurable modifier.
It defines a separately named class only inside the JUnit process; no installed
class, game, launcher, agent, mixin or production artifact is modified.

Inspection of the pinned bytecode establishes this partition:

| Portion | Original bytecode and effects |
| --- | --- |
| Preparation, offsets 0–84 | Cast ProtoChunk, capture context level. Imposter branch: `getWrapped`, reuse the exact saved actual. Proto branch: original LevelChunk constructor with original post-load processor, original Imposter constructor and `replaceProtoChunk`. Both branches converge and call `setFullStatus`. |
| Cut, offset 85 | Immediately before `ALOAD 4; runPostLoad`. The new return exposes local 4 privately. All original branches stay wholly before the cut; no catch/finally spans it. |
| Finishing, original offsets 85–119 | `runPostLoad`, `setLoaded(true)`, `registerAllBlockEntitiesAfterLevelLoad`, `registerTickContainerInLevel`, `setUnsavedListener`, return the same local 4. This suffix is copied, not rewritten as simulated vanilla behavior. |

The suffix wrapper restores locals 4 and 5; restoring local 5 reads the immutable
`WorldGenContext.level()` accessor again. Thus there is one extra context
accessor call, not an extra original constructor or side-effecting operation.
The original asynchronous `full` wrapper is not invoked by this prototype: the
existing `PendingVanillaStage` / `ChunkStageExecutor` / `ChunkStageAdmission`
claim each portion separately. The original whole-wrapper witnesses remain.

The proto post-load `lambda$full$1` remains whole, including both Throwable
handlers for scoped collector close and suppressed exceptions. Its original
`postLoadProtoChunk` and entity-loading calls still run. Reload preparation uses
the same LevelChunk constructor signature as `SerializableChunkData.read`'s
LEVELCHUNK branch and its original `postLoadChunk` processor, followed by
Imposter reuse in the extracted supplier. It is a controlled in-memory saved
fixture, not a disk/chunk-read integration claim. The reload processor retains
its entity-stream/collector handlers, legacy insertion, and subsequent saved
block-entity loop, including `keepPacked` and unpacked replacement branches.

`LevelChunk.runPostLoad` clears the processor only after a normal processor
return. An uncaught registration failure leaves it attached, as vanilla does;
the settled failure receipt prevents invoking it again. Construction/reuse is
counted once per Work. On reload the actual object is constructed once by the
saved fixture, then reused once; `constructions` counts that preparation entry.
The successful stage outcome, conversion future, holder's actual wrapped object,
and eventually original FULL completion all have reference-equal values.

## Permitted data, mutations, and publication separation

The initialized data is the actual chunk's block sections, inhabited time and
block-entity state at this new observation point. Neighbor access is restricted
by the controlled getter to the audited collision / shulker-shape call paths.
Direct-self difficulty access is retained. Private results never enter the
ordinary last-result cache or the holder's FULL slot. The original private
future selector and `UNKNOWN` ticket prefix run at the original query point.
Ordinary cache-hit, already-ready result, and genuinely absent false-query
classification remain separate. Existing ready/absent witnesses still pass;
the prototype also checks published-neighbor reuse without a private borrow.

Each active Scope is tied to the exact ServerLevel, source WorkId/lifecycle,
callback stage/claim, frozen owner, target Work, holder, holder slot and actual
LevelChunk reference. It snapshots the actual objects on entry, checks the
holder's wrapped object, and rejects an object substituted afterward. Wrong
world/work/lifecycle/holder/object and an expired Scope used from a later active
operation cannot become APPLIED even when the caller catches the exception.
Scope exit drops its plan, claim, object map and mutation ownership; a late
duplicate close cannot revoke a later operation. An unauthorized neighbor query
also records the fault when its caller catches the thrown exception. No
asynchronous identity is inferred from ambient thread state. The accepted
finite synchronous guard/fault context is unchanged.

Dynamic-shape access is **not read-only**. The test protocol explicitly owns the
whole direct mutation window: IMMEDIATE block-entity creation, replacement,
shape state, and the original listener/ticker consequences as applicable. It
separately requires the named ticket turn before a cache-miss FULL,true selector.
Removing mutation ownership fails after the original ticket prefix and before
creation; removing the ticket turn prevents that prefix. Both preserve the
already-reached team effect and record a barrier. A conflicting writer is
refused before changing input while the window is claimed.

These are test-protocol checks over controlled endpoints, not a security
boundary around arbitrary Java references. Raw LevelChunks returned to original
Minecraft calls cannot revoke arbitrary copied Java aliases. Production needs
all relevant access paths and conflicting writers bound to the protocol; a
StackWalker call-path check in the fixture is audit evidence, not a production
authorization mechanism. Route convergence, generation-writer drain receipts
and C/S/W service availability are controlled inputs here, not installed services.

The non-air pair proves repeated original searches, first-missing then present
block entities, level-33 UNKNOWN tickets and one ticker per registered box.
Four further path/order combinations read real foreign shulker progress=0.5
with a bounding box beyond its unit block. Reload tests show that an immediately
created neighbor object may later be removed/replaced by the original saved
block-entity suffix; a touched packed position and untouched packed position
take their distinct original branches. No suffix is preinstalled to avoid this.

Both success matrices finish each original callback with both public FULL slots
unfinished. Success requires no barrier and the actual original finishing return.
Public FULL completion is a separately admitted call to the original holder's
`completeFuture`; successful construction alone, callback failure, stale holder,
retirement and shutdown cannot authorize it. No production FULL registrar is enabled.

## Replanning, partial failure, retention and bounds

Plans freeze routing version, exact visible holders including observed absence,
input revisions and copies of the saved entity tags. Pre-execution changed tags
are detected even if the controlled writer omitted its revision notification.
A new corner entity and newly present holder invalidate the plan before callback
side effects; replanning discovers/prepares Z and preserves already-constructed
X/Y identities. Section/block-entity writers must participate in the controlled
revision/exclusion protocol; this is not a general snapshot detector for all
mutable Minecraft fields.

An unexpected dependency after side effects start records the existing guard's
fault, does not enlarge the active window, and cannot trigger replay. The test
injects it only after an original repeated dynamic query observes immediate
creation and asserts the injection was reached. Retained ticket/team/block-entity
effects survive FAILED_BARRIER. A caught ordinary sound fixture is different:
Minecraft catches it, so the supplier can succeed with its reached prefix and
no entity insertion; that is preserved vanilla behavior and not the successful
two-conversion matrix. Proto and reload root-insertion failures remain
FAILED_VANILLA, preserve the reached entities/team/tickets/foreign block entity,
leave postLoad attached and prohibit publication/replay.

One successful consumer may publish even if a producer whose prepared data it
observed later fails its own finishing callback. The proposal deliberately has
per-work outcomes, not component-wide atomicity or fabricated rollback. A
construction failure after holder replacement also preserves that actual object,
but its failed construction receipt cannot supply finishing or be reconstructed.

`InputConsumer` is an explicit retention claim, not query authority. Two
consumers pin shared inputs independently; closing one cannot release the
other's data. The executing Scope adds its own finite pins. Retirement is an
authentic terminal signal; it never substitutes for resolving cyclic demand.
An active finishing operation defers retirement until exit. Construction retains
its reached object through the existing active lease. The first acknowledged
release clears pending-stage references, actual-object retention, registry/queue
membership and matching visible-holder entry, and releases custody exactly once.
Old and replacement Work records coexist while an old consumer remains. Releasing
the old one neither removes the replacement holder nor settles its future.

Shutdown closes admission, preserves an already-entered original operation until
exit, suppresses publication, and waits for retained consumer acknowledgements.
Unreached conversion/publication endpoints are settled exceptionally only on
authentic retirement/shutdown. Successful external result futures keep their
actual object; there is no claim that externally held futures or fixture/world
objects are garbage collected. Cleanup measures protocol retention, not heap GC.

Measured cleanup: for **each** proto/reload path, 8 fresh helper lifecycles release
16 Work records exactly once after two consumer acknowledgements per lifecycle.
Peak retained works in each pair is 2. Terminal retained-work, consumer, work-map,
queue, retirement and custody counts are all zero. Both replacement tests release
3 works once, retaining the old object until its last consumer exits. Active
callback and active construction shutdown tests release 2 each, after authority
exits. Closed Scopes retain no plan/claim/object entries.

Configured bounds are `maxWindow` positions per direct plan, `maxQueue` queued
works, `maxQueue` explicit consumers, and `maxWindow * maxQueue` retained works
(including unreleased replacements). Defaults are 4/8/8/32; the eight-work chain
uses 4/2/2/8. A capacity refusal retains producer demand; it is not cancellation.
Writer/service unavailability parks without an owner lease and a controlled
capacity/readiness wakeup retries the same work. An unsupported larger footprint
is rejected before admission. Entity/tag payload sizes, detached caller-owned
plans and diagnostic event histories are finite fixture data, not a proven
production byte-memory bound. Real scheduling/fairness, complete callback
discovery, payload admission and resource accounting remain production obligations.

## Verification and preserved evidence

All paths below are repository-relative and ignored. Entire build directories
(including existing generated evidence) were copied before any overwrite/clean.
Evidence root: `run/nonself-input-decision/resume-20260905-085155/`.

| Command / checkpoint | Process exit | JUnit result |
| --- | --- | --- |
| Recovered original XML | Historical log reports success | 22 tests, 0 failures/errors/skips |
| `gradlew.bat test --tests "dev.phoenium.chunk.FullNonselfInputDecisionTest" --no-daemon` before edits | 0, UP-TO-DATE | Existing 22-test XML, not claimed as new execution |
| Same command with `--rerun-tasks` before edits | 0 | 22 executed, 0 failures/errors/skips |
| Final focused command, original flags | 0 | 48 tests, 0 failures/errors/skips |
| `gradlew.bat clean build compileClientGameTestJava --no-daemon` | 0 | 312 tests in 24 suites, 0 failures/errors/skips; client harness compiles |
| `git diff --check` | 0 | No whitespace errors |

The total is the preserved 264-test baseline plus 48 prototype cases. Exact logs,
exit files, XML and reports are in `focused-recovered-*`, `focused-reviewed-*`,
`clean-build-reviewed-*`, `test-summary.json` and `artifact-verification.json` under the
evidence root. `pre-session-build`, `pre-session-logs`, `pre-clean-build` and
`pre-clean-logs` preserve the old and intervening outputs; subsequent
`pre-*-clean-build` copies also precede the final review rebuilds. Pinned method
disassemblies are alongside them. Delivery HEAD and final status are recorded
in the local checkpoint manifest and final response, avoiding a self-reference.

Failed attempts remain evidence, not acceptance:

- Interrupted prototype attempt 01: compile error using `EntityType.TADPOLE/FROG`
  instead of pinned `EntityTypes`; fixed before recovery.
- Attempt 02: 12 tests passed. Attempt 03: 22 tests, 5 failures: four non-increasing
  directory-generation fixture errors and one incorrect keepPacked expectation.
  Attempt 04: recovered 22-test success; independently rerun above.
- This pass `focused-extension-02`: 41 tests, 3 failures. Two variants retired X
  before executing Y, correctly yielding NOT_READY; the test now finishes both
  before retirement. The late-fault fixture expected a repeated dynamic query
  that its one-box arrangement did not produce; it now uses the original
  two-box fallback fixture and asserts actual injection. Later focused runs and
  the clean full suite pass. No runtime check was weakened.

WARN/ERROR text alone is not a result. The controlled sound failures belong to
`caughtSoundErrorRetainsForeignImmediateCreationAndTicketButIsNotAnAuthorityBreach`,
`FullTadpoleDecisionTest.caughtSoundFailureKeepsTeamConversionPrefixAndSupplierCanSucceedWithoutReplay`,
and the preserved `ChunkQueryFailureBindingTest` caught-vanilla control. Their
assertions pass. Existing unfinished-query/diagnostic-stop tests also assert
their expected failure semantics; they are not substituted for successful
conversion. Perflib/OSHI output comes from system-report collection; no Windows
counter, registry, warning-suppression or machine-security changes were made.

The production JAR contains 251 entries and no prototype, test, JUnit, generated
fragment or client-test classes. Pre-session and clean-build SHA-256 are identical:
`46E37BBE91CBCCD958656DA125ADC10ABCCA6DC090A2D0DE86E4BD79C6E7ACC2`.
No production source/build lock changed, so identical artifact identity is
explained and measured rather than assumed.

## Separate open issues and proposed approval amendment

The interrupted live diagnostic already reproduced `Ticking block entity` at
`run/nonself-input-decision/ticking-20260905-023048/` (process exit 1, preserved
manifest/logs, same seed -663839099974294500, current production JAR). Its captured
cause is `ChunkProtocolException: unfinished synchronous chunk dependency inside
an owner transaction`, reached through `VaultBlockEntity.Server.tick ->
BlockEntity.setChanged -> Level.updateNeighbourForOutputSignal -> getBlockState
-> ServerChunkCache.getChunk`. The original failed save/timing is unavailable;
this diagnoses the preserved reproduction, not a repair or proof of the precise
historical save's cause. The incident remains **unresolved**. Its recovered
diagnostic source is retained; no new live run occurred in this pass.

Missing-route classification remains separately open: `ChunkStageDeliveryPump`
still classifies every absent route as CANCELLED. Existing cancellation cleanup
does not prove correct handling of permanently unroutable valid demand and does
not authorize cancelling a legitimate X/Y cycle.

The additional production amendment would have to approve all of the following:

1. Splitting the original FULL supplier before postLoad, with early proto holder
   replacement/status-supplier installation and separate preparation admission.
2. Exact neighboring initialized-state access before that neighbor finishes;
   D3-X currently approves direct-self access only.
3. Explicit neighboring mutation ownership for dynamic shapes, immediate
   block-entity effects and later saved-suffix replacement, with bound ticket and
   world/scoreboard/service turns and all contributing writers excluded.
4. These changed observation points and per-work outcomes: a successful consumer
   may survive/publish despite a later producer callback failure; reached effects
   are retained, never rolled back or replayed.
5. A production implementation of discovery, reservations, validation, bounded
   retention, holder replacement, shutdown and separate publication, with the
   remaining D1/D2/C/S/W and complete saved-callback closure obligations satisfied.

These items are described for review, not inferred to be approved or installed.
The production gate and all other open rows in the
[implementation ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md) remain in force.
