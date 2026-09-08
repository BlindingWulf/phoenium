# PHOENIUM Stage 4 closure and Stage 5 handoff

**PASS — STAGE4_PARALLEL_CORRECTNESS_FROZEN.** All17 required gates passed on the final
production artifact. Stage5 is ready to begin and has not been started. The freeze commit is
the repository HEAD resolved by `git rev-parse STAGE4_PARALLEL_CORRECTNESS_FROZEN`.

## Authoritative source and scope

- Branch: `stage4/regional-world-services`.
- Production implementation commit: `7da528d` (terminal object-delivery acknowledgement).
- Final shared fixture correction: `eb7a3ac` (native ticket expiry after an acknowledged save).
- Final repository revision is the commit resolved by the freeze tag; no production changes
  followed `7da528d`. The closure manifest records the full implementation and freeze revisions.
- Production: `run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar`.
- SHA-256: **43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E**.
- Minecraft26.2, Java25, Fabric Loader0.19.5, Fabric API0.159.0+26.2, Loom1.17.20,
  Gradle9.5.1. Dependency pins are unchanged. The historical JAR filename/version is retained.
- **One gameplay worker; parallel gameplay OFF.** The requested milestone name describes the
  regional correctness foundation for later parallel execution. It does not claim concurrent
  gameplay, scaling, Android suitability, or Stage5 implementation.
- Installed client gates use `-Xmx2G`, `phoenium.full.authoritative=true`, the explicit finite
  test topology and separate diagnostic probe; the production artifact contains no test authority.
  Baseline controlled/vault/adverse gates retain their declared original modes. All run on
  disposable local servers/world copies and require normal process completion.
- Frozen Stage3 source: `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`.
- Frozen reference JAR: `run/stage3-closure/final-artifacts/phoenium-0.2.0-stage2.jar`, SHA-256
  **017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534**. Unmodified.

## What Stage 4 implemented

The serial scheduler executes multiple logical regions through current directory routes,
object incarnations, topology generations and regional leases. These identities remain distinct.
Queue insertion grants no authority. Native callbacks validate their actual current owner and
footprint at delivery. The coordinator performs quiescent merge/split transitions and transfers
scheduled work, deferred events, tracking state and retained envelopes without replaying effects.

Regional block/fluid scheduled ticks preserve native trigger time, priority, sub-order,
deduplication, clear/copy behavior and restored tick semantics. Regional block events preserve
original enqueue/dispatch order, nested events, return values, deferred eligibility and actual
client deliveries. Migration continues to execute actual A and B bodies on the one worker.

Entity tracking reserves its named service, takes current entity/player/passenger/recipient
receipts, uses native effective tracking ranges and the server view cap, and preserves original
pairing, sends and removals. Movement first validates its geographical admission, prepares the
complete synchronous tracking footprint, then captures the final generation-fenced ticket.
Already accepted movement settles before tracker selection so the selected cut remains current.

Entity facts and ownership follow original native storage and visibility lifetimes. Persistent
section insertion/removal covers hidden entities without publishing them into the native visible
selector. Native tracking start/end provides truthful addressability. The original saved reader
may discard a tadpole before its later native lookup insertion; that later insertion still
publishes ownership while preserving the removed flag and original conversion behavior.

Native entity management runs at its original relative phase through an owner-free coordinator
boundary. Per-chunk entity serialization/removal takes its exact entity/passenger closure.
Native chunk executor tasks preserve the same Runnable and executor, reserving the existing
named FULL service boundary before post-processing produces regional scheduled ticks.
Startup tracker bootstrap similarly reserves its named service before reading saved trackers.

Regional save cuts correlate the actual selected holder, owner and source identity, detach
immutable data before asynchronous encoding, retain it through the original write future and
release only after acknowledgement. Failed writes restore dirty state and preserve failures.
A retired holder that remains in the native visible map can borrow only its existing exact
retention; native visible-map replacement or close releases that lifetime. A new retired source
still cannot gain authority by being retained as a Java object.

Shutdown closes gameplay admission, drains accepted work without a gameplay lease, waits for
native saves and their acknowledgements, then runs ordered native cleanup. Terminal rejection
of a queued object request now acknowledges its exact retained correlation without executing
its body. Rerouted or deferred requests keep custody. Ended entity indexes and ownership records
close after legitimate settlement. The adverse path preserves its known primary failure while
retaining actual outstanding storage work until acknowledgement.

