# Architecture

## Status and design intent

This is a Stage 0 architecture decision record. It defines contracts for the first implementation; it does not describe implemented behavior. No referenced implementation has been copied into Phoenium.

Phoenium will use a **serial-first regional scheduler**. Every simulation action will enter an explicit Phoenium ownership context. The initial scheduler runs one worker, so the same region gateway, leases, routing, and failure barriers are exercised before parallel workers are enabled. Parallelism is a later configuration that reuses those mechanisms; it is not a second execution path.

    Minecraft server integration
             |
        Owner gateway <--> Region directory <--> Topology coordinator
             |                                          |
      Phoenium scheduler -------------------------------+
         |                    |
    worker context A     worker context B
         |                    |
     Region runtime A   Region runtime B
         |                    |
     immutable save snapshot -> asynchronous I/O

## Components

| Component | Responsibility | Authority it may hold | Prohibited behavior |
| --- | --- | --- | --- |
| PhoeniumRuntime | Lifecycle root, configuration, hard failure barrier, diagnostics. | Scheduler state and immutable configuration. | Ticking a world directly or silently choosing vanilla fallback. |
| RegionDirectory | Publishes immutable location/entity-to-region ownership snapshots with a topology generation. | Current routing snapshot only. | Holding mutable entities, chunks, or task closures. |
| TopologyCoordinator | Plans split/merge/create/death transitions and commits directory generations. | Narrow topology gate and transition records. | Invoking game callbacks, serializing chunks, or waiting while holding the gate. |
| RegionScheduler | Deadline-aware dispatch of region work and owner leases. | Worker contexts, queues, and lease accounting. | Executing a region task without a lease or running unowned shutdown work. |
| OwnershipContext | Thread-local identity of the active region, lease epoch, and allowed operation category. | Assertions only; no durable world state. | Granting access merely because a caller is the server/main thread. |
| RegionRuntime | Per-region inbox, tick state, entity/chunk indexes, scheduled-work partition, and local service data. | Mutable data exclusively while its lease is active. | Direct reads or writes to another region's mutable data. |
| OwnerGateway | Resolves an envelope against the current directory and routes, stashes, reroutes, or rejects it. | Typed task envelopes and continuation scheduling. | Running arbitrary captured Runnable instances on a foreign or shutdown thread. |
| EntityTransferCoordinator | Executes entity/player/passenger-group transfer state machines. | Transfer records and admission tickets. | Moving a live entity object across a thread without source freeze and destination admission. |
| ChunkWorkAdapter | Converts chunk pipeline completion to owner-routed immutable completion messages. | Integration metadata and cancellation/reroute records. | Blocking on a chunk future, making a synthetic empty chunk, or applying a completion on its producer thread. |
| SaveCoordinator | Creates a consistent save cut and sends immutable snapshots to I/O. | Save epoch and pending snapshot ledger. | Asking arbitrary I/O threads to mutate world state. |
| GlobalServiceContext | Hosts explicitly global server services with their own policy. | Defined global-only services. | Acting as a loophole for cross-region mutable world access. |
| Diagnostics | Emits metrics, tracing, and violation reports. | Non-authoritative snapshots. | Controlling gameplay ownership or using a global diagnostic lock around simulation. |

Names are architectural placeholders until Stage 1 creates a package layout. They are intentionally not aliases for Ruthenium classes.

## Region and topology model

The topology semantics are derived from current Folia behavior and must be reimplemented for Fabric rather than copied:

1. Every loaded or held chunk has exactly one region owner.
2. The configured buffer around every owned holder is represented in topology ownership or a pending merge relationship.
3. A ticking region does not expand its ownership in place. A transient neighbor is created and merged after the active tick ends.
4. Region lifecycle is TRANSIENT, READY, TICKING, or DEAD.
5. A scheduler lease is separate from lifecycle state. Both tick work and queued task work must acquire and release a lease.

The scheduler lifecycle is independently RUNNING, QUIESCING, STOPPING, or FAILED. A failed scheduler accepts no new simulation work until a controlled operator action restarts Phoenium from a safe boot boundary.

