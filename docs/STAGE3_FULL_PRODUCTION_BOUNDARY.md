# Production FULL registration and preparation — in progress

The registration/preparation implementation is now positive in original-method
JUnit tests. **This is still an in-progress checkpoint, not checkpoint B or
integrated FULL acceptance. General production FULL selection stays disabled.**
Ordinary ChunkMap does not implement FullGenerationSource; its original whole
supplier remains the baseline path. The approved method split is reused.

Current code binds the winning GenerationChunkHolder.applyStep map invocation,
the original async FULL wrapper, and the exact enclosing ChunkStep returned
future at its adjacent handle. FULL_PREPARE runs through the existing typed
stage, route, lease, claim and outcome adapters. Private preparation success
does not settle the original conversion, adjacent holder result or public FULL.
Finishing has no installed service bindings and cannot claim.

Generation proof comes from actual status-slot references, producer future
completion/release, and generationRefCount/generationSaveSyncFuture retention.
The original holder completion can reenter and register FULL before the parent
producer's older release callback runs; the preparation stage waits for that
release. The FULL task's own generation pin remains legitimate while its result
is pending. Holder slots, input and world identity are checked again at claim.
Preparation additionally binds immutable world construction inputs and the
actual PrimaryLevelData game-time writer/read turn required by sulfur setLevel.

Current validation: `gradlew.bat test build prepareStage3ClientServer --no-daemon`
passed 353 tests / 27 suites, including every prior baseline test. The original
missing-connection test first failed on post-load/public completion, then passed
through the registrar. Failures and all overwritten outputs are preserved in
`run/full-registration/` under the session recorded in
`run/full-registration-current.txt`. Baseline start: clean
`2f270ffc539f85050a50b22d44d68b7d2fa5369e`, 334 / 26 freshly executed, exit 0.

Intermediate baseline-mode transformed server passed at
`run/full-production-integration/transform-20260905-142746/`, artifact
`FE0F38F9ADBD2C4370894E29FDD0C829B0A2EAF5F36D36CE1254BBBDDAEDCB13`.
This is not the final artifact identity or selected-path runtime acceptance.

**Next action:** add and execute the finite test-only installed-server fixture
for the production registrar/preparation path, finish the remaining lifetime
and strict-selector checks, then run the requested clean final verification.
No integrated services, finishing, private queries or publication are enabled.
The vault incident remains OPEN; Stage 3/R-CHUNK-01 PARTIAL; Stage 4 NOT STARTED.

## Previous method-boundary checkpoint (historical)
This checkpoint implements the semantic method boundary and the independent
delivery correction. **It does not complete checkpoint B, the integrated FULL
assignment, or the Stage 3 ledger. Authoritative FULL remains disabled.**
The bounded [approval](STAGE3_FULL_NONSELF_APPROVAL.md) remains valid; no new
semantic conflict or approval requirement has been demonstrated.

## Implemented boundary

`FullBoundaryPlugin` is narrow Mixin instrumentation for exactly the pinned
`ChunkStatusTasks.lambda$full$0` and descriptor. It runs after the installed
Fabric/Phoenium injections and adds `phoenium$prepareFull` and
`phoenium$finishFull`. It does not modify the original asynchronous wrapper or
the original supplier, which continue to execute in baseline mode.

Preparation contains the actual Imposter reuse or original proto construction,
Imposter construction and holder replacement, then full-status installation.
It ends before `runPostLoad`, without running any RETURN injections. Finishing
contains the original post-load/loaded/block-entity/tick/unsaved suffix and the
entire installed Fabric and Phoenium return callbacks. No handwritten copy of
Fabric event iteration, saved-entity reading or the post-load processor is used.
The original proto callback and its two Throwable/collector handlers stay whole.
Finishing restores the known locals, including one additional immutable
`WorldGenContext.level()` read, as documented for the prototype boundary.

Selectors check the exact class and method descriptor, ordered original calls
and their descriptors, no supplier exception region, no branch crossing the
cut, the actual receiver local and suffix local availability. The production
plugin additionally requires exactly the audited Fabric load callback and the
two Phoenium observations at their respective positions; missing, duplicate,
moved or unknown supplier injections fail startup explicitly. Byte offsets and
class hashes are evidence, not rewrite selectors. This is not a general binary
rewriting API. JUnit, class-remapping test helpers and generated test classes
remain in the test source set and outside the production JAR.

`FullProductionBoundaryTest` executes this production selector on the pinned
class and invokes its resulting operations on real LevelChunk/Imposter objects
with controlled world endpoints. It verifies original reference identity,
deferred post-load/loaded effects, unfinished public FULL slots and selector
drift rejection. The reload helper is **in-memory Imposter reuse**, built with
the proto constructor's dirty state; it is not persisted-disk acceptance.

`scripts/test_full_boundary_transform.py` boots the actual production artifact
on a disposable dedicated server with the recorded module set, exports the
real transformed class, verifies the callback partition and records cleanup,
seed, module and artifact hashes. This establishes transformation plus
baseline-mode execution, not execution of an authoritative asynchronous FULL
graph. The helpers themselves are not a grant to mutate a world.

## Exact remaining implementation

No production caller currently selects these new entry points. The next code
must bind the actual winning `GenerationChunkHolder.applyStep(FULL)` reservation
and `ChunkStatusTasks.full` asynchronous edge to existing lifecycle/work/stage
admission, with exact returned-future correlation at `R.handle`. Introduce a
separate preparation stage and retain its successful actual-object receipt;
only validated stage-specific input/writer/service evidence may enqueue it.
Do not run the whole supplier after invoking preparation.

Real generation writes/borrows, discovery and input revision writers, operation
contexts at difficulty/collision/dynamic-shape queries, all D1/D2 and C/P/S/W
bindings, acknowledged bounded retention, finite capacity/progress scheduling,
original finishing execution and separate STATUS_PUBLISH(FULL) remain absent.
An ordinary finishing failure must not revoke valid consumed construction
receipts; failed construction and protocol barriers still cannot supply success.
The original 48-case prototype outcome matrix is reference evidence only.

The next test to implement is
`dev.phoenium.chunk.FullProductionRegistrationTest`: invoke the original async
FULL wrapper through the installed production registrar, correlate the exact
holder result, prove preparation leaves public FULL unfinished, and prove the
finishing stage cannot claim with any required binding absent. This test/class
does **not yet exist**; it is not reported as an executed failure. Start with
the real reservation and generation-borrow producers, then the service adapters.
Do not rerun the old cycle investigation or seek the same approvals again.

See the [current handoff](STAGE3_CHUNK_HANDOFF.md) and
[acceptance ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md) for final executed
commands, counts and artifact-specific runtime results. No Stage 3 PASS tag.

