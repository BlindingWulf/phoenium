# Stage 4 current execution — 2026-09-08

**PARTIAL.** Stage 3 remains **STAGE3_SERIAL_CORRECTNESS_FROZEN** within closure
sections H/I. Stage 5 is OFF. One gameplay worker; parallel gameplay OFF.

## Source, owners, and authority

- Original main: `890d38614e001ae115cb419b653e6b566c8a4d53`.
- Frozen executable: `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`.
- Frozen production SHA-256: `017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`.
- Main branch: `stage4/regional-world-services`, checkout `Phoenium`.
- Current production source: `42bb3445e9e02741419b0890ddf08efa32e356d7`.
- Candidate production SHA-256: `6016F943D9DCB59817F90EC6CBB9BC41B2EF73DAB8EDE7D4AF3556F8A7E6D1A8`.
- Historical `Phoenium-full-luna` / `Phoenium-full-sol` and failed evidence remain untouched.

Lead is user-selected Astra/Max: architecture, integration and review. The two
explicitly configured, nonrecursive workers are `gpt-5.6-sol` / `high` / fork none
in `Phoenium-stage4-sol` (implementation), and `gpt-5.6-luna` / `high` / fork none
in `Phoenium-stage4-luna` (harness and sole broad/live verification owner). Backend
runtime identity and task/worker token accounting are unavailable. No additional
Astra, paid API, reset, account change, remote publication or personal client account.

Account-wide Codex usage began at 35% used / 65% remaining; latest installed adverse
review reading is 93% used / 7% remaining. Reserve remained 7% used / 93%
remaining. Intermediate readings are preserved in this file's Git history; these
are shared account windows, not task consumption.

## Connected implementation and acceptance

| Outcome | Connected implementation | Remaining installed acceptance |
| --- | --- | --- |
| Scheduled ticks | Actual per-owner persistent containers; original eligibility, priority, deduplication and current-cycle clear/copy; native callbacks and topology transfer | Deterministic two-owner order/effects; exact persisted records and original reload effect |
| Block events | Per-owner ordered/deferred queues, original dispatch and nested producers, complete migration | Positive ordered event/recipient results and pending-work merge/split |
| Tracking | Actual tracker partitions and original pairing/removal/sends; published position, session and passenger/vehicle receipts | Current real-client FULL, packets, withdrawal and migration |
| Save cuts | Selected-holder retention, batch/operation cuts, original snapshot detached for I/O, current-owner acknowledgement/dirty restore | Final-artifact MCA/restart and affected lifecycle under pressure |
| Shutdown | Admission close, admitted-work drain, retained-write settlement, independent native resource cleanup with failures preserved | Installed native pending-write failure PASS; final real-client normal/reload stop open |
| Global services | Existing named services, explicit metadata and owner-free I/O protocols; finite native callback enclosures | Final reached-path audit and all dependent installed gates |

Contract and pinned-source provenance: `docs/STAGE4_SERVICE_CONTRACT.md`. No broad
lock, unowned mutation, callback under metadata locks, fabricated readiness, replay,
hidden fallback, or safe refusal counted as support. Genuine same-holder FULL
re-promotion (`0d82ef3`) requires a new original-producer completion receipt; the
withdrawn subscriber remains revoked. The 66-test lifecycle gate and original
negative cases are preserved. Final installed lifecycle coverage is still required.

## Verified evidence and current failures

Fresh baseline: 690 tests / 59 suites PASS, ten historical hashes verified, frozen
production rebuilt to its exact hash; `run/stage4/baseline-20260908-012726` in Luna.
Latest consolidated candidate build and acceptance-probe build pass at Luna
`6f91e11` (main parent `2fde9ec`). Lead recounted 790 tests / 79 suites, zero
failures/errors/skips, from the actual XML reports; adverse tests are additional.

