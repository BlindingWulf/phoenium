# PHOENIUM Stage 3 closure and Stage 4 handoff

Recorded 2026-09-08, America/Chicago. This report supersedes the open integration and
contributor-only status in older FULL ledgers. Historical evidence remains preserved.

## A. Final status

**STAGE3_SERIAL_CORRECTNESS_FROZEN**

The final integrated executable source passed all required closure gates: clean build,
690 focused/architectural tests, strict installed production adapters, real offline-client
fresh FULL, actual region-file payload proof before restart, normal shutdown, actual
persisted reload/reconnect with admitted Imposter FULL, and final normal shutdown.
The saved-vault regression also passed on the same production artifact. Required terminal
ownership, fallback, barrier, unleased execution, graph/input/notification/holder/custody
and service counters are zero. No concrete blocker remains within the scope in H and I.

Stage 3 expansion stops here. No Stage 4 implementation, remote push, public release,
or Stage 3 PASS tag was created. The freeze applies to the declared serial oracle;
the explicit limits in I are not claims of universal Minecraft compatibility.

## B. Authoritative source

* Checkout: `C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium`, branch `main`.
* Frozen executable-source HEAD: `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`.
  Production repair: `328b95c`; subsequent two commits contain client harness changes only.
* Main HEAD at final evidence verification: `f4244c8f90b52c0ff9f82c34748290e8f083af39`;
  its additional change is the current-work documentation. Main was clean at gate dispatch
  and at evidence verification. Closure documentation is committed afterward; the final
  documentation HEAD and final clean state are recorded in the linked closure-head.json
  and in the task's final response. No executable change follows the tested revision.
* Verification checkout: `C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna`,
  `recovery/full-harness-luna`, HEAD `efa82c3eda12fefad1fdcca5d5ea3aac2bbda6e7`, clean.
  Root verified identical `src`, `scripts`, build files and Gradle configuration against main.
  Different commit order/cherry-pick identities do not represent a different executable tree.
* Pinned versions: Minecraft **26.2**, Java **25.0.4.101**, Fabric Loader **0.19.5**,
  Fabric API **0.159.0+26.2**, Loom **1.17.20**, Gradle **9.5.1**.
* Runtime: one gameplay worker; parallel gameplay OFF; view/simulation distance 5;
  offline local `PhoeniumTest0`; no personal account. The Gradle build parallel setting
  does not enable parallel gameplay.

The production filename retains the existing `0.2.0-stage2` version; its name is not
the acceptance status. Root inspected all 542 ZIP entries and compared them with all
198 compiled test/client source-set class paths: **zero class leaks**, server-only
Fabric environment, no JUnit/client package in the production JAR. The existing main-source
opt-in topology adapter is distinct from disposable probe/test source-set classes.

| Artifact | SHA-256 |
| --- | --- |
| [phoenium-0.2.0-stage2.jar](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/final-artifacts/phoenium-0.2.0-stage2.jar>) | `017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534` |
| [full-registration-probe.jar](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/final-artifacts/full-registration-probe.jar>) | `A495EB51CDCEB7C4F96555D804DF1D30FF2839FC4D5F4A263BCC3BF05DE708D6` |
| [full-live-probe.jar](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/final-artifacts/full-live-probe.jar>) | `0D5A0EE266AFAE675042DF377E0A8250DDD13C68FACFA1BF90DF4AAC36FE142F` |

Original build paths: production under the Luna checkout `build/libs/`; both probes
under `build/test-harness/`. The linked copies in main `run/stage3-closure/final-artifacts/`
were copied after acceptance and independently hash-checked.

[Final HEAD and clean-state record](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/closure-head.json>); [Independent artifact/evidence verification](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/verified-evidence.json>).

Actual FULL acceptance enables `-Dphoenium.full.authoritative=true`,
`-Dphoenium.stage3.explicitTopology=true`, and the disposable support/trace flags
`phoenium.test.chunkFixtureSupport`, `chunkBindingTrace`, `diagnostics`, `fullBedSupport`.
The live probe uses `phoenium.test.fullFixturePhase=fresh|reload` and
`phoenium.test.fullFixtureSeedDisk=true` only for the fresh save. These fixture hooks
are in the separate live probe. The installed controlled run uses Mixin export/count
checks and the registration probe, with general-world authoritative FULL disabled;
its selected test worlds still exercise the production registrar and adapters.
The ordinary saved-vault run keeps general-world authoritative FULL disabled.

## C. What Stage 3 implements

* Regional execution uses the existing current-route, topology-generation, owner-epoch,
  lifecycle, work, object-incarnation and stage checks. Gameplay remains on one worker
  with parallel scheduling OFF. Admission is distinct from queued delivery and from
  original work completion; a duplicate cannot release the winner's resources.
* FULL registration follows the original winning generation reservation and async
  producer. It binds the exact holder, cache, returned future, lower-status slot and
  generation lifetime. Preparation, finishing and FULL publication have separate typed
  claims. Preparation constructs or reuses the exact vanilla chunk without executing
  postLoad; finishing invokes the original suffix once; publication remains separately
  admitted and cannot be inferred from private prepared availability.
