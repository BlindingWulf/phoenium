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
7% used / 93% remaining. Tick/event + save/stop integration: Codex 44% used / 56% remaining; reserve 7% used / 93% remaining. These are account windows, not per-task consumption.

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
artifact/manifest/log hashes. Evidence: `Phoenium-stage4-luna/run/stage4/baseline-20260908-012726`.
The current installed acceptance harness and real offline TCP client extension are
Luna commits `4afc7ce` / `3706b50` (not yet integrated). The frozen-reference fixture
has actual save/restart/MCA evidence and a client run at 8/8 with positive tracking
pair/change/recipient/remove markers. Marker presence is not deterministic equivalence;
Luna is controlling fixture timing and testing trace repeatability and restored tick effects.

Integrated dispatcher: `45a6023` / `bc6d43c`. Original save journal/adapters:
`feed049` / `b02ad5e`; retained topology API `5ba166a`. Initial regional tick/event
services and resumable phases: `0a5d1b7` (Sol `80f8992`). Review identified due-time
selection, current-cycle query, event dedup, producer ownership, and topology failure
issues in that first patch; Sol is correcting them before installed acceptance.

The lead save/stop revision now binds the actual original selected holder, save-all
readiness/cut and per-operation autosave/unload cuts, named save/storage access,
admission close, accepted-work scheduler drain, and failure-independent cleanup.
Focused run `run/stage4/save-stop-final-focused-20260908.log`: 19 tests / five suites,
zero failures/errors, including original lower-stage shutdown continuation, retained
pending writes, source replacement, migration, reentrant release and barrier failure.
The earlier missing-bootstrap snapshot failure is preserved under
`run/stage4/failed-snapshot-first`. No new broad campaign has been repeated by lead.

[The service contract](STAGE4_SERVICE_CONTRACT.md) classifies all existing named/shared
services and retained save/stop lifetimes. Installed proof of those boundaries is pending.

Owners and next unresolved cases: Sol finishes corrected tick/event semantics,
owner-free cache tracking/maintenance, late migration binding, retained save-cut
transition progress and shared-barrier checks, then implements production tracking.
Lead owns save/stop/classification integration and actionable review. Luna owns the
controlled installed comparator, client, real disk/reload, migration/adverse lifecycle,
5/5 plus 8/8, affected FULL/packet/service/saved-vault and lightweight overhead campaign.
Dispatch the next stable integrated source only after the coordinator/save-cut changes
are compatible. No integrated Stage 4 installed PASS exists yet. Same-holder re-promotion
remains deferred and must gain fresh genuine lineage if the new fixture reaches it.
Internal commits remain recovery points, not completion or stopping instructions.
Latest lead checkpoint: `89a3b56` connects retained save batches and ordered shutdown.
Production region runtimes now keep tick counters without accumulating the model's
completed-operation string history. A 50,000-tick focused witness plus core, save-cut
and shutdown monitor-boundary checks pass (34 tests). The initial core source-string
guard failure is preserved under `run/stage4/failed-core-gate-20260908`; its two new
metadata exemptions retain direct `Thread.holdsLock` callback assertions and the
negative broad-gameplay-lock coverage. Repaired run:
`run/stage4/live-retention-focused-repaired-20260908.log`.
