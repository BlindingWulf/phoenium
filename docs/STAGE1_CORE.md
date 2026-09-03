# Stage 1 isolated deterministic core

## Scope boundary

This implementation is deliberately independent of live Minecraft world simulation.
There are no Mixins, live tick hooks, Minecraft world objects, packet routes, entity
tick routes, C2ME adapters, or parallel simulation workers. The Fabric entrypoint is
only a server-side load marker.

## Core execution path

```text
typed envelope -> RegionDirectory snapshot -> OwnerGateway -> owner inbox
    -> serial scheduler -> RegionLease + OwnershipContext -> typed payload apply
```

The only Stage 1 payload vocabulary is `Append`, `Add`, and the test-only injected
`Fail`. This makes the core deliberately unable to use an arbitrary captured
`Runnable` as cross-region authority. `RegionLeaseManager` permits exactly one active
lease per runtime. Each mutable `RegionRuntime` operation asserts the active
thread-local `OwnershipContext` and matching lease epoch; context is never inferred
from a worker or server thread identity.

The serial scheduler has exactly one worker and is the only executor of owner inboxes.
It advances non-empty READY runtimes in stable `RegionId` order. A failure trips
`FailureBarrier`, changes lifecycle to `FAILED`, rejects ordinary work, and never
executes pending work on another thread or falls back to vanilla.

## Topology and transitions

`RegionDirectory` atomically publishes immutable mappings of `RoutingKey` to
`RegionRoute(region, topology generation, owner epoch)`. A route is discovery data,
not mutation authority. The gateway re-resolves every envelope and applies its explicit
reroute/defer/reject policy when it is stale.

`TopologyCoordinator` has one `ReentrantLock` named `metadataGate`. It only changes
transition records and publishes a fresh directory generation; it never executes
owner code while held. A transition closes ordinary admission on participants; new
envelopes enter the ordered transition mailbox. Scheduler-driven `advance()` commits
only after all participants have no active lease, increments owner epochs, and then
releases mailboxes for fresh owner resolution.

`TopologyPlanner` is a pure deterministic connected-component merge planner for the
isolated holder model. Its property-style test records the seed `0x50E11A5E`.

## Transfer and save models

`TransferRecord` implements REQUESTED -> SOURCE_PREPARED -> DESTINATION_RESERVED
-> COMMITTED -> DESTINATION_APPLIED -> COMPLETED. It rejects a stale destination epoch,
allows rejection/rollback only pre-commit, and trips the barrier on an unrecoverable
post-commit error. `TransferGroup` requires the full reachable vehicle/passenger graph.

`SaveCoordinator` uses owner leases to copy immutable per-runtime test data against
one directory generation. It has no I/O or Minecraft serialization path.

## Build correction and deferrals

See [STAGE1_BUILD_COMPATIBILITY.md](STAGE1_BUILD_COMPATIBILITY.md). Loom 1.17.20
detects the locked 26.2 artifact as already official-named/non-obfuscated. The build
therefore avoids its rejected mapping-layer and remap configuration calls while still
using official names and no Yarn declaration.

Live Minecraft interception, all live tick categories, real chunks/entities/packets,
C2ME, redstone, actual serialization, dedicated-server boot, and multi-worker
parallelism are explicitly deferred to Stage 2 and later.