## Major production defects fixed

| Symptom | Root cause and correction | Regression or installed evidence |
| --- | --- | --- |
| Native spawn-state construction lacked an entity owner | Ownership publication missed native persistent/world-generation tracking insertion. Publish at actual tracking start. | `0ac44dc`; native entity/facts tests; final real-client and FULL gates. |
| Tracker relation receipt became stale after movement | Receipt outlived current owner-published route. Refresh at original add/move/remove callbacks while preserving incarnation. | `0ac44dc`; native movement/replacement checks. |
| Native demotion removed tracker without service reservation | Start/end callbacks entered regional tracking outside named lifecycle turn. Reserve ENTITY_LIFECYCLE before original callback admission. | `530eca0`; installed demotion/restart and tracking tests. |
| Saved or hidden entities crossed owner boundaries during load/unload | Persistent management ran inside A's final owner phase and lacked exact storage-section lifetime. Preserve original ordering at an owner-free cut; lease exact storage entity/passenger closure. | `2929375`; hidden-storage/native phase tests and final reload. |
| Movement/tracking demanded unrelated terrain or missed synchronous recipients | Maximum type radius and incomplete moved recipient/passenger closure. Use actual effective range, native view cap, seen recipients, swept path and final recapture. | `4ed718f`, `a36d4cb`, `4660f7d`; regional/movement tests and real5/8 client. |
| Native tracker selection went stale during its first drain | An earlier admitted movement executed after selecting receipts. Drain accepted work before the original selection cut. | `a36d4cb`; selection regression and installed restart. |
| Eager save reached a retired holder still natively visible | Java source retirement preceded native visible-map replacement. Borrow exact existing visible retention until native promotion/close; capture entry value before iterator removal. | `4660f7d`; retention tests preserve new-source retirement rejection. |
| FULL saved-reader conversion later lacked ownership | Removed-during-load entity was subsequently inserted by native lookup, but the ownership callback skipped removed entities. Honor actual insertion without resurrecting the entity. | `ce3f980`; `FullEntityFactsTest.removedSavedReaderStillAcquiresOwnershipAtLaterNativeLookupInsertion`; FULL fresh/reload. |
| Chunk post-processing produced a tick outside its service turn | Original chunk executor could run between world ticks. Wrap the same native Runnable/executor in existing SERVER_TASK service scheduling. | `ce3f980`; `FullServerTaskSchedulingTest` and authoritative FULL. |
| Reload tracker bootstrap lacked ENTITY_REGISTRY service | Startup binding read preexisting trackers before its named reservation. Reserve the original startup binding operation. | `b60591e`; full818 suite and authoritative persisted reload. |
| Intermittent stop remained in admitted-work drain | Terminal delivery rejection dropped the inbox envelope but did not release the retained packet correlation. Notify exact terminal rejection, remove its retained request, increment rejection without body/lease. | `7da528d`; two deterministic tests fail without fix and pass with it; final normal drains. |
| Ended runtime retained entity ownership/index entries | Cleanup ended native lifetimes without closing derived metadata. Close ended indexes after scheduler/save settlement without world mutation. | `2bb1618`; lifecycle tests and terminal native logs. |

The captured5Y thread dump establishes a wait inside `drainAdmittedWork`, without an active
gameplay lease or monitor. It does not identify the old packet correlation. The lost terminal
acknowledgement is reproduced deterministically from the production gateway lifecycle; the
final installed runs verify empty admission before cleanup. No timeout extension, weakened
predicate, forced success, replay or blanket packet clearing was used to make the drain pass.

## Acceptance harness repairs and evidence isolation

Installed identity is verified before each first launch and restart. Reference mode requires
the exact frozen hash; candidate mode requires the selected built hash and rejects the frozen
artifact. Gradle stages one production input directly. Unrelated FULL probes are excluded from
Stage4 client staging. Seven actual-file negative identity checks are preserved in
`run/stage4/artifact-identity-checks.json`; swapped artifacts cannot silently create reference evidence.

The client is an offline test identity over a real loopback TCP connection, with actual configured
render/view/simulation distance5 or8. Native FULL futures, native movement acknowledgement,
server readiness and received client terrain are independent prerequisites. Teleport fixture
routes use the actual coordinator and finite explicitly declared native generation support.
There is no direct directory publication or fabricated readiness. Both A and B bodies remain
mandatory after merge/split; an independently legitimate convergence is not treated as a fake split.

