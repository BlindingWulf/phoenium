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

Account-wide Codex usage began at 35% used / 65% remaining; latest installed-evidence
review reading is 87% used / 13% remaining. Reserve remained 7% used / 93%
remaining. Intermediate readings are preserved in this file's Git history; these
are shared account windows, not task consumption.

## Connected implementation and acceptance

| Outcome | Connected implementation | Remaining installed acceptance |
| --- | --- | --- |
| Scheduled ticks | Actual per-owner persistent containers; original eligibility, priority, deduplication and current-cycle clear/copy; native callbacks and topology transfer | Deterministic two-owner order/effects; exact persisted records and original reload effect |
| Block events | Per-owner ordered/deferred queues, original dispatch and nested producers, complete migration | Positive ordered event/recipient results and pending-work merge/split |
| Tracking | Actual tracker partitions and original pairing/removal/sends; published position, session and passenger/vehicle receipts | Current real-client FULL, packets, withdrawal and migration |
| Save cuts | Selected-holder retention, batch/operation cuts, original snapshot detached for I/O, current-owner acknowledgement/dirty restore | Final-artifact MCA/restart and affected lifecycle under pressure |
| Shutdown | Admission close, admitted-work drain, retained-write settlement, independent native resource cleanup with failures preserved | Final normal/adverse installed pending-work stop |
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
Latest consolidated candidate build and acceptance-probe build pass. Lead recounted
789 tests / 79 suites, zero failures/errors/skips, from Luna's actual XML reports.

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

Controlled FULL `transform-20260908-065445` reaches the native hooks, then fails
`FullPublishedInputBindingTest.originalUnloadWaitsForRetainedUseThenReclaimsTheActualHolder`:
the synthetic fixture world calls the actual regional unload adapter, which correctly
rejects a different live world instance. The original unload callback therefore does
not reclaim pending state. Native stop and FULL_SERVICES STOPPED PASS still occur;
they do not erase the initiating test failure.

Controlled fixture repair is integrated as `7b28af5` (Sol `92e3545`). Exact synthetic
ServerLevel/ChunkMap identity selects genuine isolated regional save/lifecycle
authority at the existing seams. Original production unload body and all existing
retention/reclamation assertions remain active; production world identity stays
strict. The 45-test focused gate passes, including foreign/post-stop rejection,
throwing-scope cleanup, real save acknowledgement and named-service teardown. The
probe JAR builds and production packaging excludes this adapter. Installed rerun
`transform-20260908-073703` reaches the original unload and reclamation but fails
the unchanged final zero-custody assertion at FullPublishedInputBindingTest:393:
three entries remain. Probe SHA-256 is
`A7D06C6F853840E32F8C811F539B5B65EC579A76350F905B20B50917BF6886F8`.
The three entries are the original clean-save source fork's lifecycle/work/custody,
retained after completion until its real ACK. Repair `c441e0b` (Sol `264d96a`) pumps
only already-published ACKs after the original callback at the owner-free fixture
boundary; unfinished native writes remain retained and original exceptions remain
primary. The 46-test focused gate passes; the unchanged zero-custody assertion now
awaits installed rerun. Sol resumes its separately packaged adverse native-write /
failed-stop probe; Luna alone launches installed evidence.

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
are preserved. Luna traced the missing native movement input after /tp: ChunkMap.move
does the remove/add player transition that supplies PLAYER_SIMULATION; a self-camera
player tick does not provide it. Shared input correction `e2b88a4` is integrated as
`b6db927`, using a brief actual client nudge after each teleport. The next run must
prove lastSection/player simulation/ticking changed and the actual client received
and applied its chunk; the nudge alone is not readiness evidence. Do not increase
timeouts, cap 5/5 or 8/8, substitute server-only receipts or silently widen scope.

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
- Freeze restart before a saved deadline can pass, activate observation, then step
  through actual restored execution exactly once; do not reschedule or extend it.
- Reuse the completed real-client world and immutable pre-restart copy. The generic
  server-only driver does not satisfy the client or final restart gates.

Final reference/candidate 5/5 and 8/8, strict timing/order/effects comparator, actual
MCA/restart, two-owner migration, affected FULL fresh/reload/packet/service/saved-vault,
pressure/failure settlement and comparable light elapsed/RSS measurements remain
open. The old 14.718 s / 672 MB checkpoint is not a final paired overhead result.

Internal commits are recovery points, not stopping instructions. Continue connected
repairs and the sole-owner campaign. Stage 4 stays PARTIAL; Stage 5 is blocked by
these acceptance gates and remains disabled.