* Initialized-input receipts distinguish private PREPARED, original PUBLISHED, BIOMES,
  structure lower-status inputs, absence, pending preparation and terminal states.
  Every consumer retains and acknowledges its own use. Original public results keep
  their provenance; baseline-origin results are not relabeled as admitted FULL.
* A distinct owner-read context operation measures and captures dependencies without
  consuming the future FULL continuation. Exact target lifetimes are retained during
  the read. Discovery releases regional ownership before capacity, demand or convergence.
  Final structural validation and the original body use the same regional lease; stale
  evidence leaves the body uninvoked and causes rebuilding after ownership release.
* Saved entity roots/passengers, original parsed-reload block tags, pending block maps,
  mutable NBT aliases and absence observations have revision/occurrence custody.
  Original lazy block-entity creation, serialization and callback order are preserved.
  Busy observations use cancellable producer-release notifications and never wait on R.
* Saved text closes static entity/block/storage/score dependencies recursively. NBT
  path classification follows quoted/unquoted leading fields, match nodes, indices and
  wildcards without applying the path or serializing data during discovery. The original
  Minecraft path and serializer execute after convergence.
* Same-world selectors use D1 entity facts and a finite candidate cut, preserving the
  original filtering, iteration, ordering, random choice, caught exceptions and name/NBT
  calls. Exact earlier saved-root insertions can extend their active original context;
  arbitrary foreign entities cannot acquire authority through a selector.
* Direct live component receipts cover Entity.CustomName, TextDisplay.text, mannequin
  description, audited block CustomName, command LastOutput conditional on TrackOutput,
  and all four front/back raw/filtered sign slots. They retain and revalidate component,
  sibling-list, translation-argument, field-owner and array identities, including absence.
* Registered predicates retain the exact registry generation and declared dependencies.
  Reference/inverted/composite, audited numeric/range/score/storage, environment and
  location predicates execute only at their original call. EntityPredicate captures the
  actual constructor-produced combined subpredicate or sorted array, preserving behavior
  when the caller later mutates its map. Audited same-world entity property and location
  predicates execute against the original candidate with a declared finite footprint.
* Structure clauses retain exact REFERENCES/STARTS holders, futures, results and raw
  reference aliases. Original generation STARTS/REFERENCES/FEATURES writers participate
  in bounded exclusion. Missing starts use a graph-owned loading-only nonpersistent
  demand ticket before R; the original query, piece predicate and UNKNOWN ticket point
  remain in place. No neighboring public FULL completion is fabricated or awaited.
* D1 entity facts, D2 profile/provider state, command storage, clock, scoreboard, world,
  connection and persistence services participate at their original producers, writers
  and consumers. Named child service work preserves ordering and remains separately
  scheduled; admitted work has no raw-thread fallback.
* Capacity accounts plans, edges, consumers, retained facts, component captures and
  transient candidate/structure data before allocation. Normal input limits are 256
  plans, 256 targets/plan, 8192 edges, 8192 consumers and 16 MiB logical fact allowance;
  a separately charged single expansion path permits larger finite operations to progress.
  These are logical accounting limits, not a JVM-heap or Android memory measurement.
* Shutdown drains already registered original generation and child work before terminal
  cleanup. Holder retirement, publication, input acknowledgement, notification failures
  and reentrant stop preserve the primary exception while independently releasing later
  resources. Existing load/EMPTY, POI, metadata, light ingress, routing and packet rules
  remain in force.

## D. Important production corrections