The final5/5 pair retains its identical accepted +4 unload boundary. The final8/8 pair uses the
corrected shared fixture: native save-all flush at +4, ticket release, two native clock steps,
then observation at +6. The correction addresses the frozen reference's actual UNKNOWN ticket:
native expiry cannot advance while its holder is not ready for saving or while the clock is
paused. The original saved arrays remain strict. The5/5 pair remains valid on the final JAR
and its matching immutable inputs; cross-distance elapsed times do not represent identical work.

FULL fixture eligibility is carried across the Stage4 detached snapshot by a separate test-only
mixin; the production serializer knows no fixture field. The original serializer, writer,
eight actual MCA roots and saved readers remain mandatory. Native insertion/removal/lookup
diagnostics are observational. Earlier FULL E/F inventory failures, candidate5Y stop timeout,
candidate5Z/reference8C frozen-ticket failures and invalid artifact/probe runs remain preserved
as failures. They are not counted toward accepted gates.

## Strict comparator contract

`scripts/compare_stage4_trace.py` requires PASS input traces and compares ordered gameplay
effects, callback/result ordering, positions, block/fluid identities, priorities, explicit
sub-orders/ties, duplicate effects, clock inputs, original unpack, final serialized scheduled
tick arrays and saved delays. Delivered tracking packets retain order, recipient, UUID identity,
packet type and primitive payload fields. Native entity IDs map only through matching stable UUIDs.

Gameplay and delivered tracking are separate ordered channels because native ServerEntity
advances its send counter while the simulation clock is frozen. Their unrelated interleaving
and tracking delivery gameTime are not required to match. Neither channel is sorted into a set.
Native scan-only bookkeeping and intermediate serializations remain in raw evidence; final
per-chunk saved state and actual deliveries are compared. Actual configured FULL cohorts must
be complete; extra native border holders do not replace the configured radius.

Only absolute clock origin, authority/lease/generation metadata, Java identity strings and the
unrelated native global fluid sub-order offset are normalized. Explicit fixture order and
within-bucket differences/ties remain strict. Candidate A/B topology and service bodies are
mandatory separately; Stage3 need not emit Stage4 topology events. Unknown event kinds fail.

Eight comparator tests pass, covering changed priority/order/ties/payload/saved delay, duplicate
effects, foreign packet identity, incomplete FULL/unknown kinds, callback ordering, packet
ordering and the permitted normalizations. Evidence: `run/stage4/final-comparator-negative-controls.log`.

## Deferred items and Stage 5 contract

Stage5 may introduce additional gameplay workers and measure scaling, but must preserve the
frozen native effects, current-owner admission, incarnation/generation fences, exact footprints,
legitimate readiness, coordinator transitions, original save futures and terminal acknowledgement.
No broad world gameplay mutex, peer-region lock ordering, owner wait on another owner, unowned
shutdown mutation, foreign raw mutation, silent fallback or committed-effect replay is permitted.

C2ME/Lithium/FerriteCore/PandaAntiLag compatibility, Android deployment/tuning, arbitrary
cross-world selector expansion, cached DataComponents authority redesign and unrelated Stage3
extensions remain deferred. These finite installed campaigns are correctness evidence, not a
long-duration stress test or a concurrency/performance benchmark. Stage5 has not been started.

## Final gate results

The machine-readable closure is `run/stage4/final-artifacts/acceptance-manifest.json`.
It validates the final artifact identities and completed reports, retains the native terminal
receipts and records SHA-256/size for151 evidence files, including immutable client MCA copies.
It references the final freeze revision separately from the production implementation revision.

