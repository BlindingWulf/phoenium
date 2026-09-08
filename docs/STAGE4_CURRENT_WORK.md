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
| Entity tracking | Actual tracker partitions, original pairing/removal/update, entity/session and exact passenger/vehicle-edge receipts, source-map order; native chunk/entity/block-entity callbacks dispatch by current owner | Actual clients, ordered packets and lifecycle migration |
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

Production recovery `0a14b8a` integrates Sol's complete native chunk/entity callback
boundary. Selected chunks, entity/passenger/vehicle graphs, and block-entity tickers
enter their current regional owner after finite admission. The native chunk shuffle,
entity iteration, spawner order/counters, conditional final phase and ticker removal
protocol are preserved. Original spawn-state entity iteration remains once; the extra
per-tick entity-binding scan is removed. Native block-ticker unload removes before
reading its now-cleared position. Lead's integrated phase/block-ticker/lifetime gate
passes 10 tests (`run/stage4/final-callback-integration-20260908.log`).
`ccc2f3b` moves native service bootstrap outside the registry monitor and refreshes
natural-spawn player receipts after admission and before use; Sol's focused gate
passes 23 tests. `2d37d3a` integrates the final container-monitor repair: bound native
index hooks add no extra peek; native collection/drain calls run outside both metadata
monitors. The merged scheduled/lifetime gate passes 22 tests, retaining all original
register/unpack and area oracles (`run/stage4/native-tick-lock-integration-20260908.log`).
Sol is idle on clean `eae63b1`, available for concrete installed failures only.
Lead owns save/stop, provenance and integration.
Luna alone owns broad builds/live campaigns, exact native oracles, controlled real
client comparison, immutable MCA/reload effects, real merge/split and lifecycle, 5/5
and 8/8, affected foundation regressions, and comparable elapsed/RSS observations.
Her installed topology fixture requests actual coordinator merge/split (no direct
directory publication). Integrated harness `784c710` (worker through `9f30a1c`)
uses the same native-clock workload on both JARs, genuine FULL receipts and native
fluid-queue inputs. It keeps far ticks in the simulated viewport through their
deadline, observes entity removal while visible and checks exact client event packets.
`b5cbea7` adds real A/B serializer observations, successful-return shutdown evidence,
explicit reference selection and separate run paths. Luna owns the stable production
campaign at `2d37d3a` and is repairing a reached test-only PlayerList Mixin local-capture
failure in the initial frozen-reference 5/5 run. The failed bundle is preserved at
`Phoenium-stage4-luna/run/stage4/reference-client-5-playerlist-failure-20260908-052925`.
Final candidate, reference 5/5 and 8/8, comparison, persisted MCA/restart
and foundation gates remain open. Preserve each prepared server/world bundle before
another Sync preparation; verify the actual installed JAR hash on every launch.
The stable-source full gate passes 769 tests / 72 suites, zero failures/errors/skips,
after the reviewed metadata-only tracker exemption in `1d18b5c` (worker `3706f34`).
Gradle build passes. Production JAR SHA-256 is
`1EBB8A438BD6DB8F98E15481F26DF63136D8A9E24E26A3758F071D7B64589900`;
shared probe at worker `44e7249` is
`F4137F8084E9A362EE519690209FF7164D1765B7355ED1A06293861D2DC03C05`.
`73095e7` integrates isolated launch paths and the repaired recipient Mixin/client
observation holder. The current frozen-reference run
`run/stage4/reference-client-5-20260908-054512` starts/listens but client login times
out after Hello. Luna is capturing both process stacks and running the independent
installed transformed-runtime candidate probe. The latter now has a preserved
failure in `run/full-production-integration/transform-20260908-055335`: original
NaturalSpawner class transformation rejects the regional nearest-player invocation
target (zero matching calls). Sol owns that exact native descriptor repair and its
companion distance target; Luna owns the installed rerun and separate login analysis.
Both client/server login-stall stacks are preserved under
`run/stage4/reference-client-5-dump-20260908-055040/thread-dumps`.
No client/runtime PASS is inferred from the successful build or listening endpoint.
Lead `c3cf005` rejects late producers after service termination before native mutation;
its new focused lifetime regression passes. The genuine same-holder withdrawal /
re-promotion repair preserves the original producer, revoked old subscriber, current
lower-input identity and new actual FULL slot/result. Four native negative reproductions
are retained in `run/stage4/same-holder-repromotion-negative-20260908.*`. Fresh/reload
before/after-completion cases and admitted FULL re-promotion plus subsequent input
replacement pass in the 66-test published/initialized/authoritative focused gate.
At the re-promotion boundary account-wide Codex usage was 66% used / 34% remaining.
At the final callback integration boundary it was 71% used / 29% remaining;
at stable production `2d37d3a` it is 74% used / 26% remaining;
reserve remains 7% used / 93% remaining. No per-task token total is exposed.
Internal commits remain recovery points; continue until all required gates pass.
