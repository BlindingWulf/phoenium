# Stage 5 execution and acceptance

Status: **IN PROGRESS; no Stage 5 PASS claimed.** Branch `stage5/bounded-parallelism`.
Stage 6 and compatibility work remain out of scope.

## Immutable starting point

Verified clean starting HEAD and peeled Stage 4 tag:
`1a82b701fce53395481f251347ca9a4deffc12d7`. The annotated tag object is
`8510dca7094c03caee5e4844434883fe5d249090`.
`run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar` hashes to
`43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E`.
This is the one-worker Stage 4 reference. Stage 3 is a distinct older reference.
Neither freeze, artifact, evidence tree nor saved fixture is to be rewritten.

Read the complete Stage 4 closure, current roadmap, testing/benchmark requirements,
threading invariants and Stage 4 service contract. The user authorizes Stage 5
parallel development/acceptance; historical one-worker restrictions are superseded
only for this work. Account-free TCP clients, disposable worlds and all dependency
pins remain mandatory.

## Execution policy

The selected lead configuration is preserved. Astra owns production engineering;
the optional substantial implementation worker defaults to explicit Astra Medium,
in isolated edits. At most one subagent may be active, with no recursive delegation.
Explicit Luna at the lowest adequate effort handles bounded read-only evidence or
existing commands; it may not repair production/harness/comparators or weaken tests.
Optional Sol High review replaces that worker. The lead owns integrated builds and
installed campaigns. Tool-selected routing, not worker names, records configuration.
If supported model selection is unavailable, work single-agent. No paid API use,
credits, account changes, external publishing or automatic extra agents.

Initial account telemetry: main Codex window 31% used / 69% remaining; reserve
bucket 7% used. Per-worker token attribution is unavailable. One helper was
explicitly selected with `gpt-5.6-luna`, `low`, for the unchanged baseline only.
It completed once: 818 tests / 87 suites, zero failures/errors/skips, Java
Temurin 25.0.4.1, 18.793 seconds. Log:
`run/stage5/baseline-20260908/test.log`. Latest account telemetry: 40% main used /
60% remaining; reserve 7% used. No credits consumed; per-worker accounting unavailable.

## Comparison contract (before candidate results)

Use frozen Stage 4 plus the candidate's one-worker implementation as controls.
Preserve the Stage 4 comparator's exact ordered scheduled-tick, block-event,
tracking/recipient/payload, callback/result and saved-delay channels and all negative
controls. Keep original MCA bytes and raw traces. Do not sort these channels or
normalize saved delays, priority, explicit sub-order/ties or duplicate effects.

New independently admitted regional callback cohorts may interleave across distinct
owners. Their original order within an owner, exact callback identity/count, input
clock, outputs and before/after phase relationships remain strict. Shared or
interacting callbacks create ordered boundaries. Worker/lease identity and timing
are evidence, not semantic equality fields. One seed alone is not deterministic
input. Candidate/reference artifact swaps must fail before launch/restart.

## Implementation direction and pending gates

Audit found that the scheduler is serial and that native chunk/entity phases and
the shared finishing-service turn retain serial mutable state. Pooling arbitrary
callbacks would be unsafe. Introduce bounded, explicitly admitted regional cohorts
with current claims and publication/lifecycle fencing; keep native shared phases
ordered. Original callbacks must run once, with no fallback after selection fails.
Audit each admitted callback's native shared accesses before enabling its cohort.

Current implementation: 1..8 fixed gameplay workers (default 1), at most one finite
batch of one task per worker, 1..256 original callbacks per regional task (default
64), and optional 4096-entry original-body interval history. One-worker execution
uses the same selection/body implementation inline. Original furnace cooking
between completion/fuel boundaries and hopper cooldown/disabled branches are the
first locally admitted bodies. All other original callbacks still execute in their
ordered phases; no supported demand is refused or replayed. Native source was read from the pinned local archive;
no external implementation was imported.

Delivery selection is distinct from a gameplay lease: topology fences both, but
terminal packet rejection acknowledges without mutation authority. Accepted batch
cleanup joins every original body. Metadata ingress is fenced against stop; stale
physical inboxes remain deliverable; generic service submission cannot mint worker
eligibility. A bounded explicit `gpt-5.6-sol`/`high` review identified these races;
the lead repaired them. Both helpers are finished. The 829-test complete suite
passed (88 suites); focused tests include actual two-thread model overlap,
failure with a held peer claim, merge admission, stop joining, capacity, conflicting
owners, generic eligibility rejection and topology contention. These model probes
do not substitute for installed original gameplay evidence.

Final eligibility now runs under all retained cohort claims, before any original body.
Concurrent ingress cannot invalidate that cut. A declined preflight commits no body;
the entire bounded segment is acknowledged and then invoked in original serial order.
Native ticker windows retain at most workers * callbacksPerRegion references. The
recipe map guard admits only exact vanilla cooking implementations. Native direct
holder sets publish immutable membership before worker access. Crash reporting reads
the exact bound native chunk; dedicated failed stop still terminates native executors
and clears running state while preserving the initiating exception.