| Gate | Result | Accepted evidence |
| --- | --- | --- |
| 1 Regional scheduled ticks | PASS | Both final candidate traces; actual3 block and3 fluid callbacks each, exact native ordering and migrated A/B bodies. |
| 2 Regional block events | PASS | Each candidate:6 dispatches, nested enqueue/results,4 actual block recipient packets, deferred eligibility/effect. |
| 3 Real-client tracking | PASS |3 pairs and3 removals per candidate;12 delivered tracking packets at5/5 and19 at8/8; full ordered payload comparison. |
| 4 Merge/split migration | PASS | Each candidate:1 explicit merge,2 split observations,12 service body observations; strict A/B service and tracking requirements. |
| 5 Truthful save cuts and acknowledgement | PASS | Regional save tests, controlled native FULL/save probe, final save/flush/stop receipts. |
| 6 Healthy shutdown | PASS | Both processes in both final candidate campaigns exit0; admission is empty before cleanup. |
| 7 Adverse pending-write shutdown | PASS | `run/stage4/adverse-final-20260908-125857/acceptance-manifest.json`. |
| 8 Final shared real-client workload | PASS | `finish-candidate-5-aa` and `finish-candidate-8-e`. |
| 9 Configured5/5 and8/8 | PASS | Actual native FULL cohorts and actual client configured distances; no reduced radius. |
| 10 Exact persisted MCA scheduled tick | PASS | Each `evidence/restart-validation.json` and `evidence/mca-before-restart`. |
| 11 Native restart/unpack/deadline effect | PASS | Exact saved delay equals unpacked delay; callback count0 before,1 at,1 after. |
| 12 Frozen-reference semantics | PASS | `run/stage4/final43-comparison-5.json` and `final43-comparison-8.json`. |
| 13 Affected Stage3 regressions | PASS | Authoritative FULL fresh/reload, controlled FULL, saved vault, complete movement/packet/chunk/routing/cleanup suites. |
| 14 Complete suite | PASS |818 tests /87 suites,0 failures,0 errors,0 skips; `final-suite-terminal-delivery-results.json`. |
| 15 Same final production artifact | PASS | All final candidate/FULL/controlled/vault/adverse recorded and installed hashes equal43AAC...D81E. |
| 16 Required terminal residue | PASS | Actual pre-cleanup drain and post-stop runtime/topology receipts, plus FULL service and adverse cleanup evidence. |
| 17 Production isolation/packaging | PASS |648 entries; server-only; no JUnit, acceptance/client-test classes, test references, client/test mixins or instrumentation. |

## Final5/5 and8/8 evidence

All paths in this table are under `run/stage4/`; each campaign has `evidence/STAGE4-CLIENT.json`,
`STAGE4-CLIENT-before-restart-trace.json`, `restart-validation.json`, phase identity receipts,
both native server logs and process metrics. Raw event counts include stop serialization and
internal observations; they are not required to equal reference raw counts.

| Distance | Candidate / reference | Raw candidate events | Strict canonical events | Matching digest |
| --- | --- | ---: | ---: | --- |
|5/5| `finish-candidate-5-aa` / `finish-reference-5-j` |1124|123|`6CBE83B4A6C44D3D92FE62BAA9A0F0AC2B2688B1325C821528F95E3198EE375A`|
|8/8| `finish-candidate-8-e` / `finish-reference-8-d` |1562|140|`D79224FF6BA91BED9F9DE30B4AA47178EDC746F4433FE8EAC7D379CFEAB4EF2C`|

The5/5 candidate's powered stone button at `(120,65,9)`, overworld chunk`(7,0)`, priorityLOW,
explicit order99, was persisted with delay188. Original unpack observed currentTick725 and
trigger913. At912:powered=true/callbacks0; at913:false/1; at914:false/1.

The8/8 candidate's same exact button was persisted with delay186 after the two additional
shared native unload steps. Unpack observed currentTick693 and trigger879. At878:true/0;
at879:false/1; at880:false/1. Its paired reference has the same delay186 and matching relative
clock inputs. No delay was normalized or rewritten. Both candidate/reference pairs independently
passed exact dimension/region/chunk, block type/position/priority, powered-state and unique tick
identity validation against the copied MCA bytes. The native far block/fluid unpack delay remains316.

The8/8 readiness gate observes the actual requested289-chunk simulation square plus the required
native border/visible cohort. Its successful native receipts are retained in the raw trace;
extra border holders are not evidence for missing requested chunks.

## FULL, vault, adverse and packaging

- **Authoritative FULL fresh and reload:** `run/full-authoritative/live-1788890286215/full-acceptance.json`.
  Final43AAC... production, seed`-5846470989536090341` in both lifecycles. Each phase:4 sources,
  8 saved readers,4 rich villagers,4 frogs, preserved teams, separate publication,4 bed checks,
  8 collision queries,8 immediate shulker reads and4 private-neighbor checks. Fresh origin
  ADMITTED_PROTO; persisted reload ADMITTED_IMPOSTER. Actual8-root MCA payload proof and real
  TCP chunk receipt passed. Both exit0; FULL/chunk/service/index cleanup and forbidden counters zero.
