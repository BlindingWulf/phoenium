# Production FULL registration and admitted preparation

## Current checkpoint — 2026-09-05

**REGISTRATION + ADMITTED PREPARATION: PASS for the bounded checkpoint below.**
Integrated FULL/checkpoint B remains **PARTIAL**. Ordinary production FULL selection
is **DISABLED**: ordinary `ChunkMap` does not implement `FullGenerationSource`.
Its original whole supplier remains the baseline path. There is no global property
that enables the incomplete graph. The existing approved method split is reused.
Stage 3/R-CHUNK-01 PARTIAL; Stage 4 NOT STARTED; one simulation worker; parallel
gameplay OFF. No Stage 3 PASS tag. The vault incident remains OPEN and was not rerun.

Verified start: clean `2f270ffc539f85050a50b22d44d68b7d2fa5369e`, production JAR
`1E368F022797143C6948C7A0DEBEC429C0D233D870DCFABB5CAF846C880F9F22`.
The first implementation commit is `d3a3643`; the final delivery HEAD and clean
status are in the ignored session manifest and final response, avoiding a
self-referential commit hash. Only one agent wrote the checkout. Pinned Minecraft
26.2, Java 25, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20 and Gradle 9.5.1
remain unchanged. Gradle build parallelism is distinct from gameplay parallelism.

## Actual entry points and future graph

`GenerationHolderCompletionMixin` wraps the real map invocation dominated by the
successful `GenerationChunkHolder.acquireStatusBump` CAS in `applyStep`. The
registrar checks the actual holder, started status, cache member and live generation
pin. A losing reservation takes the original subscriber path. A winning reservation
need not have created a public FULL slot; the implementation does not invent one.

`FullBoundaryPlugin` strictly verifies the installed wrapper observations and
supplier callback inventory, reuses `FullStageBoundary`'s existing split, and
binds the exact `CompletableFuture.supplyAsync` edge in the original
`ChunkStatusTasks.full` wrapper to `FullRegistrationRuntime.registerAsync`.
The finite synchronous invocation establishes lineage only across that map call;
it is restored in `finally` and supplies no ambient asynchronous authority.
The generated preparation supplier targets the installed `phoenium$prepareFull`.

The graph and both typed pending stages exist before the original executor is
armed. Its callback arms metadata. The conversion future returned by this original
wrapper remains the same conversion future throughout. For proto input,
`ChunkStep.apply` adds its original `thenApply(completeChunkGeneration)`; the exact
map result R is therefore a dependent future. For in-memory Imposter input,
R is the wrapper result itself. The registrar indexes R before registering its
possibly-inline release callback, and the adjacent holder handler captures and
removes that exact reference together with map/holder/step/work lineage.
R is correlation, never authority. Original holder failure behavior is retained.
The selected path never runs the original whole supplier after preparation.

## Preparation evidence and lifetime

| Evidence | Actual producer and condition proved | Lifetime and admission consumer |
| --- | --- | --- |
| Winning reservation | Original holder CAS and its dominated map invocation; actual started status, holder/cache identity | Finite synchronous registration; graph binds exact world/server/lifecycle/work/stage |
| Returned R | Actual map result including the original ChunkStep dependent where present | Indexed before inline release; removed at the adjacent exact-reference capture |
| Generation dependencies | Pinned generation/loading FULL step, original status-slot references and successful input objects; actual raw producer completion/release where still active | Rechecked under the existing custody claim fence, including holder/input/slot replacement; records released with original generation retention |
| Conflicting writers | Original winning lower generation calls register their center/write-radius targets; raw result completion releases them | Preparation cannot claim until the actual producers release; release wakes the same pending work outside the metadata gate |
| Generation pins | Original `ChunkGenerationTask.create/runUntilWait` and map acquire/release drive holder reference counts and `generationSaveSyncFuture` | A live pin is required. FULL's own legitimate pins remain until its pending result settles; zero borrows is not the eligibility predicate |
| Construction inputs | Actual level/server, paletted-container factory and dimension object identities; exact proto or Imposter and lower holder slots | Captured at registration and rechecked before claim; no post-load service grant inferred |
| Narrow game-clock turn | `PrimaryLevelData.setGameTime` writer hooks; `DerivedLevelData` delegates to the actual wrapped clock | Required for proto PotentSulfur `setLevel` game-time reads, reserved before regional claim, released after the original body; missing binding is ineligible, conflicting writer records a protocol breach |
| Prepared object | Successful original preparation body under FULL_PREPARE lease/claim; receipt contains lifecycle, work, holder, attempt and actual LevelChunk | Installed before completing the preparation outcome so inline consumers can retain it; no successful receipt after constructor/replacement failure |
| Consumer/callback acknowledgement | Explicit prepared consumer release and the original registration executor callback acknowledgement | Terminal graph remains while either is outstanding; cleanup also requires no claim, settled actual futures/producers and completed original generation retention |

