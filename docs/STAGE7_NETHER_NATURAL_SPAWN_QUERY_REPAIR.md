# Stage 7 ordinary-profile natural-spawn structure binding

Parent: `a381d614926157f096973fb2fcbd5c27b717c252` on
`stage7/respawn-lifecycle-repair`. The worktree was clean at entry. The parent JAR
remains preserved at 1,793,506 bytes, SHA-256
`DA0EECE3AA23E39348C4A48C4F29E1ABBFDD905A3BBD478A4333019D6E827CFA`.

The repair and verification receipts are under
`C:\Users\Admin\Downloads\Project PHOENIUM\.audit\nether-natural-spawn-20260922`.
`FINAL_ARTIFACT_MANIFEST.json` identifies the committed source, new named candidate,
packaging, final test selections, startup and binding receipts. The Luna handoff is
`LUNA_NETHER_SPAWN_VALIDATION_HANDOFF.md` in that directory.

## Diagnosis

The saved `live-a381d61-20260922-010913` crash at 01:39:58 America/Chicago followed
`NaturalSpawner.isValidSpawnPostitionForType -> canSpawnMobAt -> mobsAt ->
ChunkGenerator.getMobsAt -> StructureManager.fillStartsForStructure`. This is pack
membership validation through the general structure override path. The fortress
special case did not supply this failing query's result: native `mobsAt` reached
the generator. The evidence does not establish whether fortress checking was
skipped or checked and failed, or whether any fortress existed.

The root bypass is at `NaturalSpawnContinuation.Capture`'s runtime binding. In
the parent, the constructor selected `FullGenerationSource.phoenium$fullRegistration()`.
`PhoeniumLiveRuntime.fullRegistration` returns null with authoritative FULL off,
and `Capture.original` then set `entered=false`. The real
`RegionalNaturalSpawnerMixin.phoenium$retainQueries` wrapper called `capture`,
which returned false and invoked the entire original native spawning loop under
the regional owner. Its later pack query had no immutable prepared input.
The failed run explicitly logged `PHOENIUM_FULL STOPPED authoritative=false`.
This is a runtime-selection bypass, not an expired/mismatched snapshot or an
unhandled generator overload. The retained loop and its program counter were
never created for this callback.

| Historical field | Evidence |
| --- | --- |
| World | `minecraft:the_nether` |
| Operation | Native pack-position spawn-list membership validation |
| Execution | Original callback (`Capture.original`), not retained resumption |
| Prepared query / program counter / generation | Not created by this bypass; numeric historical values UNAVAILABLE |
| Query/spawn position | UNAVAILABLE |
| Reference chunk and referenced start coordinates | UNAVAILABLE |
| Rejected requested status / create | `STRUCTURE_STARTS`, `true`, established by the native `fillStartsForStructure` call |
| Preceding query-position status / create | `STRUCTURE_REFERENCES`, `true`, from native `getAllStructuresAt` |
| Exact holder, status future/result, topology, readiness history | UNAVAILABLE; selected synchronous dependency was unfinished at rejection |
| Player | Creative, approximately `(138.2898,72.97628,-38.44465)`; not the query coordinates |

`FullFinishingQueries.predicateChunk` delegated an ordinary query with no FULL
location-predicate context. `BlockEntityChunkQueryDiagnostic` was a wrapper on
that getter, not proof of a Vault operation. `ChunkSynchronousAccessGuard`
correctly rejected the unfinished wait. No FULL preload, permissive Nether
read, empty-list fallback, or weakening of the guard is appropriate.

## Correction

`FullGenerationSource.phoenium$naturalSpawnStructures` is an independent binding
to the existing `FullRegistrationRuntime` structure-query facilities.
`FullProductionMapMixin` implements it against `PhoeniumLiveRuntime`.
`FullFinishingWorldContextMixin` publishes that runtime at completed world
construction, before provider generation workers can access it. The ordinary
profile shares the existing runtime registry, shutdown and failure barrier;
it creates no FULL graph or enclosure and leaves `fullRegistration()` null.
An unpublished runtime cannot be lazily constructed on an owner or producer.

The capture now requires that purpose binding. It takes responsibility at the
existing native `spawnForChunk` wrapper for both selection and pack validation.
The existing retained locals, immutable snapshots, exact native statuses and
creation semantics, native poll-return fence, RNG, owner-release boundary and
resume arbitration are reused. No spawning loop or ordering algorithm was
replaced. Both native and optional-provider generation wrappers join the same
existing structure writer exclusion through this binding, while preserving
their original calls, future identities, failures and generation selection.