| Symptom | Root cause and correction | Regression evidence |
| --- | --- | --- |
| Admitted service work could outlive its executor or lose ordered profile state | Named service lifetime/drain and atomic D2 snapshot plus queue admission now follow the original owner/child ordering, without fallback threads | FullFinishingServicesTest, FullLegacyOwnerCacheTest, FullServerTaskSchedulingTest, shutdown tests |
| Shutdown registered generation against a terminal FULL runtime | Original generation drain occurred too late; runtime shutdown now follows the original stop/drain boundary | FullGenerationShutdownContinuationTest and actual two-stop client campaign |
| Duplicate delivery could close the active winner's resources | Stage-exit cleanup ran for a duplicate that never acquired the winner's service/input turn; cleanup is correlated with the actual entered stage | FullAuthoritativeFinishingTest duplicate regression, commits 0aca15d/88ce57b |
| Throwing publication notification skipped later retirement | Callback and retirement shared a failure path; independent acknowledgements/retirement now continue and preserve failure ordering | FullInputAcknowledgementTest, registration/publication tests, 87c8780 |
| Live dependency discovery could inspect unowned or expired data | Discovery lacked a distinct non-consuming owner-read operation and exact temporary target retention | FullContextRefinementTest, FullSavedContextInputTest, retention and lease-release cases |
| Component list/argument/field replacement could invalidate discovered dependencies | A root pointer alone did not witness mutable component structure; measured structural receipts are revalidated under the eventual body lease | Live component and command/sign/mannequin saved-context cases |
| Pending command/sign discovery could execute NBT path matching early or miss wildcard slots | Discovery used the interpreted path and first result instead of the audited serializer fields | FullPendingBlockTextInputTest: all raw/filtered slots, wildcard storage dependencies, TrackOutput absence, capacity and growth |
| Pending observation retries/failures could lose wakes or retained invalidation | Observation release, old capture invalidation, capacity refusal and callback failures needed independent handling | Pending12 and saved-context suites; 7974439 capture-failure identity and committed snapshot cases |
| Biome-only predicates demanded FULL or ignored the original cache alias | Footprint and lookup did not follow original status and cached result; BIOMES and its exact retained cache input are now independent of FULL | FullLocationPredicateInputTest, FullFinishingQueriesTest and registered numeric/environment cases |
| Structure predicates lacked connected lower-status references and generation exclusion | References, starts and mutable piece bounds have original producers outside FULL; exact lower-status receipts and generation writer turns now participate | FullStructureInputTest3, FullStructureDemandTest3, FullStructureMutationTurnsTest4 |
| Mutating a constructed EntityPredicate's source map could substitute discovery inputs | Vanilla executes its already-combined predicate, not the later caller map | FullEntityPredicateInputTest4, FullEntityPropertyExecutionTest3, registered entity-location case |
| Installed server failed before startup on mannequin data access | Mixin tried to shadow an inherited method not declared on the target; both mannequin and text display now call that original inherited method through the target type | 0b20d4c; saved-context62 and final installed direct field cases |
| Installed beacon component reads were unresolved despite green JVM tests | Mixin rejected a remappable name shadow on the four-target block adapter. The common exact field is now explicitly unmapped for pinned unobfuscated 26.2 | bf8797d; final 690-test build and installed beacon/block component positives |
| Fresh client reached FULL, then normal save stalled after obsolete admitted publication | A ticket demotion withdrew a FULL subscriber without cancelling its still-owned generation producer. `admittedPublishing` incorrectly treated invalid dependency evidence as obsolete producer execution. `328b95c` removes only that condition; exact STATUS_PUBLISH/owner/settled/retired checks and all invalid-evidence rejection remain. Original vanilla completion and the old UNLOADED subscriber are preserved | `FullAuthoritativeFinishingTest.withdrawnFullSubscriberDoesNotCancelTheOwnedOriginalPublication` fails before the fix with the exact exception for both proto and Imposter, passes after; all 72 affected cases pass; both cases also run installed; final live campaign passes |

The cached DataComponents result review found a separate intentional scope boundary:
an equal-key load can begin after final receipt validation, making original getUnchecked
wait under R. This is not claimed as implemented cache admission. The final code rejects
that context before original FULL and removes unused audit-only production hooks.

The last failure was classified **Production Stage 3 blocker**, not a further save
timeout adjustment. Its deterministic red XML and passing focused log are preserved at
`run/full-resume/session-20260907/closure-withdrawn-publication-before.xml` and
`closure-withdrawn-publication-after.log` in main. Pinned vanilla review confirmed
`GenerationChunkHolder.updateHighestAllowedStatus`/`failAndClearPendingFuture` withdraw
the subscriber, while the already-started `applyStep` handler still calls `completeFuture`.
Actual identity-checked holder unload retirement is a separate boundary.

Material harness repairs are also retained, with no production behavior hidden in them:
the negative publication fixture restores vanilla's delayed-crash field after the test;
installed selector counts measure the actual endpoint; installed private TextDisplay setter
access uses reflection to the original method; service cleanup measures the retained real
service instance after the stop event via process stdout; strict teardown parsing requires
present zero metrics at their actual subsystem markers. Normal save alone has a bounded
30-second RCON response window. Raw runtime failure diagnostics now fail the live gate
before saving, even if numeric barrier counters are zero. Source/probe/parser are current.

## E. Final focused test results

One final clean build ran on the integrated executable tree, before installed/client/vault
acceptance. **690 passing, 0 failing, 0 errors, 0 skips; 59 suites.** Each suite used is
listed below with its exact XML test count. Counts include parameterized invocations.

The clean command exited 0: `BUILD SUCCESSFUL in 14s`, `15 actionable tasks: 15 executed`.
Its console output was captured by the execution tool, not redirected to a separate log.
The post-build XML archive was copied after the campaigns and remains the exact build result.
Root independently recomputed all suite totals and file hashes. No additional clean rebuild
was performed after acceptance.

[Exact archived test inventory and XML hashes](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/final-test-inventory.json>).