The decisive release test completes an actual delayed SPAWN producer. Its newer
original holder handler publishes SPAWN and reenters the original generation task
before the older raw-producer release callback runs. FULL really registers at that
point, but preparation remains ineligible without acquiring an owner lease. The
actual older release then wakes the same graph, which prepares with its own FULL
pins still live. A separate test proves an older neighboring FEATURES writer
cannot win anew once the actual generation pyramid has reserved FULL.

Preparation uses the existing typed pending stage, delivery pump, route
revalidation, lease, claim and outcome protocol. Stale delivery resolves the new
owner; an actual pending topology transition parks; an unrelated proposal does
not justify parking. Invalid unroutable active demand trips the real barrier,
whereas explicit cancellation, supersession and shutdown keep their distinct
terminal identities. No owner waits for a prerequisite.

Proto preparation constructs the actual LevelChunk, preserves original lower-slot
replacement/reentry, and installs its full-status supplier once. Imposter
preparation reuses the wrapped LevelChunk and performs no second construction.
Constructor and replacement failures preserve the original cause and committed
prefix, produce no successful receipt, and are not retried under the same work.

FULL_FINISH is a separately typed stage whose production prerequisite binding is
absent and whose normal admission therefore returns NOT_READY without a claim.
The missing obligations are initialized-input discovery/query-operation bindings,
D1 identity facts, D2/P owner occurrences, C sessions, S ordering, W named turns
and separate STATUS_PUBLISH(FULL). This is an intentionally unbound finishing gate,
not an implementation of those service validators or of the finishing suffix.
Preparation never calls runPostLoad, loaded/block-entity/tick suffixes or Fabric
finishing callbacks. Its private receipt does not complete conversion, the adjacent
holder result, public FULL, the ready cache, or ticking/sending readiness.

At authentic shutdown/retirement, a private graph-authenticated `Stopped` control
settles conversion and maps only that graph's holder result to original UNLOADED.
Any loser-created FULL subscriber slot is failed/cleared by the original holder
mechanism after the claim exits. A replacement holder is untouched. With a queued
original callback withheld, retirement retains one graph until its callback runs;
with a prepared consumer held, retirement retains one graph until release.
Acknowledged terminal cases reach zero graphs, exact-future indexes, generation
holder records, queued deliveries, clock entries and custody/lifecycle/work roots.
No permanent strong-reference tombstone or second global ownership directory exists.
These are finite graph measurements, not capacity, heap or integrated-service claims.

## Executed verification and artifact

Evidence root: `run/full-registration/session-20260905-135652/`.
Existing XML, reports, JARs and logs were copied before replacement. Each iteration
has preserved command output/exit and XML where tests executed. The first new
`FullProductionRegistrationTest` failed on the missing split connection through
the original async wrapper; it observed unwanted post-load execution. It was
followed by positive production registration and admitted preparation tests.

