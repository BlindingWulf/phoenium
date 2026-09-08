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
`Phoenium-stage4-luna/run/stage4/baseline-20260908-012726`. Frozen production
rebuilt to the exact SHA above. Historical Stage 3 evidence/worlds remain immutable.
The latest consolidated gate, worker `32263af` integrating production `5d2efd5`,
passes 784 tests / 77 suites with zero failures/errors/skips and a successful build.
The subsequent installed transform gate fails before startup on a superclass-close
receiver signature. Final installed verification is open.

Integrated production is now `42bb344` (after `5d2efd5`, `919e2ce`, `b5d75a8`, `2ba0e8a`,
`00ce944`, `f793e57`). Native scheduled
register/unpack and current-cycle clear/copy oracles remain intact; the container
monitor repair `2d37d3a` passed the merged 22-test scheduled/lifetime gate. Selected
chunk/entity/block-entity callbacks preserve native order and use finite current
owner admission (`0a14b8a`, `ccc2f3b`). Source reads at world-service bootstrap and
container peek/poll are outside both registry/service metadata monitors. The
10-test merged phase/ticker/lifetime gate passed before the latest repair.

Genuine same-holder FULL re-promotion `0d82ef3` retains the original producer,
revokes the withdrawn subscriber, and requires a new actual completion receipt for
the distinct slot. Fresh/reload before/after completion and later input replacement
pass in the 66-test published/initialized/authoritative gate. Four native negative
reproductions remain under `run/stage4/same-holder-repromotion-negative-20260908.*`.
Source lifecycle late-producer refusals (`c3cf005`) cannot reopen bootstrap.

The prior installed candidate is worker `9370007`, production SHA-256
`B582CCFC642FC0394027CDAAD404CDDDDD80EB9EDC92DED13E7A6CD82D8D811`.
It passes the corrected exact NaturalSpawner overload transformation (`f793e57`),
but `Phoenium-stage4-luna/run/full-production-integration/transform-20260908-060338`
fails on the first world tick, gameTime 1: `dispatchLevelTick` expects queued owner
work between consecutive READINESS and BLOCK_EVENTS coordinator phases. That trips
the barrier; subsequent unload-save refusals and shutdown waiting are secondary.
This is not normal-stop evidence. Sol's phase repair `00ce944` advances the finite
coordinator chain before requesting a real owner step, retains missing-work failure,
and passes its phase/readiness focused gate plus build excluding broad tests.

Lead `2ba0e8a` settles actual retained writes and protocol custody before native
resource closure after failed admission/drain. The exception remains primary; the
failed-stop scope is confined to that invocation. Reentrant requests cannot replay
work; independent close failures are retained. The 19-test stop/save/generation
focused gate passes (`run/stage4/failed-stop-order-integration-20260908.log`).
Sol's connected exact-native failure-stop hooks are integrated in `919e2ce`; lead
`5d2efd5` removes an inaccessible helper from the protected Mixin package. They omit
forbidden new snapshots and unloads, send terminal transport notifications through
existing sessions, join submitted storage work and close independent resources.
The merged 32-test / 8-suite focused gate passes with zero failures/errors/skips
(`run/stage4/failure-stop-native-integration-20260908.log`). This includes the native
siege-origin repair `b5d75a8`: original pending spawn attempts remain enclosed after
players move or leave. Healthy native stop retains its original path. Sol is idle
on clean `16873c5`, available for concrete production failures; lead owns review and
integration. Luna owns the stable `5d2efd5` full/build and installed campaign.

The newer installed JAR SHA-256 is
`2563B8845A510E99ACE4E32F9CBE3748F2442105EA862B8FB6882757A8A9ECE7`.
Its failed bundle `run/full-production-integration/transform-20260908-064318`
retains the exact `FailedStopServerLevelMixin` receiver mismatch. `42bb344` repairs
that and the matching ChunkMap superclass receiver, retaining both native finally
sites; the 14-test target/cleanup/shutdown gate passes. Luna owns its installed rerun.

Luna alone owns broad builds and the final live campaign. Current frozen-reference
login evidence in `run/stage4/reference-network-exception-20260908-062250` verifies
the exact frozen JAR and an accepted TCP connection that closes before any server
packet-decoder receive. Client Hello then times out; both process stacks and actual
TCP tuples/PIDs are preserved. Diagnostic test-only commit `5094ad4` records native
listener/phase/counters, exceptions and TCP state; it is not yet integrated on main.
The earlier preflight-removal/property-parity experiment did not resolve login.
The test observation holder needed to move out of the entire protected Mixin package,
not only its Mixin class. Worker `e2e6317` moves it to `clientobs`; actual login/game
packets are present in `reference-client-classload-fixed-20260908-063750` and
`reference-primed-playerfix-20260908-073000`. Their later fixture waits still fail.
Worker `d67e477`/`e3a59ed` sequence real FULL client receipts at all four points,
add identical landing pads, require real successful A/B event bodies, and disable
wire dumps by default. These coherent harness commits await final integration.
No client/runtime PASS is inferred from startup, login or a passing build.

The shared acceptance harness is integrated through `08c3f0c` (failure thread dumps),
`73095e7` (isolated launch/recipient/client observation repair), `b5cbea7` and `784c710`.
It uses identical native-clock inputs on reference/candidate, real A/B regions,
actual coordinator merge/split, authentic FULL/readiness receipts, exact event
packets, original serializer observations, immutable pre-restart MCA copies and a
strict behavioral comparator. Candidate-only routing metadata is separate. Remaining
harness repairs require removing temporary preparation tickets if they make the
deferred event eligible early, plus a startup freeze before a persisted deadline
can pass. The old NOTE_BLOCK-to-REDSTONE_BLOCK test injection bypassed the original
scheduled callback; replace it with a real native block transition observed after
the original callback, including actual pending MCA record and state on restart.
Normal restart must reuse a completed shared-client world. Server-only smoke does
not complete the client gates.

Final candidate and reference 5/5 plus 8/8, ordered behavioral comparison, persisted
MCA/restart effects, migration/lifecycle and affected FULL/packet/service/saved-vault
checks remain open. Preserve prepared server/world bundles before Sync and verify
the actual installed JAR hash on each launch. Earlier installed evidence under
`integrated-installed-stable-034415` is partial: its old MCA decoder was faulty and
re-decoding after restart passed the deadline cannot establish pre-restart tick loss.
It recorded 14.718 s and 672 MB RSS in a different checkpoint; this is not the required
paired final overhead result. All failed bundles and the old worlds are preserved.

At the native shutdown integration boundary account-wide Codex usage is 80% used /
20% remaining; reserve is 7% used / 93% remaining. No per-task total is exposed.
Internal commits are recovery points. Continue repairs and the sole-owner final
campaign; Stage 4 stays PARTIAL and Stage 5 remains blocked on these acceptance gates.
