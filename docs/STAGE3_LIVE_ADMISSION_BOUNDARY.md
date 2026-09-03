# Stage 3 scoped serial movement / packet admission boundary

Status: **design and deterministic contract only**. This document does not record a
client run, a live geographic movement PASS, a live chunk-installation boundary, or a
Stage 3 completion. One simulation worker remains in use and parallel gameplay remains
off.

## Accepted scope

The only movement candidate accepted for a future live adapter is a valid, ordinary,
non-passenger `ServerboundMovePlayerPacket` whose complete conservative footprint can
be represented before vanilla mutation. The packet's requested coordinate is an input to
the conservative bound; it is not a routing decision and does not transfer player
ownership. The player stays owned by its current region for the complete vanilla handler.
Only an eventual quiescent topology publication may perform an A -> B owner transition.

The model is implemented in `LiveMovementFootprint`, `LiveMovementAdmission`, and
`LivePacketReplayGate`. It has no Minecraft references so deterministic acceptance tests
can prove the ownership ordering without a client or a mutable Minecraft world.

## Exact Minecraft 26.2 paths inspected

The local official-named 26.2 disassembly is
`../.audit/evidence/stage3-architecture/net.minecraft.server.network.ServerGamePacketListenerImpl.txt`.

`ServerGamePacketListenerImpl.handleMovePlayer(ServerboundMovePlayerPacket)` does the
following, with method-relative bytecode offsets:

```text
0..9       PacketUtils.ensureRunningOnSameThread
12..40     packet invalid-value test; invalid input disconnects at 43..53
54..72     level / won-game reads; no movement mutation
74          first tickCount read
81          resetPosition (first mutation after the valid-input path begins)
141..187   derive the horizontally/vertically clamped requested coordinates
657        jumpFromGround when applicable
689        ServerPlayer.move(MoverType.PLAYER, delta from lastGood position)
913..974   correction teleport / fall-damage path when collision validation rejects
975        accepted-position snap and later tracking, fall-damage, and chunk-source work
```

The narrowest valid-input pre-side-effect insertion point is therefore **before the
`tickCount` field read at offset 74**, after `ensureRunningOnSameThread` and the invalid
packet branch, but before `resetPosition`, jumping, `ServerPlayer.move`, any correction
teleport, chunk-source movement, or fall-damage work. The current after-`ensure` Mixin is
not evidence of this new admission contract; a later implementation must retarget the
valid-input hook to this exact point and retain a required-match count.

At offset 689, the handler invokes `ServerPlayer.move`. In `Entity.move`, the inspected
path calls `maybeBackOffFromEdge` at 143, collision at 149, records movement at 304,
stores position at 310, then continues through collision flags, supporting-block access,
fall handling, and subsequent state. This rules out an owner handoff inside `move`.

The already-hooked `handleInteract(ServerboundInteractPacket)` is **not** admitted by this
movement contract. After its server-thread handoff at offset 9, it reads the target through
`ServerLevel.getEntityOrPart` at offset 33, before `resetLastActionTime` at 41 and before
`ServerPlayer.interactOn` at 148. A raw packet entity ID cannot safely resolve target
ownership or target geographic/index footprint. In explicit regional topology this path
must fail closed before the target lookup until a separately typed, owner-safe target
resolution protocol exists. Its current correlation-scoped replay mechanism remains a
serial/default-mode mechanism, not a proof of regional interaction admission.

## Live footprint representation

`LiveMovementFootprint` keeps the `routingDestination` separate from the required
geographic components:

| Component | Covered inspected work |
| --- | --- |
| `sweptCollision` | The player AABB swept by the proposed delta, including collision collection. |
| `stepUpCandidates` | The same conservative horizontal collision envelope plus step-up candidates. |
| `supportingBlockAccesses` | Source/destination support and fall-related block queries. |
| `fluidAccesses` | The movement envelope with the horizontal fluid-query padding identified by the entity path. |
| `entityIndexAccesses` | Entity-collision and `setPos`/section-index source-destination coverage. |
| `synchronousCallbackAccesses` | Explicit source/destination callback/index locations known to this path. |

