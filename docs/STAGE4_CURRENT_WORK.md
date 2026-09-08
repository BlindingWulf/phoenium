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
remaining. These are account windows, not per-task consumption.

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

Luna is establishing one fresh baseline clean build/test and hash-checking historical
live evidence. No new live campaign started. Sol's first implementation assignment is
regional scheduled ticks/block events and their shared serial dispatch/migration
boundary. Lead is inspecting actual save/stop producers and dependencies. Next:
settle the regional execution seam without retaining a foreign outer world lease;
then integrate and give Luna one stable revision for connected harness verification.
All Stage 4 acceptance items remain open until evidenced; internal commits are
recovery points, not completion or stopping gates.
