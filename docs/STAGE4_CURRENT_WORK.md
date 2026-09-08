# Stage 4 current execution — 2026-09-08

**PARTIAL — implementation in progress.** Stage 3 remains
**STAGE3_SERIAL_CORRECTNESS_FROZEN** within closure sections H/I. Stage 5 has not
started. One gameplay worker, parallel gameplay OFF throughout.

## Source, ownership, and usage

Original clean main: `890d38614e001ae115cb419b653e6b566c8a4d53`.
Frozen executable reference: `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`.
Frozen production SHA-256:
`017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`.
Development branch: `stage4/regional-world-services` in `Phoenium`.
Historical `Phoenium-full-luna` and `Phoenium-full-sol` remain untouched.

Lead: user-selected Astra/Max; architecture, save/shutdown, integration, review.
Implementation worker: explicit `gpt-5.6-sol`, `high`, `fork_turns=none`;
isolated `Phoenium-stage4-sol`, regional state/execution and connected adapters.
Verification owner: explicit `gpt-5.6-luna`, `high`, `fork_turns=none`;
isolated `Phoenium-stage4-luna`, baseline, harness, broad/final installed campaign.
Tool spawn/configuration fields are evidence of selection; backend runtime model
identity and task/worker token accounting are unavailable. No recursive delegation,
additional Astra, API billing, resets, account changes, or remote publication.
Starting account-wide usage: Codex 35% used / 65% remaining; reserve 7% used / 93%
remaining. First dispatcher integration: Codex 38% used / 62% remaining, reserve
7% used / 93% remaining. Tick/event + save/stop integration: Codex 44% used / 56% remaining; reserve 7% used / 93% remaining. Native-area integration: Codex 51% used / 49% remaining; reserve 7% used / 93% remaining. Passenger/save-failure integration: Codex 58% used / 42% remaining; reserve 7% used / 93% remaining. These are account windows, not per-task consumption.

## Fixed implementation and acceptance map

| Outcome | Connected implementation | Remaining acceptance |
| --- | --- | --- |
| Scheduled ticks | Real per-owner persistent containers, due selection, typed collection/dispatch; native clear/copy share the original current-cycle fields and finite area cut | Installed deterministic two-owner migration, exact original register-then-unpack oracle and restored effects |
| Block events | Per-owner ordered queues, original nested enqueue and dispatch, migration | Installed ordered comparator and pending-work merge/split |
| Entity tracking | Actual tracker partitions, original pairing/removal/update, entity/session and exact passenger-edge receipts, source-map order | Owner-free outer chunk/entity producer cuts; actual clients, ordered packets and lifecycle migration |
| Save cuts | Original selected-holder retention, batch/operation topology cuts, detached original snapshot, original I/O acknowledgement | Final-artifact disk records/restart, replacement/pressure and affected FULL lifecycle |
| Shutdown | Admission close, leased admitted-work drain, independent failure settlement, terminal metadata acknowledgement | Clean final installed stop under normal and adverse pending work |
| Global services | Established named services and explicit metadata/owner-free I/O protocols | Final integrated reached-path audit; see STAGE4_SERVICE_CONTRACT.md |

All six need final production Mixins/JAR, deterministic timing/order/effects, actual
clients where relevant, real saved records/reload, baseline 5/5 and larger 8/8 without
caps, affected FULL/packet/service/saved-vault evidence, and lightweight comparable
overhead. Two logical regions must execute services, not merely appear at startup.
No broad lock, unowned mutation, foreign reads, callback under metadata locks, replay,
fake readiness, failure fallback, or safe refusal counted as feature support.
Same-holder re-promotion is now reproduced and repaired at the original FULL
completion boundary; installed lifecycle acceptance remains required. The withdrawn
subscriber stays invalid, and a new slot needs a distinct original-producer receipt.

## Verification and next unresolved case

Baseline: 690 tests / 59 suites PASS and ten historical hashes verified;
`Phoenium-stage4-luna/run/stage4/baseline-20260908-012726`. Production rebuilt to
exact frozen SHA above. Historical Stage 3 evidence/worlds remain immutable.

Integrated source recovery points: `a1da48a` tick/event/save + installed client harness;
`847602c` shared-barrier/topology notification and terminal ACK repair; `0d9623a`
counts publication inside enclosure as progress (real enclosure regression).
Focused evidence under main `run/stage4`: save/stop 19 tests; live retention/core
34 tests; topology terminal ACK 52 tests; closure progress 15 tests. Original
failures are retained in their named failed-* directories/logs. Current native
clear/copy regression compares unmodified pinned LevelTicks using its actual cohort
fields, including cancellation, copied already-run/pending entries and query behavior.

