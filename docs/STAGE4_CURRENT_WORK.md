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
7% used / 93% remaining. These are account windows, not per-task consumption.

## Fixed implementation and acceptance map

| Outcome | Connected implementation boundary | Required acceptance (all pending) |
| --- | --- | --- |
| Regional scheduled ticks | Original block/fluid scheduling, chunk tick containers, eligibility, ordered dispatch, serializer/load | Two regions; original timing/priority/subtick order, deduplication, reached effects; migration with pending work; persisted reload |
| Regional block events | Original ServerLevel enqueue and dispatch, nested enqueue, recipient service | Two regions; original insertion/dispatch and nested order; no event loss or duplicate through merge/split |
| Regional entity tracking | Original ChunkMap tracking entry/membership/update/pair/remove and existing object/connection services | Actual client recipients and ordered packets; movement, removal/re-pair and topology transition |
| Save cuts | Original save-all/autosave/unload serializer and storage acknowledgements | Coherent retained owner/topology/holder cut; dirty/replacement/failure cases; real saved records and restart; no claim beyond original flush semantics |
| Shutdown | Original stop admission, legitimate generation/owner/service work, saves and cleanup | Pending work drains or settles under proper authority; callback/reentrant/failure ordering; terminal acknowledgement and zero true residue |
| Global services | Existing identity, connections, clock, scoreboard, profile/cache, persistence, chunk/light/POI, topology | Explicit responsibilities/access protocols at actual producers/consumers; no duplicated authority or server-thread shortcut |

All six require production paths, deterministic behavioral comparison against the
frozen serial reference/pinned original methods, installed final-JAR evidence,
normal save/disk/restart/stop, affected foundation regressions, and one larger
view/simulation configuration in addition to established distance 5. Preserve
requested settings; 5 is a fixture choice, not a cap. Record lightweight overhead
only; no scaling, Folia performance, Android, or compatibility claims.

## Architecture constraints and concrete dependencies

The directory, gateway, owner epochs, leases, chunk custody, and established named
services remain authoritative. Check route/generation at body execution. Migrate
complete declared state only at quiescent boundaries and before reopening admission;
never replace an active operation's authority. Shared metadata gates do not cover
original callbacks, sends, future completion, I/O, or regional execution. Preserve
original synchronous observations, exception and RNG/order semantics. No peer-owner
waits, broad gameplay locks, fabricated readiness, fallback, or replayed effects.

Stage 3's save coordinator currently captures model logs; this is not installed
save-cut support. Its live level tick still contains large world-global phases.
Stage 4 must replace the selected service ownership/state at real boundaries.
The deferred same-holder re-promotion case (closure I) is not supported evidence:
if migration/unload/save fixtures reach it, authenticate new original publication
lineage without repeating conversion or resurrecting invalid old receipts. Earlier
FULL receipt does not prove later holder/save/retirement boundaries.

## Current verification and next unresolved case

Fresh baseline PASS: 690 tests / 59 suites, no failures/errors/skips, build exit 0;
production rebuilt to the exact frozen hash. Luna verified all ten selected historical
artifact/manifest/log hashes. Evidence: `Phoenium-stage4-luna/run/stage4/baseline-20260908-012726`;
details in [the verification note](STAGE4_VERIFICATION_NOTE.md). Luna now owns the
Stage 4 installed fixture, deterministic trace comparator, real client and disk/reload
harness. No integrated Stage 4 installed PASS exists yet.

Integrated dispatcher commits: `45a6023` / `bc6d43c` (Sol originals `28692e3` /
`77167ed`), explicit retained typed identity and lease validation, stale delivery
progress, opaque outcome handles, original unchecked failure identity; five focused
tests PASS. Save journal `feed049`: seven adverse tests PASS. Alias snapshot `eb49d38`:
one focused test PASS after fixing the test's missing Minecraft registry bootstrap;
the failed log/XML is preserved under `run/stage4/failed-snapshot-first`. Reentrant
stop `41eaf2d`: three focused tests PASS, original drain/ack/cleanup ordering preserved.
The latest main save adapter focused run (11 tests) is
`run/stage4/save-adapters-focused-first.log`; no broad campaign repeated by lead.

Actual save adapters now wrap original dirty claim, original snapshot, original write
future, final unload source retention/mutations, and flush acknowledgement. They compile;
the live runtime must initialize the shared dispatcher/save coordinator before new
installed evidence. Bootstrap precedes that installation; no selected failure falls
back. The source holder is retained before original conditional unload removal, and
its final save borrows that genuine lineage rather than FULL subscriber eligibility.

Sol is completing regional tick/event state and resumable live phases. Next unresolved
boundary: extract the original scheduled/event conditions, clock sample and profiler
instructions into owner-free coordination ranges without repeated observations;
also expose original chunk maintenance/save/tracking entry without a foreign outer
world lease. Then integrate, implement tracking/admission/drain/classification, and
dispatch one stable source to Luna. Same-holder re-promotion is still deferred and
must be repaired with fresh lineage if the new lifecycle fixture reaches it.
All complete Stage 4 acceptance items remain open until evidenced; internal commits
are recovery points, not completion or stopping gates.
