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
7% used / 93% remaining. Tick/event + save/stop integration: Codex 44% used / 56% remaining; reserve 7% used / 93% remaining. Native-area integration: Codex 51% used / 49% remaining; reserve 7% used / 93% remaining. These are account windows, not per-task consumption.

## Fixed implementation and acceptance map

| Outcome | Connected implementation | Remaining acceptance |
| --- | --- | --- |
| Scheduled ticks | Real per-owner persistent containers, due selection, typed collection/dispatch; native clear/copy share the original current-cycle fields and finite area cut | Repair exact eligibility call/order semantics; installed deterministic two-owner migration and restored effects |
| Block events | Per-owner ordered queues, original nested enqueue and dispatch, migration | Installed ordered comparator and pending-work merge/split |
| Entity tracking | Sol implementing actual tracker partitions, original pairing/removal/update and existing entity/session receipts | Complete immediate-add preflight, integrate, actual clients and lifecycle migration |
| Save cuts | Original selected-holder retention, batch/operation topology cuts, detached original snapshot, original I/O acknowledgement | Final-artifact disk records/restart, replacement/pressure and affected FULL lifecycle |
| Shutdown | Admission close, leased admitted-work drain, independent failure settlement, terminal metadata acknowledgement | Clean final installed stop under normal and adverse pending work |
| Global services | Established named services and explicit metadata/owner-free I/O protocols | Final integrated reached-path audit; see STAGE4_SERVICE_CONTRACT.md |

All six need final production Mixins/JAR, deterministic timing/order/effects, actual
clients where relevant, real saved records/reload, baseline 5/5 and larger 8/8 without
caps, affected FULL/packet/service/saved-vault evidence, and lightweight comparable
overhead. Two logical regions must execute services, not merely appear at startup.
No broad lock, unowned mutation, foreign reads, callback under metadata locks, replay,
fake readiness, failure fallback, or safe refusal counted as feature support.
Same-holder re-promotion remains deferred until reached; then authenticate fresh
original lineage without repeated conversion or resurrected old evidence.

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

Lead owns native tick clear/copy integration, exact eligibility repair and next
restart closure review, with save/stop and topology architecture. Sol owns production
tracking and immediate-add preflight, then connected repairs. Luna is the sole broad
build/live campaign owner: exact original oracles, controlled client comparator,
MCA pending tick deadline/readback/reached effects, real merge/split and lifecycle,
5/5 and 8/8, affected foundation regressions, and elapsed/RSS/GC-limit observations.
Next stable campaign must include all production repairs, not an earlier JAR.
Internal commits remain recovery points; continue until all required gates pass.