| Suite | Tests |
| --- | ---: |
| `dev.phoenium.chunk.ChunkAdapterInfrastructureTest` | 12 |
| `dev.phoenium.chunk.ChunkCustodyModelTest` | 12 |
| `dev.phoenium.chunk.ChunkMetadataAuthorizationTest` | 11 |
| `dev.phoenium.chunk.ChunkMetadataVanillaContractTest` | 1 |
| `dev.phoenium.chunk.ChunkPrerequisiteServicesTest` | 21 |
| `dev.phoenium.chunk.ChunkQueryFailureBindingTest` | 5 |
| `dev.phoenium.chunk.ChunkReloadPrerequisiteOrderTest` | 2 |
| `dev.phoenium.chunk.ChunkRouteClassificationTest` | 10 |
| `dev.phoenium.chunk.FullAuthoritativeFinishingTest` | 8 |
| `dev.phoenium.chunk.FullCallbackAuthorityContractTest` | 9 |
| `dev.phoenium.chunk.FullContextMetadataLockTest` | 2 |
| `dev.phoenium.chunk.FullContextRefinementTest` | 7 |
| `dev.phoenium.chunk.FullEntityFactsTest` | 7 |
| `dev.phoenium.chunk.FullEntityPredicateInputTest` | 4 |
| `dev.phoenium.chunk.FullEntityPropertyExecutionTest` | 3 |
| `dev.phoenium.chunk.FullEntityReferenceDecisionTest` | 10 |
| `dev.phoenium.chunk.FullFinishingQueriesTest` | 13 |
| `dev.phoenium.chunk.FullFinishingServicesTest` | 12 |
| `dev.phoenium.chunk.FullGenerationShutdownContinuationTest` | 2 |
| `dev.phoenium.chunk.FullInitializedInputBindingTest` | 27 |
| `dev.phoenium.chunk.FullInputAcknowledgementTest` | 6 |
| `dev.phoenium.chunk.FullInputCapacityProgressTest` | 3 |
| `dev.phoenium.chunk.FullLegacyOwnerCacheTest` | 8 |
| `dev.phoenium.chunk.FullLegacyOwnerDecisionTest` | 13 |
| `dev.phoenium.chunk.FullLegacyOwnersTest` | 6 |
| `dev.phoenium.chunk.FullLocationPredicateInputTest` | 4 |
| `dev.phoenium.chunk.FullNestedQueryBoundaryTest` | 6 |
| `dev.phoenium.chunk.FullNonselfInputDecisionTest` | 48 |
| `dev.phoenium.chunk.FullPendingBlockTextInputTest` | 12 |
| `dev.phoenium.chunk.FullPostLoadScoreboardContractTest` | 5 |
| `dev.phoenium.chunk.FullProductionBoundaryTest` | 14 |
| `dev.phoenium.chunk.FullProductionRegistrationTest` | 28 |
| `dev.phoenium.chunk.FullPublishedInputBindingTest` | 27 |
| `dev.phoenium.chunk.FullRegisteredPredicateExecutionTest` | 5 |
| `dev.phoenium.chunk.FullSavedContextInputTest` | 64 |
| `dev.phoenium.chunk.FullSavedEntityInputTest` | 39 |
| `dev.phoenium.chunk.FullSavedTextInputTest` | 10 |
| `dev.phoenium.chunk.FullSavedVillagerInputTest` | 10 |
| `dev.phoenium.chunk.FullSelectorPredicateInputsTest` | 12 |
| `dev.phoenium.chunk.FullServerShutdownFailureOrderTest` | 1 |
| `dev.phoenium.chunk.FullServerTaskSchedulingTest` | 6 |
| `dev.phoenium.chunk.FullStructureDemandTest` | 3 |
| `dev.phoenium.chunk.FullStructureInputTest` | 3 |
| `dev.phoenium.chunk.FullStructureMutationTurnsTest` | 4 |
| `dev.phoenium.chunk.FullTadpoleDecisionTest` | 9 |
| `dev.phoenium.chunk.FullWorldgenEnvironmentTest` | 5 |
| `dev.phoenium.chunk.LightIngressAdversarialTest` | 28 |
| `dev.phoenium.chunk.LightIngressProtocolTest` | 16 |
| `dev.phoenium.chunk.LiveTickPhaseBoundaryTest` | 5 |
| `dev.phoenium.chunk.LiveTickReadinessTest` | 4 |
| `dev.phoenium.chunk.ParsedApplyFutureBindingTest` | 26 |
| `dev.phoenium.chunk.PoiLoadBookkeepingTest` | 3 |
| `dev.phoenium.CoreAcceptanceTest` | 26 |
| `dev.phoenium.InteractionAdmissionTest` | 6 |
| `dev.phoenium.LiveMovementAdmissionTest` | 6 |
| `dev.phoenium.RealClientReadinessTest` | 6 |
| `dev.phoenium.RegionLeaseReleaseTest` | 3 |
| `dev.phoenium.Stage3MovementTest` | 14 |
| `dev.phoenium.Stage3OwnershipTest` | 18 |

XML archive: [final-postbuild-20260908-0014](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-resume/session-20260907/final-postbuild-20260908-0014>).

The narrow publication-repair verification separately passed 72 cases across six
suites: authoritative finishing 8, publication binding 27, registration 28,
acknowledgements 6, generation shutdown continuation 2 and shutdown failure order 1.
They are included in the final 690; they are not added again to that total.

## F. Installed production probe

**PASS**, normal child exit **0**, at
`C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-production-integration/transform-20260908-001135`.
Production SHA-256: `017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`.
Registration probe: `A495EB51CDCEB7C4F96555D804DF1D30FF2839FC4D5F4A263BCC3BF05DE708D6`.