The new per-world observation emits at most 32 distinct positive summaries when
`phoenium.test.diagnostics=true`, and one first-failure diagnostic. Markers name
`INITIAL_SELECTION` or `PACK_VALIDATION`, preparation/settlement, reference
presence and fortress-test participation. Details include dimension, operation,
query generation, query position, reference keys, dependency statuses, create
flags, holder/future identities and readiness. Reference/dependency descriptions
are capped at 16 entries. `PREPARED` means a preparation observation (inspect
`complete` for readiness); `SETTLED` proves return through the reserved suffix.
Diagnostic collection failure is suppressed onto the original exception, which
is rethrown. Telemetry is not authority or readiness evidence for another query.

Item/pearl, respawn, tracking, passenger and resident-binding production code is
unchanged. The only live-runtime amendment is the natural-spawn runtime accessor.

## Deterministic and native verification

`baseline-red-results` preserves the unmodified production baseline with only
test changes, plus `baseline-test.patch`. Its 14 tests yielded 11 passes and
three failures: ordinary Nether pack validation, ordinary Nether initial
selection, and the Overworld control. The pack failure includes the real native
selector/validation, generator and structure lookup, then the same synchronous
access guard. Ready data and the original authoritative fixtures passed.

The JUnit agent binds the real `spawnForChunk` entry to the production capture,
falling through to the unchanged native body when inactive. The ordinary fixture
returns null from the authoritative binding and supplies the separate structure
runtime binding when available. The same test source can run on the baseline.
World endpoints, chunk futures and holder publication are controlled. A
deliberately incomplete future is deterministic boundary evidence, not proof of
an installed C2ME scheduling window. The controlled start at `(8,0)` comes from
actual reference data; it is outside the source chunk and ordinary neighbor
closure and within the native structure reference distance.

The expanded comparisons cover initial and pack queries, already-ready data,
subsequent callbacks, two successive missing dependencies, duplicate completion,
Nether fortress weighted selection, structure and piece overrides, and biome
fallback. Every persistent RNG draw, reached query and managed-block poll event
is compared with native execution. Original five-seed ordering tests remain.

The successful-spawn scene uses controlled entity construction, placement world
answers, finalization and insertion endpoints, with the real native selector,
validation, spawning loop and `SpawnState` accounting. It proves that a previously
finalized/inserted/counted mob is not replayed when the next pack query waits,
and that finalization, insertion and global/local counts are exactly once. Its
test-only live-runtime publisher adapter executes the production passenger scope.
Real direct-passenger provisional identity/section-manager composition remains
covered by `FreshPassengerAdmissionTest`; this scene is not a live mob spawning
or passenger acceptance claim.

Existing structure query and fence tests retain source/dependency replacement,
world shutdown, stale/duplicate completion, original failure causes, writer
exclusion, lazy bounds and terminal cleanup. New binding tests cover ordinary
world publication, producer rejection before publication, stable worker lookup,
unchanged FULL selection, native/provider writer future identity, shutdown and
diagnostic cause preservation. Actual declaring types, descriptors and entry
binding are checked by source/bytecode tests and final Fabric exports.

The few intermediate test failures after the first green were incomplete test
endpoints: the new world fixture omitted its constructor-initialized ChunkMap
notification ledger; the successful entity endpoint used entity equality before
ID assignment and omitted the enclosing serial service turn. The saved test
outputs identify those exact failures. They were repaired in the fixtures; no
production assertion, test exclusion, guard or native behavior was weakened.

## Verification limits and live gate

Final commands, selections and counts are in `final-verification/test-receipt.json`.
The affected selection retains the previous 34 classes and adds the natural-spawn,
fence, structure, direct-passenger and world-publication regressions. The broader
`bounded-suite.gradle` is copied byte-for-byte from a381d61's final verification.
There are no new exclusions. The historical unfiltered 1,264 tests / 73 failures
and unresolved 1,225 reconciliation remain separate; this is not an unfiltered
repository-green claim. No clean task is needed; old artifacts remain preserved.

The final candidate is built from committed source. A fresh isolated startup uses
the existing a381d61 pinned-profile manifest, unchanged eight provider JARs and
29 configuration files, one worker and parallel OFF. Only the disposable world
name changes. Readiness, exported mixin binding, normal save/stop, process exit
and configuration/provider hashes are recorded. Startup is not natural-spawn or
Bedrock acceptance.

Luna must use a new isolated run. A recorded copy of the stopped failing world
is permitted; the original evidence world must remain untouched. First test
ordinary baseline, player-only portal travel, Creative Nether traversal toward
the recorded player area, positive `PACK_VALIDATION:SETTLED:references=PRESENT`
evidence, destination block/item interaction and reconnect. An idle server or a
bounded dwell without the marker does not prove this branch. Item/pearl cards
remain separate until that gate passes. The known mob-portal continuation defect
remains open. No Stage 8/9 or whole-stage completion is claimed.