Luna's installed `integrated-installed-20260908-025903` failed on closure progress
and narrow fixture setup; missing pending tick records/reload effect correctly FAIL.
Paired explicitTopology + chunkFixtureSupport flags repair the missing-route fixture.
Subsequent `integrated-installed-repair-031256` has positive first-run block/fluid,
events, save and clean stop, but restart reports a foreign closure owner. Preserve
both bundles. Root repaired the nested producer overclaim: enqueue requires only the destination owner; the future callback halo is admitted when due. A regression crosses that same boundary and later executes after genuine convergence. Do not widen a valid fixture boundary simply to hide that production
failure. No integrated Stage 4 installed PASS exists.

Integrated production recovery: `b2ab914` (test-oracle recovery `bf250f1`), including native tick eligibility/index
`952ce13`/`34b2d0f`, nested block-event boundary `b4d23e9`, exact tracker accessors
`1db10ad`, named tracking startup `3a36d24`, passenger relations `4cd836f`/`f36fcb1`,
and original final phase RETURN repair `b7d0051`. `545b793` preserves a source/save
exception caught by vanilla after its dirty claim, restores dirty state through the
current owner ACK, and retains the exact failure at flush. The new negative first
failure and repaired focused evidence are under `run/stage4/caught-save-*`.
Save/facts/accessor integration: 21 tests PASS; final phase/stop/facts/accessor: 20 PASS.
`b2ab914` keeps original nearby block-event broadcast conditions/order/sends while
reading only precise owner-published recipient coordinates and current live-session
receipts. Routing now publishes after position fields commit and before original
`onMove`; a throwing native callback test proves the moved receipt survives and is
withdrawn at actual unindex. Facts/event/phase focused evidence: 20 tests PASS.
`bf250f1` integrates Luna's stronger native register-then-unpack/index oracle and
retains direct original LevelTicks ordering/eligibility comparisons.

Latest installed checkpoint `integrated-installed-stable-034415` starts and executes
scheduled services/save/stop but has no final PASS: tracking and block.enqueue proof
are missing; reload command fails before its begin marker. Its pre-restart decoder
was faulty. Re-decoding the world after a restart past the deadline does not prove
pre-restart tick loss. Harness fixes `31a26b5`/`e59d239` now decode and preserve an
immutable MCA copy before restart; `ca7d11f` exposes the original reload exception.
Only detached original packed tick results are observed at serialization; proposed
owner-free raw container diagnostics were rejected and were not integrated.
Genuine pending fixture `5da8152` replaced an invalid duplicate B/type schedule;
comparison must use identical corrected reference inputs.

Sol owns the reached outer cache/entity producer gap: the old CACHE_OWNER phase
ran all selected chunks under the world anchor owner. Actual callbacks need finite
owner-free admission and current source/recipient closure, preserving original RNG,
order and synchronous effects. Worker recovery `07bdc81` has the first cache split;
it is not yet integrated or ready for a campaign. Recovery `c14eac5` adds final entity
dispatch, published nearest-player observations, per-spawner conditional footprints,
and removes the per-tick entity-binding scan. Original block-entity suffix and entity
terrain closure are still under review. Lead owns save/stop, provenance and integration.
Luna alone owns broad builds/live campaigns, exact native oracles, controlled real
client comparison, immutable MCA/reload effects, real merge/split and lifecycle, 5/5
and 8/8, affected foundation regressions, and comparable elapsed/RSS observations.
Her installed topology fixture now requests actual coordinator merge/split (no direct
directory publication); pending review corrections include cancellation-resistant
trace hooks, separated behavioral/authority comparisons, transferred deferred-event
and fluid effects, and genuinely distinct region geometry for tracking callbacks.
The next stable campaign must include all production repairs.
Lead `c3cf005` rejects late producers after service termination before native mutation;
its new focused lifetime regression passes. The genuine same-holder withdrawal /
re-promotion repair preserves the original producer, revoked old subscriber, current
lower-input identity and new actual FULL slot/result. Four native negative reproductions
are retained in `run/stage4/same-holder-repromotion-negative-20260908.*`. Fresh/reload
before/after-completion cases and admitted FULL re-promotion plus subsequent input
replacement pass in the 66-test published/initialized/authoritative focused gate.
At this integration boundary account-wide Codex usage is 66% used / 34% remaining;
reserve remains 7% used / 93% remaining. No per-task token total is exposed.
Internal commits remain recovery points; continue until all required gates pass.
