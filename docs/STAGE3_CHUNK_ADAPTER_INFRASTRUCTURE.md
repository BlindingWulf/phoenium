# Stage 3 chunk adapter infrastructure

## Status

This document records the reusable production-facing infrastructure added before
any authoritative Minecraft continuation is bound.

It implements the constrained bridge from a ready vanilla completion to a
current-owner admitted synchronous continuation.

It does not claim that any Minecraft 26.2 callback currently uses that bridge.

`R-CHUNK-01 = PARTIAL`

`Stage 3 = PARTIAL`

`Stage 4 = NOT STARTED`

## Decision

The locked design remains **A — coordinated multi-stage admission**.

`RegionDirectory` remains the exclusive ownership authority.

The adapter infrastructure does not infer authority from producer thread,
server thread, holder identity, topology generation, or a previously observed
route.

The synchronous atomic unit remains one named vanilla chunk continuation with
a closed footprint.

No broad gameplay lock, peer-region lock, owner wait, main-thread fallback,
or async lease is introduced.

## Component map

```text
vanilla completion / registration callback
          |
          v
PendingVanillaStage<I,O> --READY then QUEUED--> ChunkStageDeliveryPump
                                                   |
                                                   v
                                      current RegionDirectory route
                                                   |
                                                   v
                                      ChunkStageOwnerGateway inbox
                                                   |
                                                   v
                                           ChunkStageExecutor
                                                   |
                                                   v
                                      ChunkStageAdmission + short lease
                                                   |
                                                   v
                                 original VanillaChunkStage<I,O>, once
```

The producer's role ends after it retains stage input, publishes the stage, and
hands the typed object to the owner gateway.

The producer never invokes the retained continuation.

## Lifecycle registry

`ChunkLifecycleRegistry` binds real holder lifecycle evidence to
`ChunkCustodyModel.LifecycleId`.

The logical key is unchanged:

```text
server session
+ world instance sequence
+ world/dimension and ChunkPos (RoutingKey)
+ monotonic holder activation sequence
```

The registry has typed operations for holder activation, current lifecycle
resolution, same-holder observation, pending unload, rescue, retirement, and
currentness checks.

The holder reference is compared with `==` and is only lifecycle evidence.

It is not the complete identity and `System.identityHashCode` is not used for
correctness.

A topology generation change, owner transfer, additional demand, status
request, or legitimate pending-unload rescue does not create a lifecycle.

A true holder replacement, retirement followed by activation, or world
instance replacement creates a distinct activation sequence.

Pending-unload rescue returns the same live lifecycle if and only if the same
holder evidence is retained.

The registry uses a small metadata lock only.  It never guards chunk/gameplay
state and never chooses an owner.

## Shared work registry

`ChunkWorkRegistry` maps a real status/promotion work reservation to one
`WorkId`.

`WorkReservation(family, slot, reservationSequence)` is a typed lineage for a
vanilla holder/status reservation.  It is explicitly not a public
`getChunkFuture` call or a demand count.

Repeated resolutions of the same reservation return the same `WorkId`.

`addDemand` and `removeDemand` create separate `DemandId` subscriptions.

Removing the last demand does not retire committed shared work.

Only explicit work retirement changes the work lineage state.

The adapter must obtain its lifecycle and shared work identity before it
publishes an authoritative stage delivery.

## Pending vanilla stage

`PendingVanillaStage<I,O>` carries:

- lifecycle, work, and stage identifiers;
- world and `RoutingKey`/chunk position;
- semantic `ChunkStageKind`;
- correlation ID;
- exclusively retained or immutable upstream input;
- a single `VanillaChunkStage<I,O>` original continuation;
- custody-derived terminal state and a private `ChunkStageOutcome<O>` result.

`VanillaChunkStage<I,O>` is the closed typed functional surface retained by a
pending stage.  It is not a `Runnable`, generic `Callable`, or arbitrary
region-task API.

Input binds exactly once when the registered vanilla callback becomes ready.

Publication then records `READY` and `QUEUED` in `ChunkCustodyModel` before
the delivery pump can submit anything to an owner inbox.

This ordering prevents completion-registration loss races.

The custody model remains the state authority:

```text
READY -> QUEUED -> CLAIMED -> APPLIED
```

It also records supersession, cancellation, vanilla failure, and barrier
failure.  Route staleness is a delivery disposition, not a second state
machine.

## Delivery pump

`ChunkStageDeliveryPump` accepts a published typed pending stage from any
producer context.

It resolves the current route from `RegionDirectory` at delivery time.

