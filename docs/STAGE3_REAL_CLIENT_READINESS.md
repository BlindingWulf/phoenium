# Stage 3 real-client readiness

This document defines the pre-client configuration and future evidence plan. It does
not record a real connection, movement packet, or interaction packet.

## Readiness distinction

The earlier `NO` assessments were **not ready to test**, rather than merely **not
tested**: live boot had only a Stage 2 world-default route, player ownership was lazy at
packet ingress, and the interaction candidate index lacked lifecycle-time removal
invalidation. Those technical prerequisites are closed by the development fixture and
lifecycle adapters described here. The paths are now ready to receive their first
controlled real packet, though neither has yet received one.

## Explicit development topology

Start the disposable server with:

```text
-Dphoenium.stage3.explicitTopology=true
```

For every loaded server world the fixture publishes generation `1`, no world default,
and two real region IDs:

| Region | Explicit chunk domain relative to `(0, 0)` | Purpose |
| --- | --- | --- |
| `stage3-fixture-a` | `x=-4..4`, `z=-4..4` | Spawn and same-owner route |
| `stage3-fixture-b` | `x=5..8`, `z=-4..4` | Escape/cross-owner witness |

The domains are disjoint. Missing keys resolve to no owner; they never fall through to
the serial Stage 2 world default. Each region has a real runtime and owner epoch `0`,
but only one scheduler worker exists and parallel gameplay remains off. This fixture is
development-only and is not a general regional topology implementation.

## Player bootstrap and movement snapshot

`PlayerList.placeNewPlayer` publishes the initial player binding after vanilla placement
and before any movement packet can enter `handleMovePlayer`. The object ID is the player
UUID, the initial incarnation is established by `ObjectOwnershipRegistry`, the network
entity ID receives a derived candidate index entry, and the current routing key must be
an explicit fixture key. A position without an explicit route is rejected at lifecycle
admission; no packet can bootstrap it through a world default.

At the approved `tickCount` hook, these values have been checked against the official
26.2 handler ordering:

| Value | Classification | Reason |
| --- | --- | --- |
| Current position and AABB | SAFE SNAPSHOT | Available from `ServerPlayer` before `resetPosition`. |
| Proposed X/Y/Z | SAFE SNAPSHOT | Packet getters are available before handler mutation. |
| `lastGoodX/Y/Z` | SAFE SNAPSHOT | Listener fields are read before the first mutation. |
| Awaiting teleport position/id | EXCLUDED PATH | Deferred until normal teleport/baseline convergence; no regional replay. |
| Passenger | EXCLUDED PATH | Ordinary player movement replay is not classified for passengers. |
| `noPhysics` | EXCLUDED PATH | Direct/no-physics movement has a distinct semantic footprint. |
| Sleeping | EXCLUDED PATH | Bed state is not ordinary continuous movement. |
| Dimension transition | EXCLUDED PATH | Requires relocation semantics outside Stage 3 scope. |
| `maxUpStep` | SAFE SNAPSHOT | Used to conservatively inflate the step/collision footprint. |

First bootstrap packets with pending teleport or a non-converged last-good baseline are
counted as `movementBootstrapDeferred`. They create no replay marker and no ownership
mutation. Once vanilla teleport acknowledgement/baseline processing has converged, a
fresh movement packet is classified normally; there is no connection drop, owner wait,
or permanent retry state.

## Future movement routes — prepared only

The planned disposable-player starting position is `(8.5, 80, 8.5)`, chunk `(0, 0)`,
inside A.

| Future route | Coordinates | Expected result |
| --- | --- | --- |
| Same owner | `(8.5,80,8.5)` to `(24.5,80,8.5)`, chunk `(0,0)` to `(1,0)` | Full padded footprint remains in A. Owner A→A; `K1→K2`; object epoch unchanged. |
| Boundary escape | `(72.5,80,8.5)` to `(82.5,80,8.5)`, chunk `(4,0)` toward `(5,0)` | Full padded footprint leaves A. Reject before replay; no foreign mutation or transfer. |

The client setup must use a fresh disposable world/player whose initial placement is
within the stated A domain. These routes are not being run by this task.

## Entity-ID candidate lifecycle

The target index is populated from vanilla lifecycle callbacks, not from a packet-time
world lookup:

| Vanilla lifecycle point | Derived action |
| --- | --- |
| `ServerLevel.addFreshEntity`, `addDuringTeleport`, or `addNewPlayer` after vanilla addressability | Publish network ID → ObjectId/incarnation candidate. |
| `Entity.setPosRaw` tail | Refresh same-owner routing candidate; cross-owner movement invalidates it rather than transferring ownership. |
| `PlayerList.remove` head or `Entity.setRemoved` head | Invalidate the network-ID candidate, then remove object ownership metadata. |
| Later numeric ID reuse | New addressability publishes a different candidate; an old reference fails `isCurrent` and incarnation validation. |

Packet ingress reads only this concurrent derived map, then validates the current object
registry, directory generation, owner, and frozen footprint. It does not call
`ServerLevel.getEntity`, `getEntityOrPart`, or any mutable world entity index. Vanilla
`getEntityOrPart` is reached only under a validated interaction replay marker.

## Future interaction routes — prepared only

Use a harmless vanilla `minecraft:armor_stand` in a disposable test world.

| Future case | Placement | Expected result |
| --- | --- | --- |
| Same-region interaction | Player `(8.5,80,8.5)`, armor stand `(10.5,80,8.5)`, both A | Valid index hit, A lease, one replay; observable vanilla result is the armor stand interaction’s normal hand/swap behavior. |
| Cross-region interaction | Player `(72.5,80,8.5)` in A, armor stand `(82.5,80,8.5)` in B | Fail closed before `getEntityOrPart` and `interactOn`; no B lease, wait, lock, or mutation. |

## Future real-client method

The preferred evidence method is a disposable local Minecraft 26.2 client/test account
using offline/local-server mode where allowed by the server configuration. It avoids any
Phoenium production dependency. Connect to the disposable Fabric server with explicit
fixture mode, perform the listed walking and armor-stand interaction actions, capture
`latest.log` plus `Phoenium.liveRuntime().packetDiagnostics()` counters before RCON
`stop`. If a matching local client is unavailable, use a protocol-compatible 26.2 bot
only after separately validating its protocol semantics; manual client control is the
fallback.

Expected exactly-once evidence uses counters rather than log wording:

```text
movement ingress = 1; footprint accepted = 1; owner execution = 1;
replay = 1; handler completion = 1; routing reconciliation = 1

interaction ingress = 1; index hit = 1; owner admission = 1;
owner execution = 1; replay = 1; vanilla lookup = 1; handler completion = 1
```

No per-tick packet logs are emitted. Cross-owner and stale cases are separately visible
through reject/defer/stale counters.