Current validation: 833 tests / 88 suites pass (latest `failure-termination-build.log`).
`frozen-control-1`, `serial-control-1`, `control-2`, `control-4` initially matched seven
ordered state cuts and 384 smelts. `frozen-full-trace` and `retained-claim-trace-4`
also match all 76,800 original furnace callbacks (HEAD/RETURN state, exact per-object
clock/order), with natural distinct-worker overlap and no conflicting claims.
These traces precede the final dedicated-stop repair and must be repeated on the
final artifact. Negative comparator controls reject state, clock, order, duplicate,
phase, ownership and lifetime defects; the Stage 4 comparator is unchanged.

`client-2-5-a` passed real TCP, original tracking/merge/split/unload, native MCA button
readback and restart: saved delay 186; no callback before deadline, exactly one at it,
none afterward. Furnace save readback correctly rejected a stale native clean-chunk
snapshot (5 ticks old). New identical `stage5 dirty` fixture input before frozen
save makes the next campaign require exact timers, without changing native save rules.
Client 8/8, frozen Stage 4 client pairs, candidate one-worker control and final repeat
remain pending. Stage 5 mode is explicit `-Pstage5=true -Pstage5Workers=2|4`; reference
mode then requires the exact Stage 4 path/hash, while old Stage 4 mode retains Stage 3.

`native-adverse-2-d` passes on current built JAR
`1AFE9225121A8B2CB760CFE8270535E4BA16E9D6EFF62A7CA80858A3352D5D62`:
original worker failure overlaps another original live claim and retained native
write; peer joined, all 41 native save records acknowledged, original failure retained,
15/15 native closes, no residue, natural process exit in 6.30 s, no watchdog termination.
Earlier `native-adverse-2-b` printed resource PASS but required watchdog termination
and is NOT an accepted shutdown result. The runner now rejects that condition.

Other preserved failed inputs: initial native demand used an eager synchronous native
chunk request (fixed to native tickets plus actual readiness); a ticker interface in
the Mixin-only package failed class loading (moved to service); callback observer and
dedicated-stop hooks initially used wrong exact native descriptors (corrected). The
all-or-nothing window eliminated overlap despite matching states, so was replaced by
bounded segments and retained final preflight. No unchanged failure was rerun as PASS.

Next: run the extended `runStage3FullAcceptance -Pstage5=true -Pstage5Workers=4` with
seed `-5846470989536090341`; finish strict client/reference/persistence campaigns;
then run isolated repeated distributed/concentrated benchmarks at 1/2/4 workers with
identical offered work. The gameplay runner records native CPU/heap/GC/allocation cuts,
100 ms process working-set samples, bounded original intervals, and actual enqueue
to worker-start delay. Benchmark callback/body tracing is disabled. Final audit pending.

Required remaining evidence: deterministic races and progress/capacity; original
gameplay body/claim intervals with natural overlap at 2 and another bounded worker
count; complete final suite; installed 5/5 and 8/8 TCP client and native FULL branches;
strict frozen Stage 4/candidate serial comparisons; merge/split/stale/replacement;
save/MCA/restart/native scheduled deadlines; healthy/adverse shutdown with legitimate
claims and writes retained to acknowledgement; server-only final packaging; identical
artifact identity throughout final acceptance; repeatable distributed/concentrated
benchmarks with completed work, latency, CPU/memory/GC and explicit limitations.

FULL `full-4-a` on 5de466b / 7CCBA6ED failed after successful native furnace warmup:
new FULL demand at 126,-70 was routed to a dynamically split region absent from the
startup lease-manager snapshot. Native admission, FULL context refinement and save
retention now resolve the authoritative registered owner after current route validation.
A focused regression creates admission before the split, rejects the old queued route,
then proves the original callback owns the new region's actual lease. Focused build
passes; next FULL run uses JAR 59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5.
The headless runner also strips unrelated prepared FULL probes from its disposable copy.
The bounded sequential benchmark driver is `scripts/benchmark_stage5.py`; its report
is evidence capture only and cannot mark Stage 5 complete.

FULL `full-4-b` reaches all four native FULL publications and original disk writes
with the repaired owner lookup, but the bed callbacks time out because the probe
left Minecraft frozen. Read-only `tick query` confirms frozen while all scheduler
health counters remain zero. Root cause: freezing at the measured tick's END leaves
Minecraft's original sprint finalizer pending; a later unfreeze is overwritten by
its saved frozen state. The probe now lets native finalization finish and only then
publishes STAGE5_MEASURED. This is a harness repair, not a production clock change.
The independent Sol High review of 5aeb2fe found no actionable issue; coordinator-only
registration-before-route publication remains an explicit requirement.

`churn-final-4-a` rejected one extra native tick when merely awaiting sprint completion:
console admission can run an initial game tick before the server loop charges a sprint
tick. The exact cut now calls original `stopSprinting` (or the original zero-remaining
finalizer) and requires the native frozen state, rather than changing time or ignoring
the extra callback. `churn-final-4-b` passes: 800 actual ticks, 128 furnaces, three
merge/replacement/split rounds, 12 actual replacement instances and 24 stale queued
native read deliveries; natural process exit. Strict reference comparison is next.
Full Java suite passes after the owner repair (834 tests / 88 suites); all 14 Python
comparator negative tests pass, including the unchanged Stage 4 cases.