It forwards the stage and current route only to `ChunkStageOwnerGateway`.

The gateway is typed to `PendingVanillaStage`, so it cannot become a generic
arbitrary-Runnable scheduler.

The pump does not join or get a future, block a producer, block an owner,
execute on its caller, use the server thread as fallback, acquire a peer lease,
or acquire two leases.

If an A/G1 inbox copy is delivered after the directory names B/G2,
`ChunkStageAdmission` returns `STALE_ROUTE` before claim.

The stage remains `QUEUED` and the same `StageId` is re-delivered after current
route resolution.

Therefore A executes zero times and B may invoke the original continuation
once.

The same process handles an A/G1 to A/G2 generation change without changing
the stage identity.

## Claim and lease execution

`ChunkStageExecutor` is the only standard bridge from a typed owner inbox to
`ChunkStageAdmission`.

It gives the admission component the delivered owner and generation plus a
narrow adapter that invokes only the pending stage's typed continuation.

`ChunkStageAdmission` validates current ownership, reserves the short
target-owner lease, claims the stage under its custody metadata gate, enters
`OwnershipContext`, and invokes the continuation synchronously.

The lease begins after the continuation is ready to execute and ends before any
future asynchronous work.

An already claimed continuation may finish during shutdown.

Shutdown before claim invokes nothing.

Duplicate owner deliveries cannot obtain a second claim.

An exception from the typed continuation is a barrier failure with no automatic
retry.

## Typed registration and future preservation

`ChunkStageRegistration<I,O>` supplies a typed registration callback for a
later Mixin/adaptor.  The callback binds its upstream input, publishes the
pending stage, and returns its private typed outcome stage.

The public vanilla future graph is not replaced by this infrastructure.

No future is joined or synchronously waited on.

Before a real seam is enabled, its exact continuation registration must prove
that the original upstream future, dependent-future role, result object,
exception behavior, and ordering remain intact.

In particular, an existing `thenApplyAsync` site cannot be casually changed
into a different public pipeline merely to defer owner execution.

The observed `ChunkMap.lambda$scheduleChunkLoad$3` seam is therefore not bound
in this pass.

## Private control outcomes

`ChunkStageOutcome` separates:

- applied value;
- control result (`STALE_ROUTE`, `SUPERSEDED`, `CANCELLED`, `SHUTDOWN`);
- genuine vanilla failure;
- barrier failure.

`STALE_ROUTE` deliberately leaves the stage eligible for re-delivery rather
than completing a fake failure or fake success.

Control is not represented as an I/O, parsing, or corruption exception.

The future Minecraft adapter must filter this private channel at the precise
registration/recovery boundary, before a vanilla `exceptionallyAsync` load
recovery handler could treat it as a corruption failure.

No real exceptional recovery seam is bound yet.

## Service bridge boundaries

Three narrow interfaces reserve the future integration boundaries.

`ChunkPoiApplicationBridge` separates coordinated POI prefetch/indexing from
owner-installed/reconciled POI records.  It provides no broad POI lock.

`ChunkLightingApplicationBridge` separates coordinated lighting computation
from owner-applied live flags and notifications.  It does not force light
computation onto an owner scheduler.

`ChunkMetadataBridge` represents coordinated map/type metadata with per-key
storage ordering and lifecycle validation.  It intentionally does not classify
all metadata as region-local.

These are contracts only; they do not yet bind Minecraft implementations.

## Deterministic evidence

`ChunkAdapterInfrastructureTest` proves INF-01 through INF-12:

- lifecycle identity, replacement, and pending-unload rescue;
- A-to-B reroute and same-owner generation refresh;
- publication-before-delivery;
- duplicate exactly-once invocation;
- old lifecycle fencing;
- independent demand and shared work survival;
- shutdown before and after claim;
- producer ambient context being ignored;
- stale control being distinct from vanilla failure;
- typed continuation failure becoming terminal barrier failure without retry.

These are production-infrastructure tests, not live Minecraft adapter proof.

## Explicitly not bound

No authoritative Minecraft Mixin is added in this pass.

There is no parsed-apply single-seam proof.

There is no POI, lighting, FULL conversion/publication, ticking, send, holder
publication, unload/reload, terminal teardown, delayed send, actual
supersession, or exceptional storage-recovery binding.

There is no C2ME or optimization-mod integration.

Workers remain one and parallel gameplay remains off.

## Next task

Bind the first authoritative Minecraft continuation only after its exact
registration can use this infrastructure while preserving its existing vanilla
future semantics, including its genuine exceptional recovery behavior.