### Split, merge, and migration

Topology transitions use a continuation protocol, never a broad gameplay lock:

1. The coordinator reads an immutable directory snapshot and plans a transition under a short topology gate.
2. It releases that gate, asks each participant's owner gateway to stop admitting ordinary work, and waits through scheduler continuations. No region worker waits synchronously for another region worker.
3. Participants reach a quiescent lease boundary. Their transition mailboxes accept new envelopes and reroute them after commit.
4. The coordinator briefly commits the new topology generation and directory snapshot.
5. Owner tasks move only declared, region-owned data into the new region runtimes. An envelope with an old generation is re-resolved and rerouted.
6. New owners admit work only after their required state is complete. A transition either reaches this state or fails Phoenium scheduling; it never exposes a partly migrated world through a vanilla fallback.

The gate may protect topology metadata, not entity simulation, packet handling, callbacks, save serialization, or arbitrary region tasks. Its hold time and contention are benchmarked.

## Task contract

All cross-context work is represented by a typed RegionTaskEnvelope, not an arbitrary Runnable:

| Required field | Purpose |
| --- | --- |
| operation kind and validated payload | Makes the action reviewable and prevents a captured live object from becoming authority. |
| world key and region routing key | Identifies the intended owner. |
| expected topology generation and owner epoch | Detects stale delivery after migration. |
| origin, correlation id, deadline, and sequence policy | Supports tracing, ordering, cancellation, and fairness. |
| admission policy | Defines reroute, defer, reject, or transition-mailbox behavior. |

Delivery re-resolves the owner from RegionDirectory. The owner validates both lease and epoch immediately before touching mutable state. A stale envelope is rerouted or rejected; it is never run where it happened to land.

## Entity and player transfer

Entity relocation uses this state machine:

    Requested
      -> SourcePrepared (source owner validates and freezes group)
      -> DestinationReserved (destination accepts target and epoch)
      -> Committed (directory and transfer record commit)
      -> DestinationApplied (destination reconstructs or adopts state)
      -> Completed (source release acknowledged)

The request can be Rejected or RolledBack before commit. An invariant breach after commit enters FailedBarrier.

The transfer unit is a root vehicle and its complete passenger graph. A player transfer also includes connection routing, teleport acceptance, and tracker changes as named transfer phases. Source and destination do not take peer locks and do not synchronously wait. A target cannot accept a teleport or movement update outside its current owner epoch.

## Chunk work and C2ME boundary

C2ME is a required compatibility target, but it is not a source of authority. Its current 26.2 modules and threading contract must be audited before an adapter is implemented.

The future adapter receives a completion, captures only immutable completion data plus the requested chunk key and request generation, and sends an envelope to OwnerGateway. The owning region applies it after validating current topology and chunk status. Producer threads never directly mutate regional state. Calls that would wait on a chunk future, obtain a foreign mutable holder, or substitute a fake chunk are rejected by design.

## Saving and shutdown

Saving establishes a save epoch, pauses ordinary admission through owner gateways, reaches lease boundaries, and asks each owner to create an immutable SaveSnapshot. I/O may serialize snapshots asynchronously. It may not call Minecraft mutation APIs. The save commit records exactly which topology and transfer generations are represented.

Shutdown enters QUIESCING, drains only envelopes that are valid for their owning runtime, resolves or records in-flight transfers, produces final snapshots, then stops workers. A standalone shutdown thread never executes a raw cross-region queue.

## Planned server integration

No mixin target is approved merely because an old implementation used it. Stage 2 will identify the minimal 26.2 server-tick interception point and prove, with a counter and dedicated boot test, that each tick category is routed once. Network, scheduled ticks, block events, entity tracking, redstone, and chunk pipelines enter in separate stages after their ownership contracts are tested.

## Stage 2 live binding (implemented)

