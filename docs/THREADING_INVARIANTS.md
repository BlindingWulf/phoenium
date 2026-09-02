# Threading invariants

These rules are the acceptance contract for every future Phoenium change. An assertion must be enabled in development and test builds for every rule marked **assert**. A production violation trips the Phoenium failure barrier and produces a diagnostic record; it does not fall through to normal vanilla ticking.

## Ownership and execution

| ID | Invariant | Enforcement |
| --- | --- | --- |
| T-01 | Mutable region data is accessed only by its active owner lease. | **Assert** active OwnershipContext, region id, and lease epoch at each boundary API. |
| T-02 | Being on the Minecraft server/main thread grants no regional ownership. | **Assert** every access through ownership APIs, regardless of thread identity. |
| T-03 | A mutable entity, chunk-side service, scheduled-tick partition, block-event queue, tracker entry, or connection routing record has one declared owner at a time. | **Assert** owner index and transition state. |
| T-04 | Cross-region work is a typed envelope that re-resolves ownership at delivery. | Reject raw cross-region closures and verify routing generation. |
| T-05 | An owner task may not synchronously wait for another region, a chunk future, a scheduler future, or a save future. | Static review, test instrumentation, and runtime blocking detector at gateway APIs. |
| T-06 | Global services use a declared global context and may consume immutable regional snapshots only unless a specific protocol says otherwise. | Separate API package and snapshot type. |

## Region lifecycle and topology

| ID | Invariant | Enforcement |
| --- | --- | --- |
| T-07 | Every live chunk holder maps to exactly one region in the published directory. | Topology property tests and directory consistency scan. |
| T-08 | Buffer closure, merge radius, and ticking-region expansion rules are maintained through every create/split/merge path. | Generative topology tests against configured radii. |
| T-09 | Lifecycle values are limited to TRANSIENT, READY, TICKING, and DEAD; illegal transitions fail fast. | Transition table with exhaustive validation. |
| T-10 | Both a tick and queued task execution hold a region lease for their full mutable operation. | **Assert** acquire/release pairing; no task-only bypass. |
| T-11 | Directory publication and topology mutation are generation-based. A stale route cannot mutate the prior owner. | **Assert** generation and owner epoch at envelope delivery. |
| T-12 | The topology gate protects metadata only and is never held across arbitrary game code, callback dispatch, I/O, peer waits, or serialization. | Timed gate instrumentation and code review rule. |

## Transfers, networking, and chunk work

| ID | Invariant | Enforcement |
| --- | --- | --- |
| T-13 | Entity and player transfer has source preparation, destination admission, commit, application, and completion/rollback states. | Transition-record assertions and crash-recovery tests. |
| T-14 | A vehicle and all passengers migrate as one transfer group. | Graph completeness check before source removal. |
| T-15 | Packet processing validates player/connection ownership and schedules through the owner gateway. | Packet ingress tests and stale-teleport tests. |
| T-16 | A chunk pipeline completion is applied only by the current owner after request-generation validation. | C2ME adapter contract test; no producer-thread application. |
| T-17 | A missing or foreign chunk is not replaced with an empty, air, bedrock, or other fabricated world value. | Forbidden fallback tests and API review. |
| T-18 | Redstone, neighbor, piston, comparator, and game-event paths either prove local ownership or use an explicit deferred/merge protocol. | Boundary regressions; no silent cancellation or fake state. |

## Failure, saving, and observability

| ID | Invariant | Enforcement |
| --- | --- | --- |
| T-19 | A scheduler failure creates a hard Phoenium barrier before any affected world can advance. | Dedicated test injects worker failure and verifies no vanilla tick fallback. |
| T-20 | Strict checks and fallback logging are on by default in all non-release test profiles; production records every barrier event. | Configuration test and startup diagnostics. |
| T-21 | Saving captures immutable owner-produced snapshots for a defined topology/transfer generation. | Save-cut test while regions merge and entities transfer. |
| T-22 | Shutdown does not execute raw pending work from an unowned thread. | Shutdown stress test and envelope audit. |
| T-23 | Diagnostics are snapshots and counters, never an alternative authority path for world state. | API boundary and race test. |
| T-24 | Any compatibility adapter is isolated; an unproven adapter feature remains disabled behind a clear startup capability result. | Dedicated boot and capability report. |

## Synchronization policy

Phoenium may use atomics, immutable snapshots, owner-local queues, and a narrow topology gate. It may not introduce a broad per-world gameplay lock, monitor-serialize all scheduled ticks, synchronize arbitrary vanilla collection methods as a substitute for ownership, or use lock ordering between peer regions.

Where a transition needs multiple regions, the coordinator uses state records and asynchronous continuations. It does not retain one region's lock while acquiring another's. Timeouts are diagnostics that trip the failure barrier; they do not authorize a forced, unowned operation.

