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
| I-08 | 6 | PandaAntiLag behavioral feature | Local limits and skip compensation pass entity-class audit. |

## Mandatory regression suite

The suite includes the Stage 0 evidence-derived tests R-LEASE-01, R-LOCK-01, R-LOCK-02, R-OWNER-01, R-OWNER-02, R-FALLBACK-01, and R-CHUNK-01 in [STAGE0_SYNCHRONIZATION.md](STAGE0_SYNCHRONIZATION.md), plus:

| ID | Regression |
| --- | --- |
| R-NET-01 | A packet cannot run player mutation from an unowned packet thread. |
| R-TP-01 | Teleport acceptance outside destination ownership is rejected. |
| R-VEHICLE-01 | Passenger desynchronization is detected and reconciled through a root-group protocol. |
| R-REDSTONE-01 | Foreign redstone/game-event/neighbor access never returns fabricated block state or silently cancels required behavior. |
| R-PANDA-01 | Pseudo-region keys include world identity and limiter work is owner-local. |

## Test discipline

Tests must use deterministic seeds, record scheduler worker count and topology configuration, fail on any ownership diagnostic, and retain minimized replay traces for concurrency failures. No test may pass merely because a fallback or broad lock masked an ownership violation.