The future Minecraft adapter must construct these from the player's current AABB, a
server-thread snapshot of `lastGoodX/Y/Z`, and the valid packet proposal. It converts
conservative block/AABB bounds to inclusive chunk routing boxes with mathematical floor,
one-block cursor/query padding, finite-value and integer-range checks. The proposal must
over-approximate the clamped movement result; it must never be used as a target-owner
lookup. If `lastGood` differs from the current position, an awaiting teleport is active,
or the path is passenger/no-physics/sleeping/dimension-changing or otherwise unclassified,
the scoped adapter must not replay the ordinary movement handler.

The model calls `FrozenRegionAuthority.requireWithinBox` for every box and
`requireWithin` for named callback/index locations. Thus a finite huge delta fails before
box enumeration when its area already exceeds the frozen domain. No clamping, sampling,
foreign lookup, position store, or mid-lease domain expansion is permitted by that path.

## Admission and replay contract

```text
valid real move packet, already on server thread
    -> resolve CURRENT player binding
    -> construct complete classified footprint from current state + proposal
    -> freeze the current explicit RegionDirectory authority
    -> require all footprint components inside that authority
    -> queue typed owner envelope with an admission ticket
    -> immediately before replay: require same owner, same topology generation,
       and full footprint containment again
    -> enter one correlation-scoped replay marker
    -> invoke vanilla handler exactly once
    -> reconcile actual accepted routing position under the same owner
```

`LiveMovementAdmission.Ticket` is generation fenced. A topology publication after
calculation invalidates the ticket even if the new directory happens to have the same
shape. `LivePacketReplayGate` creates a replay marker only after this execution-time
validation and consumes the ticket once. The marker cannot be a global or recursive
bypass of the owner or footprint checks.

For a footprint escape, this Stage 3 serial design chooses **fail closed before vanilla
mutation**. It records no destination ownership and causes no foreign-region mutation.
A later graceful path may defer before effects, record convergence, and attempt a fresh
admission only after a new quiescent topology boundary. It must not resume an old packet
handler tail or replay after partial execution.

## Eventual two-region serial fixture

An eventual live fixture publishes explicit routes only:

```text
Region A authority: explicit A keys
Region B authority: explicit B keys
```

The route sets are disjoint. `FrozenRegionAuthority` is frozen from those explicit keys;
world defaults do not supply missing explicit keys. When required closures overlap, the
existing `TopologyCoordinator.recordConvergence` closes ordinary admission for both
participants, preserves their active frozen domains, and publishes a converged topology
only after leases drain. It never grants overlapping independent authority or expands an
active lease.

## Deterministic evidence

`LiveMovementAdmissionTest` proves, without a client:

- a fully contained classified footprint admits before the simulated vanilla mutation;
- a chunk-coordinate crossing within A admits and keeps the object epoch unchanged;
- a collision footprint escaping A fails before either local or foreign simulated mutation;
- an A/B closure conflict records pending convergence while the old explicit routes stay
  unchanged and both regions are closed;
- a directory publication between calculation and replay rejects the stale ticket; and
- the replay marker requires a current ticket, forbids nesting, and consumes a ticket
  exactly once.

Existing deterministic tests retain explicit-topology no-default-fallback, frozen-domain
escape, topology publication, stale inbox, and exactly-once ownership coverage. The
chunk observer remains **DIAGNOSTIC ONLY** and no chunk-installation work changes here.

## Remaining gate

Before any real client movement test, implement the documented valid-input Mixin point
and the narrowly scoped Minecraft AABB/state snapshot adapter, then verify Mixin matching
and a no-client serial server boot. A separate review must decide the target-resolution
protocol for interaction packets. Until those checks and a controlled client run exist,
Stage 3 remains **PARTIAL**.