Candidate installed `run/full-production-integration/transform-20260908-073804`
passes the corrected baseline marker-applicability gate (Luna `9d13b49`). Nine live
intercepts/dispatches/executions, zero barriers/violations/fallback, one worker,
parallel OFF. Normal stop records admissionClosed=true, queuedServices=0,
pendingSaves=0, 528 captures and 529 protocol acknowledgements (not 529 durable writes).
All save failure/residual counters are zero. The earlier `transform-20260908-065406`
false-negative marker bundle remains immutable. This is baseline installed health,
not complete Stage 4 acceptance. The candidate Stage4 shutdown marker, zero residual
fields, positive execution and exact workers=1 are now mandatory in the runner
(`177a3dc`, `8934b05`); Luna revalidates the preserved log without another launch.

Controlled installed FULL `transform-20260908-080511` now PASS. Exact isolated
fixture authority (`7b28af5`) and a separate post-callback acknowledgement phase
(`bd505ff`, required positive marker `3f3ed40`) preserve the actual production unload
and strict world identity. The probe observes pendingBefore=1/custodyBefore=3, then
real ACK and zero terminal custody. Original negative cases remain. Three actual
world ticks execute, workers=1, parallel OFF; Stage4 normal-stop residual/failure
counters and named-service custody are zero. The probe's controlled endpoints are
explicit; this does not establish real-client disk reload. Probe SHA-256:
`AD4D14C5508242A924BAFDCC4305F34096EAB84A0C6290E61332B69335F70CD2`;
log SHA-256 `DAC6B6CD7D85959AD9E18242FFC9E097BD1462290862A3D7E0F56A4D1ED90791`.
The 46-test focused gate also passes. Preserved failures are `065445` (foreign
synthetic world), `073703` (three retained entries awaiting ACK), and `080349`
(stale disposable probe JAR). Forced harness rebuild precedes the valid `080511`.

Separate adverse native-write / failed-stop probe is now
integrated (`af02a04`, Sol `c1c7a24`): exact native IOWorker identity, position and
first original write; one retained save during the actual regional callback failure;
original outcome, exception identity, resource completion and zero terminal custody.
Follow-up `6aa10d7` (Sol `5ccfdb5`) requires each exact resource-close HEAD to observe
the original write completion, pending=0, failedSettlements=1, stopped runtime and
zero leases; RETURN still proves closure. Strict wait/write/cleanup order and
cycle-safe exception traversal are covered by 29 focused tests, all PASS. Reviewed
probe SHA-256 is `01F5B32A770196482058AC5977CFB9A2E89EFA19BEB46D3F954753783BE21B17`.
Installed `run/stage4/adverse-native-20260908-0810` PASS on production `6016F943...`:
one original selected write remains pending at the exact callback failure; original
write success precedes terminal ACK and all 15/15 exact cleanup entries. The exception
identity survives; native resource sets close 3/3/3/4/1/1, and save, dispatcher,
lease, entity, scoreboard and named-service custody reach zero. FailedSettlements=1
is expected and no successful/durable-save claim is made. The probe starts before
ordinary world ticks; its two genuine owner operations and native write are the
positive execution evidence, not a normal-run health claim. Exactly one PASS marker;
log SHA-256 `AF8C991976CDBAE9B684048E411B6E3E30795F094F8AA62CFED7EA873B6BF526`.
Sol now owns the bounded frozen/native movement source diagnosis below; Luna alone
coordinates live runs and the remaining MCA/restart harness work.

Earlier preserved native failures: `transform-20260908-060338` first-tick consecutive
coordinator phases, repaired `00ce944`; `transform-20260908-064318` superclass-close
receiver signature, repaired `42bb344`. Failed-stop settlement `2ba0e8a`, native
cleanup `919e2ce` and public transformed-handler API `5d2efd5` pass the merged
32-test focused gate. Normal stop keeps its original native path. Source/container
monitor repairs, exact NaturalSpawner overloads and retained VillageSiege origin
are integrated and covered by focused native-source oracles.

## Next unresolved campaign case