| Actual installed marker | Cases / important semantics |
| --- | --- |
| Registration | 8; original winning holder/generation/async registrar, proto and in-memory Imposter, clock producer; preparation-only negatives keep postLoad/publicFull zero |
| Initialized inputs | 19; exact inputs, both cyclic pair orders, larger window, original demand, capacity-to-progress, saved writer, biome rollback and complete acknowledgement |
| Published inputs | 18; original slot/result wrapper, honest baseline origin, independent uses, save pins/unload, 34 repeated cycles and zero true teardown records |
| Finishing | 67 = query 14 + service 7 + D1 7 + context 35 + publication 4 |
| Context breakdown | 31 saved-context cases plus 4 pending text cases; original selector, earlier saved-root selector, interpreted custom name, direct entity/block/text, command/sign/mannequin, lazy block NBT, storage and metadata authority |
| Pending/direct breakdown | Pending legacy 6 + pending text 4 = 10; direct legacy 3 + direct live 3 = 6. These are subdivisions, not extra marker totals |
| Publication | Original finishing and separate publication on proto/Imposter; both withdrawn-subscriber cases; `withdrawnSubscriberOriginalCompletion=true`, `invalidEvidenceRejected=true` |
| Registered numeric/environment predicates | 5; original selector, nested storage, scores, positional environment/BIOMES, random chance; original RNG draws exactly 1 |
| Entity property | 3; original type/NBT, actual constructed predicate snapshot and absent-target short circuit; entity location is additionally one of the 14 query cases |
| Structure | 10 = input 3 + demand 3 + generation exclusion 4; original references/starts, distinct tickets and demand cleanup |

The seven top-level marker totals sum to **130** controlled invocations; two additional
explicit input acknowledgement regressions execute outside those markers (**132** calls
overall). These are installed probe invocations, not additional JUnit tests. All run with
`javaagent=false`; controlled lower-generation results/world endpoints are labeled explicitly.

Actual server load/EMPTY baseline: 529 registrations, exact captures, parsed applications
and publications; POI installs 529. All chunk binding terminal graph/index/POI/light/
metadata/lifecycle/work/custody and synchronous invocation counters are 0; duplicate
applications and barriers 0. Stage 2 shows one worker, parallel OFF, 3 intercepted/dispatched/
executed operations; leases, fallback, ownership violations, unleased operations and barriers 0.

`PHOENIUM_FULL_SERVICES STOPPED PASS stopEvent=true` reports zero service reservations,
active service/world/environment state, addressable entity facts, scoreboard sessions/catchup,
legacy plans/queued/running/cache roots, and `legacyProducerTerminated=1`.
The global FULL marker is honestly `authoritative=false`; selected controlled fixture
teardowns prove their own zero graphs/indices/generation holders/inputs/custody. Actual
authoritative global cleanup is proved separately by both lifecycles in G.

No crash report, shutdown exception, invalid Mixin or skipped adapter error is present.
Installed log SHA: `F59D6B9291E1F0D15F63BD4C0761B26B9A141264FFFF2D22827752664F04CA0A`.
Transformed ChunkStatusTasks SHA: `0B4DEBF86F04DA4D7119EEA182E5E84EFADD99623079D374446E0AFF670D4495`.

[Installed acceptance manifest](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-production-integration/transform-20260908-001135/acceptance.json>).

## G. Real FULL fresh/reload acceptance

**PASS**, at
`C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-authoritative/live-1788844341347`.
World: `FULL-LIVE-1788844341437`; fresh and reload seed: **-4448537946587151198**.
Both lifecycles use exactly production SHA
`017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`
and live probe SHA `0D5A0EE266AFAE675042DF377E0A8250DDD13C68FACFA1BF90DF4AAC36FE142F`.

| Witness | Fresh | Persisted reload |
| --- | --- | --- |
| Offline login/configuration/usable gameplay world and real TCP target receipt | PASS | PASS |
| `FULL_LIVE_OK` | PASS | PASS |
| Exact source chunks | 4 | 4 |
| Original saved readers, each reached once | 8 | 8 |
| Rich villagers / converted frogs | 4 / 4 | 4 / 4 |
| Team restoration and conversion alias removal | PASS | PASS |
| Separate admitted publication | PASS | PASS |
| Original bed checks | 4 | 4 |
| Original collision queries | 8 | 8 |
| Original IMMEDIATE Shulker reads | 8 | 8 |
| Private retained-neighbor reads | 4 | 4 |
| Actual origin | ADMITTED_PROTO | ADMITTED_IMPOSTER |
| Final total registrations/preparations/finishes/publications | 187 each | 101 each |
| Target suffix admission witnesses | 4 | 4 |
| Load/EMPTY registrations/captures/applications/publications | 3213 each | 1005 each |
| Normal save and normal child stop | PASS, exit 0 | PASS, exit 0 |