`full-4-c` (`run/full-authoritative/live-1788902752609`) passes the original fresh and
reload FULL/client/native-MCA gates on production 59BB31DC: eight original readers,
four bed callbacks, eight collision/IMMEDIATE reads in each lifecycle, zero residue,
natural exits. Fresh has valid natural local overlap; reload's observed native ticker
ordering splits the two small cohorts into separate serial segments (no overlap), so
it is not the final Stage 5 FULL concurrency gate. The next fixture uses four larger
independent furnace cohorts, preserving every original FULL assertion.
`final-client-4-8-b` passes native 8/8 TCP, saved button delay 369 and original deadline
callbacks. Native furnace MCA/readback matches, but both furnace regions were merged:
center 20's required halo crosses the Stage 4 split boundary at 19. The auxiliary center
is now 24, inside B's actual closure. This corrects offered independent topology rather
than relaxing overlap. Prepared FULL staging also needed exclusion of its existing
Stage 4 probe before explicitly installing the selected copy; failed staging retained.
Actual client prelaunch/restart guard checks now exercise swapped real JARs and both
Stage 3/4 reference identities; Stage 5 candidate mode explicitly rejects either freeze.

`full-4-d` requested auxiliary center 60 outside the existing explicitly declared
fixture domain and was correctly rejected as unroutable. The fixture now uses the
already supported centers 0,10,20,32. `full-4-e` fresh has natural four-region overlap;
reload has natural two-region overlap but only three of the four required frog/villager
pairs visible. The original 8-reader/suffix witnesses exist. This remains FAIL/pending
diagnosis, not a reason to relax the native entity count. Sol High is doing one bounded
read-only investigation of that exact missing publication while the lead maintains
the sequential final-control driver `scripts/run_stage5_controls.py`.

Latest closure checkpoint (still IN PROGRESS): production remains 5aeb2fe / 59BB31DC.
`controls-native-a` passes frozen Stage 4 and candidate 1/2/4: 128 furnaces, 800 actual
native ticks, 102,400 original callbacks (204,800 HEAD/RETURN events), 512 smelts,
12 exact observation cuts, three churn rounds, 12 replacements and 24 stale reads.
All five ordered comparisons and native MCA checks pass, with natural 2/4-worker overlap.

The bounded Sol High investigation of `full-4-e` proved all four native FULL insertions
succeeded. One section then legitimately unloaded its frog/villager before visibility;
the probe incorrectly treated their later EntityStorage reread as a first FULL reader
and threw inside native loading. The probe now permits only a previously proven native
FULL reader and successful insertion with the same UUID, marker and exact class. It
keeps every original first-reader, rich-state, conversion, visibility and count gate.
No production behavior or FULL publication rule changed. Installed retest is next.

`controls-client5-a` frozen and candidate-one native lifecycle/deadline gates pass, but
the campaign is not accepted: the initial driver used the headless initial-unpack
requirement against a fresh client fixture, and its probe changed between builds.
The new client comparison keeps the Stage 4 ordered projection unchanged and requires
the original native MCA/unpack/deadline validator on the actual restart. It also
requires identical probe artifacts, exact saved delays/distances, PASS raw traces,
original tracking/body coverage for both owners, and prelaunch/restart identity.
The driver now checks artifacts after each run as well as before. Stable 5/5 and 8/8
campaigns will run without source edits or competing builds.

Latest full Java suite: 834 tests / 88 suites PASS (`reader-lifecycle-build.log`).
Python comparator negatives: 16 methods PASS (`comparator-negatives-final.log`).
Actual copied-JAR identity negatives: 18 checks PASS (`identity-final-b/report.json`).
The benchmark recorder now includes shared-service entries, actual topology latency,
native save/stop latency, JVM allocation/GC, process CPU/working set and host metadata.
No benchmark measurements have been accepted yet.

`full-4-f` / `run/full-authoritative/live-1788904763484` PASS: original fresh/reload
FULL assertions, both TCP clients, MCA payloads and native exits pass. Separate strict
original-body interval checks pass in both lifecycles: 512 furnaces, four regions,
4,096 retained intervals each, natural maximum overlap 4 fresh / 2 reload.

`controls-client5-b` FAIL was correctly rejected for saved delay 186 versus 369.
The prior claim that a fresh client fixture needs no native unpack was incorrect.
Native ChunkMap removes a holder from updating/visible maps BEFORE scheduleUnload
removes it from pendingUnloads. New demand can resurrect that exact pending holder,
keeping its absolute trigger; completed unload instead rereads delay=316 at +187.
The probe's old 'unloaded' receipt checked only the first two maps. It now additionally
requires the original pending-unload holder to be absent at the same frozen +6 cut.
This strengthens the native lifecycle gate and makes the offered reload input exact.
The Stage 4 comparator is restored byte-for-byte to its original unpack requirement;
the Stage 5 client wrapper now requires BOTH that original path and restart validation.
Missing original unpack remains a negative test. No production code changed.