Luna's frozen real-TCP reference logs in and receives A=(0,0) and B=(7,0).
`run/stage4/reference-far-native-error-20260908-084500` preserves the exact frozen
JAR and the first native far prerequisite: after teleport to (520.5,65,8.5) and 120
genuine ticks, center (32,0) has ticket 33, FULL/latest full and successful fullFuture,
but tickingFuture=Unloaded. All eight halo holders have ticket 34, INACCESSIBLE,
unloaded FULL/ticking futures; native block/entity-range and nearby checks are false.
No current client chunk payload arrives. Both process dumps and packet/server logs
are preserved. The movement-input hypothesis (`e2b88a4`, integrated `b6db927`) did
not resolve readiness: `reference-nudge-5-20260908-074500` stalls at B=(7,0), despite
actual movement/input packets. Luna now captures generic first-failure server/client
position, lastSection, ticket types, teleport/ack state and exact native cache state.
Lead review also found the recorded accepted FULL reference used authoritative=true
and explicitTopology=true, whereas the earlier shared launcher omitted them. The
source audit confirms explicitTopology is the established one-worker fixture;
`stage4.candidate` alone gates new coordinator calls. Shared correction `8a2847a`
restores both recorded FULL flags on reference/candidate and adds generic native
ticket/teleport/client-state diagnostics. Flag-corrected frozen run
`reference-diagnostic-5b-20260908-081000` still stalls at B: player=(120.5,65,8.5),
lastSection=(0,4,0), tracking center=(7,0), FULL/ticket 33 but absent simulation
demand. `awaitingTeleport=3` is a native ID counter; awaitingPositionFromClient=null
means it is not currently waiting on that position. Pinned listener lines 527–544
and 1236–1244 disprove the earlier unacknowledged-teleport inference. Frozen run
`reference-telemetry-5-20260908-0806` preserves matching accepted IDs 1/2/3, the same
B failure, dumps and a 134 KB real-client trace. Sol now traces frozen/pinned movement
admission and section update; Luna holds further movement retries pending that
specific boundary and runs the independent adverse/MCA harness work. Positive live
readiness is still required. Closure H/I's distant-demand and old same-holder
re-promotion boundaries remain explicit; no new reference scope is silently claimed.
Do not increase timeouts, cap distances, substitute server-only receipts or weaken
the actual current client FULL gate.

Main harness now integrates Luna's coherent test-only work through `e2b88a4`,
including `dec8c48`, diagnostic `2a6c0f0` / `0f57111` and the first observation
contract corrections. These fix protected Mixin-package helper loading, sequence actual
preparation receipts, add landing pads, release temporary force tickets, and replace
a fabricated NOTE_BLOCK-to-REDSTONE_BLOCK callback with original STONE_BUTTON.tick.
Lead review requires the following remaining corrections:

- Keep exact typed, current connection/world/resident-chunk receipts and actual client
  FULL application. Do not require a fresh packet when an overlapping viewport still
  contains the original payload. Separate event-observation clearing from chunk lifetime.
- A/B event parameters, result fields and the shared far-block coordinate are now
  coherent; their installed positive event/recipient acceptance is still open.
- Positively observe deferred event eligibility false before migration and true later.
- Inspect one exact native pending minecraft:stone_button record in its actual MCA
  chunk, saved powered=true state, priority and native relative delay at the snapshot.
  Current parser's local/world coordinate alternatives and deadline guesses are invalid.
  Luna `573ba9d` is committed in its worktree but not accepted/integrated: it invents
  saved `o` order, expects the wrong ChunkPos string, and uses continuous rather than
  native padded-per-long palette packing. Pinned SavedTick saves only i/x/y/z/t/p;
  ChunkPos prints `[x, z]`. Correct to exactly one obligation with coherent dimension,
  region/chunk identity and named powered state. Luna owns the source-based repair.
- Freeze restart before a saved deadline can pass, activate observation, then step
  through actual restored execution exactly once; do not reschedule or extend it.
- Reuse the completed real-client world and immutable pre-restart copy. The generic
  server-only driver does not satisfy the client or final restart gates.

Final reference/candidate 5/5 and 8/8, strict timing/order/effects comparator, actual
MCA/restart, two-owner migration, affected FULL fresh/reload/packet/service/saved-vault,
pressure coverage beyond the passing installed failed-stop case, and comparable
light elapsed/RSS measurements remain
open. The old 14.718 s / 672 MB checkpoint is not a final paired overhead result.

Internal commits are recovery points, not stopping instructions. Continue connected
repairs and the sole-owner campaign. Stage 4 stays PARTIAL; Stage 5 is blocked by
these acceptance gates and remains disabled.