Before restart the client requested normal `save-all flush`, waited for its normal response,
and read the actual MCA file at
`FULL-LIVE-1788844341437/dimensions/minecraft/overworld/region/r.1.0.mca`.
The NBT reader verified exact coordinates, `minecraft:full` status, two compound roots
per record, both exact tags and exact deterministic UUIDs. The manifest retains the full
serialized root payloads, sector offsets, record lengths and compressed-record SHA-256.
Root also checked the recorded payloads: tadpole Age 24000; villager Age -200, XP81,
farmer level3, scale3, seven bread, one trade, sleeping position [2032,75,-1104], and
all eight team/NoAI/NoGravity/Invulnerable/PersistenceRequired fields. These are disk-read
observations taken before restart, not inferred serializer callbacks. Region files can
be rewritten by the later normal reload save; the hashes below identify the captured
fresh-save records at that required boundary.

| Chunk | Sector offset / sectors / record bytes | Tadpole UUID; villager UUID | Fresh saved record SHA-256 |
| --- | --- | --- | --- |
| [32,4] | 2 / 2 / 5564 | 272f5ba5-97cd-377a-9935-41bd57f3903e; feb49173-7d91-3c47-a5cb-0990bb8292c0 | `CB9A1E1497B84A797A2579DFF67B35667714EAAE7A90C0549A3716389194399D` |
| [33,4] | 10 / 2 / 6054 | 23335a3c-2457-3ca9-8d91-3e01634e5e2d; 24eb46cb-32a2-32f7-a58e-73ef3407e933 | `1B46FF9B3F24C110AD8DC1A8E3FCF7D709BD8E9C35E168FA9EC3EF4F355B63D0` |
| [34,4] | 26 / 2 / 6364 | d0481ff9-7594-31d2-8af5-93189b740274; 077adc9a-e5ed-3f9e-aa68-14fa1f0ebaaf | `D07CB73E387A68D0955115042F4391B96FD5A2A206D5421E7C4D4E61AFF92773` |
| [35,4] | 36 / 2 / 6128 | 6e89835f-ab7a-3a4d-9684-d5143872869d; 22c78472-943b-39bb-aa42-65019ec3c97b | `F5F25550F7FDDF840099732083EB0B014B57D1B41E3DA3A74A645C5BD1F44E3A` |

**Fixture provenance:** vanilla FULL serialization ordinarily omits the legacy `entities`
field. The disposable probe adds exactly these eight phase-specific legacy roots to the
output of the original serializer, then normal region I/O persists them and the original
Minecraft parse/read/post-load path consumes them on restart. It does not patch the saved
world after writing, replace the serializer, or substitute an in-memory Imposter for disk
reload. This deliberately seeded persistence fixture is not spontaneous normal entity
persistence. The richer component/predicate/structure cases in F are controlled installed
coverage; G does not imply those additional cases all occur in its smaller fixture.

Both stops have exactly one authoritative FULL snapshot and separate chunk binding,
Stage 2 and service snapshots. All occurrences of the required final counters are zero:
structure holders/readers/writers/waiting writers/demand tickets; generation holders;
graphs, returned index and deliveries; plans, requested preparations, consumers, fact bytes,
pending notifications, expanded operations and edges; published records, uses, transient
executions and charged record bytes; POI/light/metadata/lifecycle/work/custody state;
leases, fallback, ownership violations, unleased operations and barriers. Normal running
resident publication records and transient generation holders are not mistaken for terminal
residue. Services report the actual stop event, all retained service fields zero, and the
legacy child executor terminated. Runtime failure diagnostics are empty in both lifecycles.
No generation-after-shutdown, crash or stopping exception appears.

The existing movement counter named `replay` measures the one admitted delivery of deferred
packet work; it is not a second execution of a committed gameplay body. Fresh terminal
packet accept/owner/replay/effect/reconcile counts are 7 each, reload 2 each; rejections,
duplicate admissions and fallback are zero.

**Saved-vault regression:** the same production SHA also passes
`C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/nonself-input-decision/saved-vault-20260908-001318`.
The preserved failed world's original vault [143,-31,28], chunk [8,1], was copied into
disposable `STAGE3-SAVED-VAULT-1788844398840`, seed **-663839099974294500**.
It executed 600 ticks, 30 dirty attempts/30 completed, 120 neighbor reads, 30 cross-chunk
reads, 30 owner scopes and 30 ordinary-tick scopes; scopeCleared=true. Exit0, no ticking
failure, normal stop, zero ownership/barriers/fallback and retained service/custody state.
The original saved world remains untouched. This tests the ordinary tick path, outside
FULL finishing, with general-world authoritative FULL disabled.

[Actual fresh/disk/reload manifest](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-authoritative/live-1788844341347/full-acceptance.json>); [Saved-vault manifest](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/nonself-input-decision/saved-vault-20260908-001318/manifest.json>).

## H. Supported Stage 3 scope

The supported serial oracle is the pinned Minecraft 26.2 server and the declared finite
same-world FULL fixtures/vanilla readers, including proto and actual persisted reload,
saved villager initialization, tadpole-to-frog conversion and collision/environment reads,
teams and original service behavior, audited text components and NBT sources, same-world
selectors, the implemented registered predicate families, and connected structure queries.
Original reads of available public data, exact private prepared data and genuine absence
remain distinct. Live text direct fields and pending command/sign fields are supported;
this report distinguishes installed controlled coverage from the smaller actual
fresh/reload fixture; each evidence level retains its stated coverage limits.

