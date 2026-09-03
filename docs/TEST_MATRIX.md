# Test matrix

## Evidence recorded in Stage 0

| ID | Check | Result | What the result means |
| --- | --- | --- | --- |
| P0-E-01 | Supplied reference inventory and upstream content comparison | PASS | Local Folia and Ruthenium match audited current upstream except listed batch-file line endings; supplied Panda matches a pre-26.2 upstream revision. |
| P0-E-02 | Current source revision, license, and archival status review | PASS | Source hierarchy and reuse policy are documented. |
| P0-E-03 | Official Minecraft 26.2 artifact digest and class-name inspection | PASS | Java 25 and current Mojang mapping targets were verified for planning. |
| P0-E-04 | Ruthenium source and test inventory review | PASS | 77 main Java files, 13 test classes, 29 test methods were inspected; the tests were not executed. |
| P0-E-05 | Phoenium build | NOT RUN | There is no build in Stage 0. |
| P0-E-06 | Phoenium unit, integration, or dedicated boot | NOT RUN | There is no Phoenium runtime in Stage 0. |

PASS here means the forensic check completed. It does not mean an unimplemented runtime is correct.

## Future deterministic core tests

| ID | Stage | Scenario | Required oracle |
| --- | --- | --- | --- |
| C-01 | 1 | One region runs ticks and typed local tasks | Exactly-once operation order and lease pairing. |
| C-02 | 1 | Stale envelope arrives after owner generation changes | It is rerouted/rejected and cannot mutate old owner state. |
| C-03 | 1 | Split and merge across randomized holders and radii | Directory uniqueness, buffer closure, legal lifecycle transitions. |
| C-04 | 1 | Task active during requested topology transition | Transition waits for lease boundary; task never runs unleased. |
| C-05 | 1 | Reciprocal task and migration pressure | Completion without peer locks, synchronous waits, or deadlock. |
| C-06 | 1 | Vehicle passenger graph transfer | Complete group is admitted or remains intact at source. |
| C-07 | 1 | Injected worker exception | FAILED barrier activates; no vanilla-tick escape path. |
| C-08 | 1 | Save cut during migration and transfer | Snapshot has one coherent generation and no duplicate entity ownership. |

## Server integration gates

| ID | Stage | Scenario | Required oracle |
| --- | --- | --- | --- |
| I-01 | 2 | Dedicated Fabric 26.2 server boots with Phoenium disabled and enabled | Clean start/stop, valid server-only metadata, capability report. |
| I-02 | 2 | Controlled single world tick interception | Each expected tick category is counted once; no double tick. |
| I-03 | 2 | Serial-first live region scheduler | Live server progress uses Phoenium context with one worker; disabling integration is explicit startup configuration, not failure fallback. |
| I-04 | 3 | Chunk load/unload and off-thread completion simulation | Completion routes to current owner without blocking or fabricated chunks. |
| I-05 | 3 | Entity crossing boundary and player teleport | Epoch checks, transfer state machine, packet ownership, passenger grouping. |
| I-06 | 4 | Scheduled ticks, block events, trackers, save, and shutdown | No global gameplay lock; consistent save and clean stop. |
| I-07 | 5 | Parallel regions under stress | No ownership violations; serial and parallel final state match under deterministic harness. |
| I-08 | 7 | PandaAntiLag behavioral feature | Local limits and skip compensation pass entity-class audit; Panda Anti-Lag remains absent from the final runtime stack after native replacement. |

## Required-stack compatibility gates

No required-stack compatibility test has been run in Stage 0. Every target in [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) therefore remains `NOT_TESTED` until recorded execution evidence exists.

