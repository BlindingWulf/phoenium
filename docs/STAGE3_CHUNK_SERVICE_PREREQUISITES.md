# Stage 3 chunk service prerequisites

## Status

This document records the prerequisite layer added before the first
authoritative chunk-application binding.

The architecture remains **A — coordinated multi-stage admission**.

`R-CHUNK-01 = PARTIAL`

`Stage 3 = PARTIAL`

`Stage 4 = NOT STARTED`

There is no `PARSED_APPLY` Mixin in this pass.

There is no `thenComposeAsync` or exceptional recovery composition in this
pass.

The layer is intentionally useful before Minecraft callbacks are bound: it
represents completed prerequisite service work without granting ownership.

## Authority boundaries

`RegionDirectory` remains the only regional ownership authority.

`ChunkCustodyModel` remains the lifecycle, work, and stage authority.

`ChunkStageAdmission` remains the future short-lease claim authority.

The components here are neither a replacement lease system nor a global
gameplay lock.

They are world-scoped coordinated-service ledgers.

Their receipts are proof of work already completed.

A receipt is not permission to perform a regional mutation.

## Lifetime root

`ChunkPrerequisiteServices` is a real world-scoped production lifetime root.

`PhoeniumLiveRuntime` constructs one instance per `ServerLevel` at server
startup.

It shuts every instance down during server stopping and clears the map.

No Minecraft chunk callback consults the root yet.

That deliberate absence preserves the accepted future-graph checkpoint.

The root contains:

- `ChunkCustodyModel`, `ChunkLifecycleRegistry`, and `ChunkWorkRegistry`;
- `PoiInstallService`;
- `LightInputService`;
- `ChunkTypeMetadataService`; and
- `LoadApplyPrerequisites`.

Construction is demand allocated.

There is no per-chunk, per-section, or per-region preallocation.

## POI install identity

`PoiInstallService.InstallId` has these equality components:

```text
LifecycleId
+ WorkId
+ section scope key
+ monotonic POI install sequence
```

`LifecycleId` already contains server-session, world-instance, world identity,
chunk routing key, and holder activation sequence.

The section key is included because the observed POI storage operation is
section-scoped.

The sequence distinguishes a genuine later installation from accidental
duplicate delivery.

The service rejects an identity whose work does not belong to its lifecycle.

It also rejects non-current lifecycle or retired-work identities.

## POI race handling

The POI ledger has a strict local state progression:

```text
absent -> INSTALLING -> INSTALLED
```

The first read-triggered unpack or pending-load drain receives `WON`.

The other path receives `INSTALLING` and does not wait, join, or execute a
second physical installation.

The eventual admitted adapter calls `installed` only after the one real POI
installation succeeds.

At that point both paths observe the same immutable `PoiInstallReceipt`.

No service method performs vanilla POI mutation in this pass.

The small `ReentrantLock` guards only this receipt map.

It is not held across POI storage work, a region lease, or a callback.

Pending-unload rescue preserves the same lifecycle and therefore preserves a
valid installed receipt.

True lifecycle replacement makes the old receipt unusable.

## Light-input identity and receipt

`LightInputService.BatchId` has:

```text
LifecycleId
+ WorkId
+ monotonic light-input batch sequence
```

Each required payload is a `SectionInput`:

```text
LightLayer (BLOCK or SKY)
+ section key
+ input sequence
```

The service records an actual declared batch shape.

It never invents a BLOCK or SKY input merely to meet a fixed template.

The caller declares whether `retainData` is required and which section inputs
are required for this actual load branch.

The required ordering is:

```text
retain intent
+ exact BLOCK/SKY inputs
-> accepted coordinated ingress
-> LightInputReceipt
```

The receipt means that all required input was accepted by the coordinated
light service.

It explicitly does **not** mean light computation, light propagation, chunk
lighting completion, live flags, or notifications have completed.

Duplicate logical retain or section delivery is `DUPLICATE`.

Different layers, sections, and input sequences remain distinct identities.

The service contains no `RegionLease` field and never acquires one.

Future vanilla or ScalableLux compute can remain coordinated and later issue a
typed result/notification for separately admitted regional application.

## Ordered metadata façade