## I. Explicitly unsupported/deferred scope

| Boundary | Authority reason and safe behavior | Vanilla relevance |
| --- | --- | --- |
| Cross-world/global selectors and cross-dimension candidate bundles | Current candidate/read claim is same-world; discovery returns an explicit unmet/UNRESOLVED obligation before FULL instead of foreign virtual calls or a synthesized multi-world claim | Valid vanilla selectors/data-pack text can request it; optional extension to the accepted same-world contract |
| Interpreted components.minecraft:custom_name and other cached DataComponents text | Process-wide EncoderCache has original independent encoders; no current claim can exclude an equal-key load after validation. Explicit unresolved before serializer/continuation; no global encoder fence or cache behavior change | Valid vanilla component feature, including custom data-pack text; intentionally excluded from the frozen supported context scope |
| Unknown/unindexed component fields, map implementations and mod serializers | Dependency-producing serializer behavior is not proven by a finite audited producer receipt; explicit unmet before original FULL | Mod extensions and additional vanilla component-bearing fields may reach it; ordinary audited direct fields remain supported |
| Unknown registered predicates or numeric providers and unsupported entity subpredicates | No truthful finite footprint or constructor producer is established; explicit unresolved before execution. Relationship/vehicle/passenger/lightning, support/stepping/movement-affected positions, player ray/look and partial component predicates remain deferred | Some are normal optional vanilla/data-pack predicates; not claimed to be universal entity-property coverage |
| Unknown saved initialization readers or malformed/nonfinite serialized inputs without a finite footprint | Discovery reports the concrete unbound input/invalid saved position before FULL. It never invents readiness or silently retries original effects | Additional vanilla/mod readers and invalid data; exact supported readers are covered separately |
| Arbitrary new distant ordinary-tick dependency demand | The saved-vault regression covers the legal existing readiness path and exact fixture; it is not proof of every future tick/dependency route | Ordinary gameplay can eventually request additional routes; no universal tick-demand coverage claim |
| Rescued/re-promoted holder after withdrawn FULL subscriber | Old publication evidence remains invalid. A later same-holder re-promotion has no newly authenticated publication lineage here; `FullInitializedInputs` returns UNRESOLVED if that resident target lacks original publication evidence. No authority is invented. This is an untested future re-promotion/provenance case, not a failing final acceptance gate; any future support needs exact new lineage rather than clearing invalid | Ordinary vanilla ticket changes can produce this pattern; further support/coverage is explicitly deferred at the stopping rule |
| Actual parallel gameplay, Android/low-memory, arbitrary compatibility mods and unpinned versions | No Stage 4 or portability/compatibility proof was performed | Future stages/platform work; runtime remains one gameplay worker, parallel OFF |

Missing dynamic authority is an explicit unresolved state, not successful FULL. Capacity
pressure, busy ownership and stale evidence are retry/progress states where a supported
finite operation exists; they are not used to mislabel unsupported behavior as complete.

## J. Remaining real blockers

**NONE — Stage 3 closure criteria satisfied.**

The latest failed publication run and deterministic regression were repaired and the
invalidated gates rerun successfully. Explicit unsupported authority and untested future
patterns are listed in I; they are not silently enabled or represented as passing coverage.

## K. Stage 4 handoff

All current gates passed. Stage 4 may now use this tested serial implementation
as its frozen oracle within H and I. Parallel work must preserve exactly-once original effects,
original RNG/iteration/exception order, genuine readiness, private/public result separation,
exact stage and object identity, original lazy construction, owner-free dependency demand,
same-lease final validation, acknowledged lifetime and zero terminal residue. It must not
convert unresolved authority into fallback, polling while R is held, replay, an early
public FULL result or a broad gameplay lock. Deferred scope requires its own authority
work; a serial freeze is not proof of arbitrary parallel Minecraft behavior. No Stage 4
implementation, public release or PASS tag is part of this task.

## L. Recovery information

Important local commits, in addition to the exact source identities in B:

| Commit | Recovery purpose |
| --- | --- |
| 7b49fea | Connected service lifecycle and D1 registration |
| 64071e9 / 2519af0 | Original generation drain ordering; ordered profile writes with snapshots |
| 0aca15d / 87c8780 | Winning service reservation on duplicate delivery; independent publication/input cleanup |
| 685f0ad / dcd7e92 / 4df9ebe | Exact context target retention, partial-failure cleanup and lease-release wake |
| 0adcd38 | Connected structure lower-status references/starts/demand and generation exclusion |
| 40b9790 | Original constructed entity predicate and finite scoped execution |
| df2cf58 through 7974439 | Pending command/sign field discovery, custody, revalidation and retry/failure handling |
| 0b20d4c / bf8797d | Installed inherited data access and multi-target block name shadow repairs |
| bdf9f3a / 8de1c03 | Explicit cached-context boundary and removal of unused cache instrumentation |
| cbe88fe / 80e9818 / 57fecec | Strict actual disk/teardown proof and installed real selector/service witnesses |
| 328b95c | Reproduced and fixed subscriber-withdrawal publication blocker; installed regressions |
| 1375b59 / c31b3f4 | Bounded normal-save response, final-field parsing and explicit runtime failure rejection |

