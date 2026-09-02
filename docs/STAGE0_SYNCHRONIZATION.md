# Synchronization inventory and disposition

This is the Stage 0 classification of synchronization mechanisms found in the supplied Ruthenium source. Line references are approximate source locations at audited revision aebbbad87498c0718b1287a226789212e6103bcf. The full read-only search evidence is retained outside this project in the audit workspace; this document records the design decision each site requires.

| Sites or mechanism | Protected intent | Audit finding | Phoenium disposition |
| --- | --- | --- | --- |
| ThreadedRegionizer, StampedLock and synchronized region lookup paths | Region topology and directory lookup | A meaningful topology primitive, but correctness depends on precise buffer closure and callbacks must not run under it. | Rewrite as a timed, narrow TopologyGate for metadata only. |
| TickRegionScheduler initialization and budget timer monitors | Scheduler initialization and accounting | Narrow in places, but does not make unleased task work safe. | Use immutable configuration, atomics, and owner-local accounting. |
| TickRegionScheduler RegionScheduleHandle runTick | Tick execution serialization | Acquires region ticking state and releases it. | Preserve the lease concept, reimplement. |
| TickRegionScheduler RegionScheduleHandle runTasks | Queued work | Runs mutable queued tasks without the comparable tryMarkTicking/markNotTicking lease. A merge can treat it as READY. | Correctness blocker: every task execution must hold a lease. |
| RegionizedWorldData per-world ReentrantReadWriteLock | Chunk/world service access and broadcast coordination | All regional ticks take the read lock; writer work blocks all readers. Waiting paths risk broad contention and lock-upgrade style hazards. | Do not port. Ownership replaces the broad world lock. |
| RegionizedWorldData blockEventsLock and two-peer merge locking | Block-event queues and state movement | Merge locks this then other without a canonical identity order. Current topology may serialize callers, but the method is unsafe as a general API. | Do not use peer locks. Use a transition record and owner continuations. |
| RegionTaskQueue synchronized queue methods and transfer | Queue order and move | Transfers may lock this then target with no total ordering. | Replace with owner inboxes and transition mailboxes; no peer monitor locking. |
| WorldTickSchedulerMixin and ScheduledTickProcessor synchronized methods | Scheduled tick scheduler collections | Broad monitor serializes scheduling and update activity. | Partition scheduled ticks by owner region; cross-owner actions are envelopes. |
| ChunkTickSchedulerMixin synchronized methods | Chunk tick scheduling | Blanket synchronization substitutes for ownership. | Do not port until a per-region chunk work contract exists. |
| EntityTrackingSectionMixin, SectionedEntityCache, entity tracker mixins | Mutable entity collections | Blanket collection synchronization does not establish who may mutate entries. | Rebuild owner-local registries; audit PersistentEntitySectionManager integration later. |
| LevelPropagatorMixin and PendingUpdateQueue synchronization | Propagation/update queues | Prevents races by serializing shared vanilla structures without a topology protocol. | Defer; define ownership and completion routing before integration. |
| ServerChunkLoadingManager mixins and broadcast sets | Chunk loading, holder, and broadcast state | The old targets are mapping-stale and concurrency semantics are unproved with C2ME. | Do not port; use a future isolated adapter. |
| RaidManagerMixin synchronized map and tick | Shared raid state | Tick runs under a global monitor and position proof has an existential radius flaw. | Classify raids explicitly as global or regional; no synchronized-map shortcut. |
| Static ConcurrentHashMaps in RegionTaskDispatcher | Pending chunk task registry | Values are raw Runnable work keyed by mutable ServerWorld. Shutdown can execute them unowned. | Replace with typed envelopes, request generations, and owner rerouting only. |
| Static ConcurrentHashMaps in player-transfer code | Transfer snapshots | Entries have no ownership epoch; captured state is not fully applied and production callers are absent. | Replace with durable transfer records and explicit state machine. |
| Static ConcurrentHashMaps in networking, monitors, debug, failure handler | Registry or diagnostic state | Some are reasonable snapshot/counter candidates; none can be authority for mutable game state. | Allow only non-authoritative counters/snapshots after API review. |
| Atomic booleans/counters and ThreadLocals in scheduler | Lifecycle and worker context | Appropriate primitives only when paired with a lease and strict cleanup. | Use for scheduler state and OwnershipContext, with hard cleanup assertions. |
| CheckedRandom thread-local/shared seed behavior | Random-state isolation attempt | Global seed writes, non-volatile initialization, and changed ordering make determinism unclear. | Do not port. Establish deterministic test policy separately. |
| Thread.sleep, Thread.join, shutdown/watchdog waits | Diagnostics and shutdown | Found in watchdog/shutdown paths, not ordinary future waits. They are still not a simulation coordination method. | Permit only outside regional simulation and instrument timeouts. |
| CompletableFuture.join, Future.get, semaphores, CountDownLatch | Blocking primitives | No direct main-source occurrences found in the audit scan. | Continue to prohibit blocking cross-region waits by invariant T-05. |
| RegionTickStats and RegionDebug synchronized methods | Statistics and debug snapshots | Diagnostic scope; risk is low if snapshots remain non-authoritative. | Keep diagnostics owner-independent and never lock gameplay to render a report. |

## Specific hazards that become test cases

| Regression ID | Source evidence | Required Phoenium proof |
| --- | --- | --- |
| R-LEASE-01 | Ruthenium queued task handle lacks a ticking lease. | Attempt a merge while a task is active; topology must wait for the lease boundary. |
| R-LOCK-01 | Per-world reader/writer lock covers regional tick operations. | Parallel regions continue without a shared world-gameplay lock; topology gate hold time remains bounded. |
| R-LOCK-02 | Peer queue and block-event locks have no global lock order. | Simultaneous reciprocal transfers complete without deadlock or peer locks. |
| R-OWNER-01 | Pending raw task executes from shutdown thread. | Shutdown rejects/reroutes stale work and never mutates region state from an unowned thread. |
| R-OWNER-02 | Main-thread access is treated as permitted and radius ownership is existential. | Foreign loaded chunk reads and partial-radius ownership fail assertion or use a protocol. |
| R-FALLBACK-01 | Scheduler failure defaults to vanilla fallback with assertions/logging off. | Inject failure; prove affected tick advancement stops behind the barrier. |
| R-CHUNK-01 | Missing chunk can become an EmptyChunk. | Foreign/missing chunk never becomes fabricated world state. |