- **Controlled installed FULL/save:** `run/full-production-integration/transform-20260908-125847/acceptance.json`.
  PASS, exit0, same final JAR. Native transformed registration, finishing and retained save/write
  acknowledgement checks pass. Log SHA`22212FD2AECAA15D63237F83394F73356E25EC158B302D1FC1B2358D051A4701`.
- **Saved vault:** `run/nonself-input-decision/saved-vault-20260908-130002/manifest.json`.
  Disposable copy; preserved source untouched. Actual vault`(143,-31,28)` in chunk`(8,1)`:
  597 ticks,30 dirty attempts/30 completions,120 neighbor reads,30 cross-chunk reads,
  30 owner scopes and30 ordinary scopes. Exact dirty lifetime, cleared scope, normal exit0,
  clean FULL/service/runtime teardown and zero forbidden residue.
- **Adverse pending native write:** `run/stage4/adverse-final-20260908-125857/acceptance-manifest.json`.
  PASS, exit0, same final JAR and unchanged probe
  `01F5B32A770196482058AC5977CFB9A2E89EFA19BEB46D3F954753783BE21B17`.
  One exact expected callback failure,1 original write/1 outcome,0 write failures,
  pendingAtFailedStop1, ordered wait→write acknowledgement→15/15 cleanup entries,
  resource sets3/3/3/4/1/1, pending saves/dispatcher/leases/named reservations/addressable
  entities/scoreboard sessions/legacy plans all0. The intentionally failed scheduler remains
  negative evidence; **durablePersistenceClaim=false**. Log SHA
  `4828E23F6FC34192E152CA2F86ABBE3F49F5EFF939E841F6855784B1551CFA97`.
- **Packaging:** `run/stage4/final-packaging-terminal-delivery.json`, PASS on final hash,
  648 entries, all six audit checks true and no forbidden entries/references. Full suite log:
  `run/stage4/final-suite-terminal-delivery.log`; exact counts in its corresponding results JSON.

## Zero-residue health

All four accepted candidate process lifecycles have actual `STAGE4_DRAIN_COMPLETE` receipts
before native cleanup: retained packets0, chunk requests0, regional dispatcher0, pending saves0,
completed-but-unacknowledged saves0 and all runtime inboxes/transition mailboxes0.
Post-stop receipts show pending topology transitionsfalse, convergencefalse, transitioning
regions0 and leases0. Native runtime counters show entity/player ownership records0,
duplicate/bypass/fallback/ownership violations/unleased/barriers0 and no producer chunk mutation.
Regional shutdown reports admissionClosed=true, queuedServices0 and pendingSaves0.
FULL retained graphs, published records/uses, input plans/edges/notifications and charged
retained bytes are0. FULL/vault/adverse named-service cleanup reservations are0.

Positive cumulative counts such as captures, publications and acknowledgements record completed
work; they are not terminal residue. The earlier same-final-JAR candidate8D first shutdown
explicitly observed44 pending saves and completed their acknowledgements before drain success.
That supplemental healthy run is preserved; the strict final8/8 pair is8E/reference8D.

## Lightweight elapsed time and memory

These are one accepted process observation per phase, from child process launch through normal
exit. Windows PeakWorkingSetSize records process peak resident memory; working set is also
sampled every250ms. Each metric file records PID, sample count and exit0. Other final installed
campaigns were not run concurrently with these measurements. This is not a benchmark or a
scaling conclusion; startup, terrain generation, client pacing, disk work and tracing are included.

| Distance / artifact | First lifecycle seconds | First peak MiB | Restart seconds | Restart peak MiB |
| --- | ---: | ---: | ---: | ---: |
|5/5 candidate43AAC...|48.238|885.61|15.869|695.29|
|5/5 frozen Stage3|47.736|886.96|15.673|691.41|
|8/8 candidate43AAC...|50.140|772.78|18.167|694.29|
|8/8 frozen Stage3|48.443|767.20|15.665|685.11|

The accepted runs show no obvious time or memory blow-up relative to their paired reference.
No throughput, percentile latency, Android memory budget or multi-worker speedup is claimed.
The native Windows OSHI startup process-enumeration warnings present in preserved logs are
separate from the successful direct child-process memory measurements and gameplay health.

## Recovery commands and important commits