Full logs/worlds are intentionally local and ignored by Git. Final artifact copies,
independent verification, test XML inventory and the post-commit HEAD record are under
`C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/stage3-closure/`.
Do not delete the Luna evidence checkout when preserving this handoff.

| Evidence file | SHA-256 |
| --- | --- |
| [run/full-production-integration/transform-20260908-001135/acceptance.json](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-production-integration/transform-20260908-001135/acceptance.json>) | `F134C1D721D3412826264A3D7FC7C8CF08C557C110CDF8232A07FD6D18514ADB` |
| [run/full-authoritative/live-1788844341347/full-acceptance.json](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-authoritative/live-1788844341347/full-acceptance.json>) | `4544159504E46EA931B8D38A38186CDCD76CF7B48D1945C8CBCA8B3E42D9E74B` |
| [run/nonself-input-decision/saved-vault-20260908-001318/manifest.json](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/nonself-input-decision/saved-vault-20260908-001318/manifest.json>) | `6C91EB781991BC501D78DCAB2997389F50F2CA57B5E9AC3F44DA82E3D7423218` |
| [run/full-production-integration/transform-20260908-001135/server-process.log](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-production-integration/transform-20260908-001135/server-process.log>) | `F59D6B9291E1F0D15F63BD4C0761B26B9A141264FFFF2D22827752664F04CA0A` |
| [run/full-authoritative/live-1788844341347/fresh-server-process.log](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-authoritative/live-1788844341347/fresh-server-process.log>) | `0C390FCFEB0E6DC745B5C9E59A8669959BF8B341D6614FCC0FC2172CBCFCFD92` |
| [run/full-authoritative/live-1788844341347/reload-server-process.log](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/full-authoritative/live-1788844341347/reload-server-process.log>) | `7DB01F82A748358FD8FD9145B895B6CDC6DBDA9E07E39082790050765E8D3869` |
| [run/nonself-input-decision/saved-vault-20260908-001318/server/server-process.log](<C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium-full-luna/run/nonself-input-decision/saved-vault-20260908-001318/server/server-process.log>) | `65E4C9F33FB0B67057155E11E2B6F604F639C5B82F4080C2F33B14CD8523B9A3` |

Preserved unsuccessful evidence includes the installed runs `transform-20260907-231000`
(inherited shadow), `234611` (block name Mixin), `234920` and `235328` (harness defects),
and authoritative `live-1788843351880` (short save response timeout) and
`live-1788843598570` (runtime publication failure followed by save timeout).
These are historical failures, not included in the final PASS counts. The original
vault failure remains in main `run/nonself-input-decision/ticking-20260905-122520/server/`.
The final post-build XML archive is named in E. The independent verification script is
`run/full-resume/session-20260907/verify-closure-evidence.py` in main; it reads evidence
and creates hash-checked artifact copies, without starting a server or changing production.

Rebuild all executable artifacts and all focused/architectural tests from authoritative main:

```powershell
Set-Location -LiteralPath 'C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build fullRegistrationHarness fullAcceptanceHarness prepareFullAcceptanceServer prepareStage3ClientServer clientGameTestClasses --console=plain
```

Rerun the current complete focused inventory without another clean:

```powershell
.\gradlew.bat test --rerun-tasks --console=plain
```

Run the strict installed probe after building current artifacts:

```powershell
$env:PHOENIUM_FULL_REGISTRATION_PROBE = '1'
python scripts/test_full_boundary_transform.py
```

Run the existing account-free fresh/disk/reload campaign and the bounded saved-vault regression:

```powershell
.\gradlew.bat runStage3FullAcceptance --console=plain
python scripts/reproduce_saved_vault_tick.py
```

The vault command requires the preserved original world and the inventory at
`C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium/run/full-resume/session-20260907/vault-saved-inventory.json`,
SHA `ED127C3D7D38255DE2BBC1D148958CDAF18886650A23A3CB5375BC9702C99353`.
Do not substitute a newly placed vault for that saved-state regression. Campaigns create
new disposable worlds; their new seed/run paths must be recorded if intentionally rerun.
One owner runs builds and servers; no competing live campaigns. A future production change
invalidates the corresponding evidence and requires affected tests/installed/live gates.

Delegation used supported explicit `gpt-5.6-sol`/High and `gpt-5.6-luna`/High controls,
with fork_turns=none; lead Astra kept the user-selected effort. Runtime identity metadata
is not exposed. Workers completed and are idle. At the latest measured integration
boundary account-wide Codex weekly use was 34%, reserve 7%; task/worker token usage is
unknown. No reset was redeemed, no extra spending or account/model escalation occurred.
Final process inspection found only the Gradle daemon; no Minecraft server/client remains.