| ID | Stage | Scenario | Required oracle |
| --- | --- | --- | --- |
| M-01 | 6 | Per-target startup and shutdown in every relevant configuration mode | Clean lifecycle is necessary but never sufficient; capability result and ownership diagnostics are retained. |
| M-02 | 6–8 | Major upstream gameplay/runtime features | Intended upstream functionality is feature-complete, or a deliberate equivalent-or-better native replacement is proven. Disabling a major feature is `PARTIAL`, not `PASS_CONFIG`. |
| M-03 | 6–8 | Phoenium ownership and required-stack interaction | No unleased mutable access, foreign mutation, fabricated world state, disabled ownership assertion, or silent fallback. |
| M-04 | 6–8 | Serial and parallel Phoenium modes | Both modes preserve declared behavior; serial success alone is insufficient for a parallel production target. |
| M-05 | 6–8 | Save, restart, region transition, and applicable stress | Completion routing, cancellation, topology/owner generation, and persistence remain correct under repeatable load. |
| M-06 | 7 | Proposed patch or fork | Reproduced failure, repeatable test, identified violated invariant, adapter evaluation, provenance, license, independent build, and regression/feature-completeness tests exist before acceptance. |
| M-07 | 7–9 | Fork Phase A / B / C separation | Phase A proves feature parity and ownership in serial/parallel modes before Phase B integration; Phase C optimization has distinct benchmark evidence. |

## Mandatory regression suite

The suite includes the Stage 0 evidence-derived tests R-LEASE-01, R-LOCK-01, R-LOCK-02, R-OWNER-01, R-OWNER-02, R-FALLBACK-01, and R-CHUNK-01 in [STAGE0_SYNCHRONIZATION.md](STAGE0_SYNCHRONIZATION.md), plus:

| ID | Regression |
| --- | --- |
| R-NET-01 | A packet cannot run player mutation from an unowned packet thread. |
| R-TP-01 | Teleport acceptance outside destination ownership is rejected. |
| R-VEHICLE-01 | Passenger desynchronization is detected and reconciled through a root-group protocol. |
| R-REDSTONE-01 | Foreign redstone/game-event/neighbor access never returns fabricated block state or silently cancels required behavior. |
| R-PANDA-01 | Pseudo-region keys include world identity and limiter work is owner-local. |

## Stage 1 execution evidence

The isolated deterministic suite implements C-01 through C-08 and these applicable
regressions: R-LEASE-01, R-LOCK-01, R-LOCK-02, R-OWNER-01, R-OWNER-02, and
R-FALLBACK-01. R-CHUNK-01 is precisely not applicable: Stage 1 deliberately has no
missing-data/chunk abstraction, and therefore no fabricated semantic state path.
The suite also checks lifecycle validation, transfer post-commit barrier behavior,
blocking-owner-wait instrumentation, server-only metadata, and the no-Yarn build
correction. Actual command results are recorded in [HANDOFF.md](HANDOFF.md).

## Stage 2 execution evidence

| ID | Result | Evidence |
| --- | --- | --- |
| I-01 / R-FABRIC-SERVER-01 | PASS | Disposable dedicated Fabric 26.2 server loaded Phoenium 0.2.0-stage2 with Loader 0.19.5, Java 25, `fabric-api-base` and `fabric-lifecycle-events-v1`; server-only metadata and no client entrypoint were inspected in the production JAR. RCON `/stop` exited cleanly. |
| I-02 / R-LIVE-TICK-01 | PASS | Accepted redirect recorded `180 = 180 = 180` and `177 = 177 = 177` intercept/dispatch/execution observations across separate fresh server processes. Duplicate, bypass, and fallback counters were all zero. An earlier 19-second observation recorded `1173 = 1173 = 1173`. |
| I-03 / R-LIVE-OWNER-01 | PASS | `rLiveOwner01LivePayloadNeedsTheSameLeaseAndContextAsAnyOtherOwnerOperation` proves direct caller-thread mutation fails while the same typed live payload succeeds only under the scheduler lease/context. Live diagnostics report one serial lane and parallel OFF. |
| R-LIVE-FALLBACK-01 | PASS | Test-only `-Dphoenium.test.failLiveTickAt=10` stopped execution at 9 completed operations. Later intercepted calls were consumed by the FAILED scheduler: `1131 intercepted, 10 dispatched, 9 executed, 0 bypass, 0 fallback, 1 barrier`. The disposable server then accepted clean RCON `/stop`. |

Stage 2 controls only the single direct server-level dispatch in `MinecraftServer.tickChildren`.
Entity internals, scheduled block/fluid ticks, block events, random ticks, tracking,
packets, chunk completion, saves, and cross-world/region parallelism remain vanilla or
unimplemented as regional mechanisms.

## Test discipline

Tests must use deterministic seeds, record scheduler worker count and topology configuration, fail on any ownership diagnostic, and retain minimized replay traces for concurrency failures. No test may pass merely because a fallback or broad lock masked an ownership violation.