| Command or check | Exit | Evidence/result |
| --- | --- | --- |
| `gradlew.bat build compileClientGameTestJava --rerun-tasks --no-daemon` | 0 | Fresh baseline 334 tests / 26 suites; `baseline.*`, `baseline-test-results/` |
| `gradlew.bat test --tests "dev.phoenium.chunk.FullProductionRegistrationTest" --no-daemon` | 1 initially; 0 after implementation | Initial `registration-red-01.*`; later focused passes preserved, final class executes 28 cases |
| `gradlew.bat clean build compileClientGameTestJava prepareStage3ClientServer --no-daemon` | 0 | Final `final-clean-build-20.*`, `final-clean-build-20-xml/`; 365 tests / 27 suites, zero failures/errors/skips, all 11 tasks executed |
| Test occurrence multiset | 0 | All 334 baseline occurrences retained; 31 additions = 28 registration cases + 3 strict selector cases; original 48 prototype tests retained |
| `gradlew.bat fullRegistrationHarness --no-daemon` | 0 | `final-harness-20.*`; separate disposable server-only test mod |
| `python scripts/test_full_boundary_transform.py` | 0 | `final-baseline-20.*`; separate baseline server described below |
| Same probe with test-scoped `PHOENIUM_FULL_REGISTRATION_PROBE=1` | 0 | `final-controlled-20.*`; eight installed-Mixin selected-path cases described below |
| Production JAR / external-copy inspection | 0 | `final-verification.json`; 272 entries, server environment, no JUnit, test agent/hooks/fixture/probe, prototype or client classes |
| `git diff --check` and full start-to-delivery diff check | 0 | Final reviewed paths only; exact results captured in session evidence |

Original-method JUnit uses a test-only agent to supply Mixin-equivalent hook
plumbing on the original holder, wrapper and clock classes. It does not replace
the production registrar, original task/refcount mechanisms or preparation helper.
Controlled lower-generation futures, world endpoints, executors and deterministic
owner scheduling are test facilities. Existing 48 prototype cases and explicit
cancellation fixtures with 34 plus eight-times-34 zero-retention assertions remain
unchanged reference evidence. No fixture grants a fake preparation-ready flag.

The real installed-artifact selected run is
`run/full-production-integration/transform-20260905-145045/`, seed
`1956211289291058007`, child exit 0. Its separate test mod executes eight original
registration/preparation cases through the actual production Mixins with **no
javaagent**: proto/Imposter with inline/delayed registration, genuine parent-release
reentry, absent finishing prerequisites, actual clock writer and consumer retention.
The PASS marker reports postLoad=0, publicFull=0 and terminal graph/index/generation
holder/custody=0. These are controlled world/lower-generation inputs and an
**in-memory Imposter**, not a saved-world reload or ordinary selected FULL demand.

The separate baseline run is
`run/full-production-integration/transform-20260905-145039/`, seed
`-7902113717611656236`, child exit 0. It verifies installed selectors, method split,
original callback placement, holder adapters and game-clock hooks, then reports
529 load/EMPTY applies/publications and zero retained load roots. Baseline boot
is not positive selected FULL evidence. Neither run is integrated saved-world or
client acceptance. The known vault diagnostic was not rerun or repaired here.

Final production JAR SHA-256:
`BCD87DE959F1189594330ABB83488A5433B8287DCC3CC59AF62016CE05CBB97D`.
The prepared server and both newly tested external-server copies match it.
Historical server copies and prior evidence are preserved as historical artifacts.
Installed module scope is Fabric API base, command-api-v2 3.1.0+00cb03469e,
lifecycle-events-v1 4.1.4+29b6eb019e and Phoenium, plus Loader/Minecraft; the selected
run adds only the disposable test mod. Exact module/class/log hashes, command
arguments and process exits are in each `acceptance.json` and the session manifest.
The full development Fabric API is not claimed as installed external scope.

Preserved development failures include the initial desired missing-connection
failure; compile-time fixture type/constructor and lambda errors; an overbroad
metadata-monitor assertion and new-enum expectation; an invalid attempt to schedule
a neighbor writer after the real pyramid had already excluded it; and proto
section setup that incorrectly required a light engine. Each was corrected and
followed by focused/full reruns. Their logs/XML remain alongside the final pass.
None is hidden as an unexecuted test or treated as a new approval blocker.

## Next implementation dependency

Implement the production initialized-input discovery and exact consumer/operation
receipt binder feeding FULL_FINISH, using the retained actual preparation receipt.
That is the single next implementation action. Complete service reservation,
finishing, separate publication, capacity/progress and integrated acceptance work
remain open in the [acceptance ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md).
This session stops at registration/preparation and does not enable ordinary FULL.