`ChunkTypeMetadataService` is the narrow façade for the shared chunk-type-cache
role currently performed by `ChunkMap.markPosition` and
`ChunkMap.markPositionReplaceable`.

It exposes `markPosition` and `markPositionReplaceable`, not its mutable map.

`markPosition` returns the exact prior byte, or `ABSENT`, before writing.

That preserves previous-value semantics rather than reducing the operation to
an unverified map write.

The façade has 64 fixed metadata stripes.

One stripe linearizes one metadata key.

Unrelated keys can use different stripes and require no peer-region ordering.

The fixed stripe count prevents unbounded lock-object allocation.

The backing entries are concurrent, so different stripe operations do not
unsafe-access a plain shared map.

These locks guard metadata ordering only.

They are not region locks, world/gameplay locks, or held while running vanilla
chunk code.

Before commit, every update checks lifecycle currentness.

An old lifecycle write is rejected before it can replace current metadata.

`markPositionReplaceable` refuses to downgrade a current `FULL` marker.

This protects the type-cache role used by vanilla full-chunk save protection.

Topology generation is intentionally not part of lifecycle equality.

A current lifecycle update remains valid through a topology-only generation
change; a stale lifecycle remains invalid.

## Load-application prerequisite query

`LoadApplyPrerequisites` represents the future Decision A input gate.

It returns one of:

```text
READY
NOT_READY
STALE
FAILED
SHUTDOWN
```

The current implementation returns `READY`, `NOT_READY`, `STALE`, or
`SHUTDOWN`; `FAILED` is reserved for a future explicit prerequisite terminal
failure contract.

It never blocks, spins, joins a future, or calls `get`.

It does not claim a stage or run an application continuation.

`Required.fresh()` requires only valid lifecycle/work and metadata
preconditions.

`Required.reload(poi, light)` requires those plus exactly the named POI and
light-input receipts.

This difference is intentional: fresh generation cannot be made to wait for
persisted-load inputs it never created.

A receipt with the wrong lifecycle, work lineage, or world is stale, never
ready.

Missing valid input is `NOT_READY`.

## Cleanup and shutdown

Receipt ledgers are removed by `forget`, lifecycle retirement, or shutdown.

`ChunkPrerequisiteServices.retire` removes POI, light, and metadata entries for
the retired lifecycle.

Shutdown closes new admission and clears the receipt maps.

After settlement the receipt-entry count is zero.

The registries retain no holder references through these service entries.

The services allocate entries only for demanded identities.

## Failure classification

These ledgers do not route every service error to `FailureBarrier`.

A genuine future POI or light engine failure must remain a genuine service or
vanilla failure at its exact adapter boundary.

Wrong-lifecycle use, duplicate physical installation, or foreign regional POI
mutation remain ownership-invariant problems for the future binding layer.

This service layer performs no regional mutation, so it cannot classify a
vanilla exception as an authority breach merely by observing a receipt.

## Deterministic proof

`ChunkPrerequisiteServicesTest` provides PRQ-01 through PRQ-18.

It covers normal and duplicate POI receipt settlement, read/background racing,
old lifecycle rejection, and pending-unload rescue.

It covers retain plus BLOCK/SKY ingress, duplicate inputs, distinct inputs,
and the absence of a light lease surface.

It proves metadata previous-byte behavior, same-key order, distinct-key state,
stale lifecycle rejection, and full-marker protection.

It proves fresh/reload branch differences, non-blocking missing receipt state,
and shutdown cleanup.

The tests are deterministic logical simulations, not flaky thread races.

## Explicitly not bound

No authoritative Minecraft Mixin was added.

No POI storage mutation is intercepted.

No light-engine method is intercepted.

No type-cache call site is intercepted.

No `PARSED_APPLY` continuation is bound.

No holder private-control filter or exact returned-future index is installed.

No `STATUS_PUBLISH`, FULL, ticking, sending, recovery, C2ME, or optimization
mod work is included.

Simulation workers remain one.

Parallel gameplay remains off.

## Next task

The next pass may bind Decision A's exact atomic `PARSED_APPLY` continuation
only after using these receipts as non-blocking prerequisites and preserving
the specified vanilla future and recovery semantics.