The serial live binding is `MinecraftServer.tickChildren(BooleanSupplier)`, redirecting
its single direct `ServerLevel.tick(BooleanSupplier)` invoke.  The redirect does not
perform a world mutation itself.  It submits the closed `LiveLevelTick(worldKey)`
envelope through `OwnerGateway`, then `RegionScheduler.runOne` installs the normal P1
lease and `OwnershipContext` before its dedicated typed live-operation handler invokes
the original `ServerLevel.tick` call.  The envelope carries only a world key; the live
Minecraft object remains at the integration boundary and is never a routed payload.

Stage 2 represents each real `ServerLevel` present at server start as one region named
`stage2-live-world`. This is a controlled serial world-level scope, not geographical
regionalization. The scheduler reports one serial execution lane and parallel gameplay
is off. The lane runs on Minecraft's server thread because this stage intentionally
keeps vanilla server-level work on its required thread; authority still comes only from
the lease/context installed by the scheduler, never thread identity.

If the scheduler fails, the redirect consumes later matching calls without invoking
vanilla. This is a hard barrier, not a fallback. No peer-region lock, broad gameplay
lock, blocking owner wait, or additional simulation pool is introduced.

## Future compatibility layer boundary

## Stage 3 ownership boundary (implemented core)

Stage 3 adds a derived `ObjectOwnershipRegistry` for entity and player identities. Each record
contains a position routing key, directory generation/region epoch, and an independent object
epoch. The independent epoch is necessary because a moving entity can make old work stale even
when a later topology happens to reuse a region epoch. The registry is protected only by a narrow
metadata gate and validates against `RegionDirectory` before every owner operation; it is not a
second authority system.

`ObjectOwnerGateway` converts an `OwnedObjectEnvelope` to the pre-existing typed task path by
resolving current object ownership. The scheduler's closed object-executor seam rechecks the
object epoch, directory route, active ownership context, and executing region immediately before
mutation. Chunk producers use `ChunkWorkRequest` and `ChunkCompletionGateway` in the same way:
they send immutable completion metadata and never mutate a live region or wait on its result.

For the serial live bridge, every current `ServerLevel` publishes an explicit whole-world default
route. The live executor reconciles actual Minecraft entities/players to position keys under its
controlled lease. This is a declared temporary owner topology, not a grant of blanket authority;
the foreign-object regression uses separate A/B regions and proves the distinction.

This is a future API boundary, not an implementation commitment in Stage 0. It exists to reduce needless third-party forks while preserving this architecture's single ownership system. A compatibility integration may eventually expose region-local execution, entity-owner execution, explicitly global Phoenium execution, asynchronous calculation with owner-routed completion, ownership queries and assertions, immutable snapshots, typed task envelopes, capability discovery, and safe region-transition requests.

Every such operation must use the established OwnerGateway, RegionDirectory, OwnershipContext, region leases, topology generations, owner epochs, typed envelopes, GlobalServiceContext, and failure barrier. An adapter cannot establish an alternate owner registry, bypass lease validation, or make a foreign callback authoritative.

The boundary is intentionally limited. It cannot safely convert arbitrary single-threaded algorithms into region-parallel ones, including synchronous work that spans independently owned regions, immediate mutable foreign-region reads, synchronous multi-region mutation, unsafe global/static cache mutation, captured live Minecraft objects passed to foreign threads, blocking cross-region or chunk `Future.get`/`join`, or custom executors that mutate regional state. Such cases require an algorithm redesign, topology convergence or temporary merge, Phoenium-aware source changes, an upstream patch, or a maintained fork. A broad world lock or hot-path global serialization is not an acceptable generic repair.

An eventual conservative fallback for unknown legacy mods may serialize or quiesce work only when semantics cannot be inferred. It remains lease-respecting, measurable, diagnostic-visible, and unable to fall back unsafely to vanilla behavior. It is not the preferred execution model and cannot become a permanent high-frequency dependency for the required deployment stack.

## Stage 1 implementation status

The isolated serial core now implements the identity values, lifecycle state machines,
immutable directory, typed-envelope gateway, region lease/context, transition mailbox,
deterministic one-worker scheduler, failure barrier, generic transfer record/group, and
immutable save-cut model. Details and deferred live boundaries are in
[STAGE1_CORE.md](STAGE1_CORE.md). No Stage 0 architectural decision changed.