Run from `C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium`. Use a new run ID/directory for
any future diagnostic so accepted logs and world snapshots remain untouched. Do not clean the
audited Loom native cache casually: its earlier byte drift and recovery are recorded in
`docs/STAGE4_CURRENT_WORK.md` and `run/stage4/native-cache-difference.json`.

```powershell
git status --short
git rev-parse STAGE4_PARALLEL_CORRECTNESS_FROZEN
Get-FileHash run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar -Algorithm SHA256
.\gradlew.bat build fullRegistrationHarness fullAcceptanceHarness adverseStopHarness stage4AcceptanceHarness prepareStage3ClientServer --no-daemon --console=plain
.\gradlew.bat test --tests dev.phoenium.Stage3OwnershipTest --no-daemon --console=plain
.\gradlew.bat test --no-daemon --console=plain
$env:PHOENIUM_DIAGNOSTIC_SEED='-5846470989536090341'
.\gradlew.bat runStage3FullAcceptance --no-daemon --console=plain
$env:PHOENIUM_FULL_REGISTRATION_PROBE='1'
python scripts/test_full_boundary_transform.py
python scripts/run_stage4_adverse_stop.py
$env:PHOENIUM_SAVED_VAULT_PORT='25587'
python scripts/reproduce_saved_vault_tick.py
python scripts/audit_stage4_packaging.py --output run/stage4/new-packaging-audit.json
python -m unittest discover -s scripts -p test_compare_stage4_trace.py -v
```

Native installed client workload, replacing `future-candidate-5` with a new unique ID:

```powershell
.\gradlew.bat runStage4ClientGameTest -Pstage4RunId=future-candidate-5 -Pstage4ServerDirectory=run/stage4/future-candidate-5 -Pstage4EvidenceDir=run/stage4/future-candidate-5/evidence -Pstage4ViewDistance=5 -Pstage4SimulationDistance=5 -Pstage4PacketTrace=true --no-daemon --console=plain
```

For8/8, use a new ID and set both distances8. For a paired frozen-reference run, add these
quoted PowerShell arguments to the same distance/workload command:

```powershell
'-Pstage4Reference=true' '-Pstage4ReferenceProduction=run/stage3-closure/final-artifacts/phoenium-0.2.0-stage2.jar'
```

Read-only validation of the accepted evidence:

```powershell
python scripts/validate_stage4_client_restart.py --evidence run/stage4/finish-candidate-5-aa/evidence/STAGE4-CLIENT.json --output run/stage4/recheck-5.json
python scripts/validate_stage4_client_restart.py --evidence run/stage4/finish-candidate-8-e/evidence/STAGE4-CLIENT.json --output run/stage4/recheck-8.json
python scripts/compare_stage4_trace.py --reference run/stage4/finish-reference-5-j/evidence/STAGE4-CLIENT-before-restart-trace.json --candidate run/stage4/finish-candidate-5-aa/evidence/STAGE4-CLIENT-before-restart-trace.json --require-lifecycle --output run/stage4/recheck-comparison-5.json
python scripts/compare_stage4_trace.py --reference run/stage4/finish-reference-8-d/evidence/STAGE4-CLIENT-before-restart-trace.json --candidate run/stage4/finish-candidate-8-e/evidence/STAGE4-CLIENT-before-restart-trace.json --require-lifecycle --output run/stage4/recheck-comparison-8.json
```

The exact bundled Python used for closure is
`C:\Users\Admin\AppData\Local\Programs\Python\Python312\python.exe`.
Full evidence consolidation is reproducible with `run/stage4/assemble-final43-evidence.py`;
it reads and asserts completed reports rather than launching games or upgrading partial results.

Meaningful recovery commits: `0ac44dc` ownership/artifact identity; `530eca0` tracker lifecycle;
`2929375` persistent entity management; `4ed718f` effective tracking ranges; `a36d4cb` native
visibility and selection; `4660f7d` visible-save retention/movement closure; `2bb1618` ended
indexes/configured FULL; `ce3f980` removed native insertion/chunk executor; `b60591e` bootstrap;
`7da528d` terminal acknowledgement; `eb7a3ac` shared native ticket/save boundary. Earlier commits,
failed-run paths and exact resumable history remain in `docs/STAGE4_CURRENT_WORK.md`.

**Stage4 is closed. The working tree at the freeze commit is clean. Stage5 is ready but unstarted.**
