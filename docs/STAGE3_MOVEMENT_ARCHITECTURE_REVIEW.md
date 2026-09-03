# Stage 3 movement architecture review

Review date: 2026-09-02 (America/Chicago). Baseline: `a45d852f916bfc806de70a7b9030d3083adfca56`,
on the partial implementation `bf9717de8afa6156de64032693dffcaf9c3be827`.
Stage 3 remains **PARTIAL**. This is an architecture decision, not live acceptance evidence.
Parallel gameplay remains off; Stage 4 and compatibility work are not started.

## Decision A — topology enclosure

**An admitted ordinary movement transaction executes completely under its existing region
owner and lease. `Entity.move()` never changes region owner mid-call.** Its source,
swept collision area, destination, callbacks, and continuing caller work must be enclosed
by that authority. Moving between chunk coordinates owned by the same RegionId updates
routing metadata; it is not an ownership transfer.

Ownership may change at a quiescent topology transition, or through an explicit relocation
protocol that prepares the source and destination. Neither a `setPos` interception nor
a packet's proposed destination is such a protocol.

This accepts the enclosure architecture with **mandatory escape prevention**, not the
stronger proposition that a fixed Folia-style buffer automatically contains every vanilla
or modded operation. That proposition is disproved below. A conservative neighborhood
and complete access/admission checks are both necessary. Unclassified escaping paths
must fail closed before foreign access; arbitrary modded movement is not proven supported.
The current Phoenium implementation does not yet implement these geographic guarantees.

Decision B is rejected: no complete transferable ordinary movement transaction with proven
equivalent caller semantics was found. The previously rejected seam before `setPos(Vec3)`
remains rejected. This review does not propose tail replay, rollback of arbitrary game
callbacks, a movement lock, cross-owner waits, or silent movement truncation.

## Evidence identity and reproducibility

The supplied Folia tree is the behavioral reference audited at
`14b7fee5c866fca9a40ede4a58998fb928140f65`; its `gradle.properties` pins Paper
`5d4f9bd0e4f6b1ef70d07feb411580cef7d1a746` and Minecraft 26.2. Review those exact
revisions rather than interpreting a moving branch as evidence. The existing local
`.audit/upstream/Folia` checkout reports that Folia commit. The supplied mirror's
equivalence is recorded in `SOURCE_PROVENANCE.md`.

The four supplied Folia base/passenger/acceptance patches used here were also compared
directly with the pinned checkout during this review. All four are text-identical after
LF/CRLF normalization; the supplied files use LF and the local Git checkout uses CRLF.

Minecraft evidence was regenerated with Java 25 `javap -p -c -s` against
`../.audit/downloads/minecraft-26.2-inner.jar`. Its SHA-256 is
`183c0499c5f855570ee487dd38e141a53f0121f83a0b07a3bac2d8b6698823e8`.
Local disassemblies are under `../.audit/evidence/stage3-architecture/`, named by fully
qualified class. They are inspection artifacts outside the repository, not redistributed
Minecraft implementation. Bytecode offsets below are method-relative, not source lines.

Useful reference abbreviations in this review:

| ID | Pinned reference and exact locations |
| --- | --- |
| F-MC | [Folia Minecraft base patch](https://github.com/PaperMC/Folia/blob/14b7fee5c866fca9a40ede4a58998fb928140f65/folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch): `ThreadedRegionizer` added-file hunk, patch line 3756 onward; `RegionizedWorldData`, line 2873 onward; ServerLevel construction, 11340–11347; entity insertion/removal, 161–199. |
| F-THREAD | [Folia Paper base patch](https://github.com/PaperMC/Folia/blob/14b7fee5c866fca9a40ede4a58998fb928140f65/folia-server/paper-patches/features/0001-Region-Threading-Base.patch): `TickThread`, lines 7–222; `gridExponent = 4`, line 437. |
| P-ENTITY | [Pinned Paper Entity patch](https://github.com/PaperMC/Paper/blob/5d4f9bd0e4f6b1ef70d07feb411580cef7d1a746/paper-server/patches/sources/net/minecraft/world/entity/Entity.java.patch): ordinary `move`, lines 509 onward; `setPosRaw`, 1786 onward. |
| P-LOOKUP | [Pinned Paper Moonrise patch](https://github.com/PaperMC/Paper/blob/5d4f9bd0e4f6b1ef70d07feb411580cef7d1a746/paper-server/patches/features/0001-Moonrise-optimisation-patches.patch): `EntityLookup.moveEntity`, 4201–4248; `Callback.onMove`, 4650–4661; `ServerEntityLookup.checkThread`, 4997–5004. This is Folia's pinned dependency, not an unrelated compatibility-mod audit. |
| F-PASSENGER | [Folia patch 0009](https://github.com/PaperMC/Folia/blob/14b7fee5c866fca9a40ede4a58998fb928140f65/folia-server/minecraft-patches/features/0009-Teleport-desynced-passengers-to-root-vehicle.patch): `MinecraftServer` entity-scheduler tail and `Entity.fixPassengerDesync`; July 17, 2026 fix. |
| F-ACCEPT | [Folia patch 0010](https://github.com/PaperMC/Folia/blob/14b7fee5c866fca9a40ede4a58998fb928140f65/folia-server/minecraft-patches/features/0010-Do-not-allow-out-of-region-teleport-accept.patch): `ServerGamePacketListenerImpl` before `absSnapTo`; July 17, 2026 fix. |

The official [region logic documentation](https://docs.papermc.io/folia/reference/region-logic/)
was also checked. It describes unique holder ownership, nearby ownership, frozen ticking
regions, transient neighbors, and merges at release. Region sections are an implementation
granularity. Those are architectural constraints, not a universal movement-distance proof;
the pinned implementation below is the detailed evidence.

## What Minecraft actually touches

The exact 26.2 `Entity.move(MoverType, Vec3)` disassembly reconfirms the escalation:
`maybeBackOffFromEdge` at 143; `collide` at 149; `addMovementThisTick` at 304;
`setPos(Vec3)` at 310; fall handling at 546; movement emission at 648; subsequent
velocity/state work. The no-physics branch calls `setPos(DDD)` at 35 and continues
updating collision flags. Neither branch carries a region-transfer boundary.

More importantly, checking just the final chunk would already be too late:

| Work | Required authority and exact evidence |
| --- | --- |
| Swept bounding box | `Entity.collide(Vec3)` calls `AABB.expandTowards(delta)` at 12 and `Level.getEntityCollisions` at 15, before resolving collisions. An entity stopped by a nearby wall can still cause a much larger query. Include the entity index partitions and candidate entities inspected by the query, not just occupied blocks. |
| Block collision and step-up | `collideBoundingBox(CollisionContext,...)` collects from the expanded box at 6–9. `collide` uses `maxUpStep`, the vertically adjusted box, and additional collider collection at 179–224. `BlockCollisions` constructs its cursor from epsilon-adjusted bounds plus one block at each end, then gets collision chunks and block states. The broad phase, step candidates, and query padding all belong to the footprint. |
| Supporting block and falling | `checkSupportingBlock` searches both a thin box under the entity and an offset candidate. `checkFallDamage` invokes `Block.fallOn` at 56, which can call entity/block behavior. Cached supporting positions must still be owned, or explicitly reset during relocation. |
| Position and indices | `setPosRaw(DDD)` stores position at 49, changes the cached chunk position at 167, then calls `EntityInLevelCallback.onMove` at 174. `PersistentEntitySectionManager.Callback.onMove` removes the old section entry, creates/gets the new section and adds it, then updates tracking/ticking status. This is synchronous index mutation, not a passive coordinate assignment. Waypoint callbacks also follow in `setPosRaw`. |
| Fluids and traversed-block effects | `Entity.baseTick` invokes `updateFluidInteraction`. `EntityFluidInteraction.update` uses `getFluidInteractionBox`, floors/ceils its bounds, checks a one-block-expanded horizontal neighborhood via `hasFluidAndLoaded` at 113, reads fluid at 217 and flow at 369. Its readiness scan requests FULL chunks with creation disabled and examines their sections. `applyEffectsFromBlocks` consumes recorded movements, including effects after `move` returns. Include these boxes, neighbor queries, traversed positions, caches, damage and callbacks across the enclosing tick. A proof covering only the text of `move` is insufficient. |
| Emission and neighbor effects | `applyMovementEmissionAndPlaySound` invokes sound/event behavior; falling, block effects and callbacks may reach listeners, neighbors and other entities. Their synchronous footprints must be closed too. No arbitrary recursive callback radius is assumed. Later T-18 work must classify such effects; this review does not implement redstone or event routing. |
| Lookup/loading | `Level.getBlockState` and `getFluidState` obtain chunk state; `getChunkForCollisions` requests a FULL-status chunk with creation disabled. `ServerChunkCache.getChunk` has a server-thread `managedBlock` followed by `join` (153, 158 in the existing disassembly). Owning a chunk coordinate does not prove its data is ready or make this wait legal under T-05. |
| Projectiles | `projectile.arrow.AbstractArrow.tick` calls `clipIncludingBorder` at 521, `stepMoveAndHit` at 529 or `setPos(Vec3)` at 542, then block effects. `stepMoveAndHit` includes position/effects/hit handling. Continuous swept movement is not synonymous with calling `Entity.move`. |
| Vehicles and passengers | The complete root/passenger graph, its bounds, passenger positioning, collisions and effects must be local through the synchronous operation. A split must preserve a graph in one owner even if its members occupy several chunks. A passenger found outside that contract cannot be silently detached or independently transferred. |

Immutable block-state values do not turn access to their mutable chunk/section containers
into a safe foreign read. Read-only *intent* is not an ownership exemption under T-01.

## MutationClosure and the admission property

For world W, directory generation g, and lease L of region R, define `D(R,L)` as a frozen
set of owned chunk coordinates plus the declared entity/index/service partitions that
R may access under L. Empty buffer coordinates reserve authority; they are **not fake
chunks** and do not imply loaded data. A foreign TRANSIENT region is not part of D.

Define `Footprint(op)` as every regional mutable container read or written by op,
including synchronous descendants, broad-phase queries, callbacks, cached historical
positions, and the relevant continuing caller. Define `MutationClosure(R,L)` as a
conservative closure containing the union of those footprints for all operations admitted
during L, closed under their synchronous effects. The spatial projection is represented
by chunk-coordinate ownership. Entity and shared-service ownership are checked separately.

Required property:

```text
for every operation admitted during L:
    Footprint(op) is contained in MutationClosure(R,L)
    MutationClosure(R,L) is contained in D(R,L)
    all required mutable data has owner R for all of L
    required synchronous chunk data is already available at its required status
```

R's entity fields, including position, remain R-owned throughout this operation. Because
the new position is still in D, reconciling its routing key does not contradict location
ownership. A separate owner ID cannot be assigned merely because the coordinate changed.

The closure is a semantic bound, not a promise to compute every possible mod callback
statically. The implementation needs both conservative preflight for known operations and
mandatory checks **before** mutable chunk, entity-index, event/service and position
application access. Unknown behavior cannot become an unchecked exception to this rule.
Position checks belong before position is stored, not in the later section callback.

For a known operation, preflight may reject/defer the *entire* operation before side
effects while topology converges. If a dynamically discovered escape occurs after local
side effects, there is no general safe defer-and-replay: trip the hard failure barrier
before foreign access and do not continue or pretend rollback. Graceful retry needs an
operation-specific proven restart boundary. This preserves semantics for admitted paths;
unsupported paths explicitly fail instead of silently returning a modified vanilla result.

All mutable execution categories use this property, including packet and chunk-application
tasks. A task-only lease cannot bypass the topology conditions required of a tick lease.

## Units, radius, and adversarial movement

The semantic unit is a footprint in block/world coordinates plus object and service
identities. The authoritative spatial unit can remain Phoenium's chunk `RoutingKey`.
Region sections are optional batching of chunk keys; they are not a new owner or a
source of authority. Convert conservative bounds by mathematical floor, including
negative coordinates, vertical extents, border padding and overflow/nonfinite checks.

No universal finite *local* buffer radius has been proven for all Minecraft/Fabric
operations. In `Entity.setDeltaMovement(Vec3)` bytecode 0–9 the only test is `isFinite`;
there is no magnitude clamp. `move` also accepts a supplied Vec3 directly. A finite
vector large enough to leave any chosen local enclosure is a counterexample; collision
queries can leave it even if the entity never reaches that destination. A zero-motion
huge bounding box or large overridden step height is a further counterexample.

Folia's pinned configuration has `gridExponent = 4` (16 by 16 chunks per section).
ServerLevel passes empty creation radius `max(1, 8 / (1 << shift))` and section merge
radius 1. At that default, a nonempty section gets one section of empty neighborhood:
a 3 by 3 section area before unions with other activity. Addition searches creation
plus merge radius (2 sections at the default). This is not a Phoenium speed limit, and
distance from an arbitrary entity to an actual owned edge depends on its position and
the entire union of sections. Copying "8 chunks" or "one section" would not prove closure.

For Phoenium, choose a performance buffer only after measuring admitted operation
footprints. Given active holders/entities H and an implementation buffer b, verify that
their dilated footprint and all required partitions belong to R before lease acquisition.
If two candidate neighborhoods intersect or have required adjacency, they must converge
before independent admission. A planner based on centers needs to account for *both*
buffers and query reach; a single center-distance threshold is not automatically enough.
Do not recursively dilate every empty buffer cell forever: active support seeds and
semantic reach define the closure, while empty cells reserve that computed domain.

| Case | Classification and handling |
| --- | --- |
| Ordinary `Entity.move`, falling, knockback, elytra | Continuous when part of a synchronous simulation transaction. Execute wholly under the admitted owner if its footprint fits. Source velocity updates do not themselves transfer ownership. Future movement still needs validation. |
| Extreme velocity / mod speed increase | Still continuous if the caller requests collision-resolved travel. Never reinterpret it as a teleport, clamp it, or assume it is safe. Preflight convergence where restartable; otherwise hard barrier before foreign access. |
| Explosion | Velocity impulse and subsequent movement are distinct operations; the explosion's damage/block/query footprint needs its own enclosure. An arbitrary explosion radius is not covered by the movement buffer. No explosion implementation is added here. |
| Projectile / piston / vehicle movement | Continuous with path-specific footprint. Piston's `applyPistonMovementRestriction` clamps its accumulated selected-axis contribution to ±0.51, but that is a piston-specific fact, not a bound on collision/effects or other movement. Projectile hit callbacks and the complete passenger graph remain included. |
| Player movement packet | A proposal processed under the player's current authority. Vanilla checks, collision resolution, rejected movement, correction and tracking belong to the same admitted operation. A proposed destination is not an already-committed location. |
| `setPos` / direct position API | Classify by caller transaction. It may be a construction operation on a non-live object, a step inside continuous movement, passenger alignment, or relocation. The method name does not authorize any of those. Unknown live caller is a development barrier until classified. |
| Absolute snap / command teleport / mod relocation | Discontinuous relocation if replacing position without the continuous collision transaction. Local relocation can finish under the same owner after validating its full effects; a foreign destination requires the explicit transfer protocol. |
| Portal / dimension change | Explicit source preparation, destination reservation, commit, application and completion/rollback. Source and destination worlds, chunk readiness, cached positions and passengers are part of the protocol. |
| Respawn | Lifecycle/connection and incarnation transition, not ordinary motion. It invalidates the prior live-object incarnation even if coordinates or RegionId happen to match. |

This classification is intentionally semantic. There is no universal auto-conversion from
an escaping continuous operation into a discontinuous teleport.

## Folia comparison: what the code proves

In the locally extracted F-MC `ThreadedRegionizer.java`, `addChunk` lines 276–440 creates
the empty-neighborhood sections and gathers nearby owners. New sections go only to a
non-ticking owner. Ticking participants are connected by `mergeIntoLater`, and unresolved
non-ticking owners become TRANSIENT. `tryMarkTicking` lines 959–974 checks READY and the
absence of pending merge relationships under the same topology lock used by creation.
`markNotTicking` lines 977–995 invokes `onRegionRelease`; lines 499–570 process pending
merges, propagate outstanding dependencies, and only then consider pruning/splitting.
Those extracted line numbers map to the added-file hunk in F-MC (patch offset +3755).

Entity association has two levels. P-LOOKUP's `Callback.onMove` updates spatial section
membership immediately. Its `moveEntity` checks entity authority and both new and old
chunk authority *before changing the slices*. It does not hand an ordinary entity to an
independently ticking region. F-THREAD's `isTickThreadFor(Entity)` additionally recognizes
membership in the current `RegionizedWorldData.allEntities`; coordinate lookup alone is
not the entire entity-authority test.

F-MC wires `ServerEntityLookup.addEntityCallback` into current world data, and removes the
entity from that data only after removal callbacks. In extracted `RegionizedWorldData`,
`addEntity`/`removeEntity` are lines 622–649. Its merge callback (77–105) combines entity
collections and re-registers entity schedulers. Its split callback (160–204) assigns
entities using their **current** chunk section and moves the relevant lists/schedulers.
Player regional association is updated at insertion/removal and merge/split. A simple
intra-owner spatial section change does not move the entity between those region lists.

F-MC `Entity.teleportAsync`, patch lines 15022–15109, explicitly distinguishes a local
same-world shortcut with owned radius/readiness checks from async passenger-tree transfer.
`teleportSyncSameRegion` and `resetStoredPositions` at 14939–14962 clear supporting/movement
caches. Portals use separate async handling at 15125 onward. Pinned vanilla/Paper move
does not contain a mid-call owner-transfer protocol.

F-PASSENGER checks passenger spread, reconnects a ticket corridor, and invokes async
root-vehicle relocation when desynchronized. F-ACCEPT checks ownership around an awaited
teleport position before accepting it. Phoenium must retain their conceptual lessons:
group completeness, desync detection, cached-position reset, and current-owner/transfer
validation on teleport acceptance. It need not adopt Bukkit, Paper's complete chunk system,
entity scheduler implementation, exact radii, watchdog position locks, shutdown exceptions,
or Folia's callback-under-regionizer-lock mechanism.

Folia also has path-specific guards and altered behavior: F-MC lines 14576–14582 check a
block-effects location with a radius and can stop that path. Therefore the evidence is
**topology plus enforcement**, not proof that all vanilla tails work for arbitrary motion
without checks. Phoenium must not import a skip or missing-state substitution as semantics.
No upstream implementation code is copied or adapted by this review.

## The adjacency race and two-worker proof

Let A and B already hold leases on frozen disjoint domains `D_A` and `D_B`. Admission
has verified each active transaction's conservative closure, including access checks,
inside its own domain. Assume A is the first operation to try to access B-owned mutable
state. That access is outside `D_A`; the mandatory check cannot authorize it. Thus no
first foreign access exists in a successful execution. This proof does not use worker
count, server-thread identity, movement speed, or a movement-method lock.

Creation during the leases does not alter either frozen domain. A-side activity may
activate a holder in an already-owned empty coordinate. If its future required neighborhood
would approach B, the coordinator records pending convergence and closes future ordinary
admission on the affected participants. Newly created outside-domain topology is TRANSIENT
or pending metadata and cannot host ordinary application work. Pending merge is not
permission for A to use its live state. Background detached computation may finish and wait
in an envelope. Installation is governed separately by the chunk review.

If B has not started, topology dependency publication and lease acquisition must linearize
against the same metadata admission gate: either B acquired its old safe domain first,
or it observes the pending merge and cannot start. If both were already ticking, their
old disjoint domains remain unchanged until both drain. A may not continue an escaping
operation merely because a merge has been requested. If A releases first while B still
ticks, A's affected successor remains ineligible; it does not resume with an overlapping
expanded closure. Only the completed convergence can authorize further work.

This qualifies the phrase "independently ticking neighbors are forbidden": already
running regions can acquire a *future* merge dependency while retaining disjoint old
domains. What is forbidden is simultaneous authorization of overlapping mutable closures.
No synchronous worker wait is needed. Pending ownerless results must not be turned into
live holders; every published live holder still needs its unique T-07 owner.

## Publication ordering, merges, splits and object epochs

The proposed model is consistent with T-01 and T-07–T-14 with this ordering:

1. Under the short metadata gate, identify every affected owner, close ordinary admission,
   record the transition identity and preserve active lease domains. Admission closure and
   lease acquisition must be atomic with respect to each other. Envelopes are mailboxed.
2. Drain all affected leases by scheduler continuation; never wait from a region operation.
   Reconcile actual positions before releasing their source authority. Capture each affected
   object's old declared owner, incarnation and current routing key; freeze complete groups.
3. While destinations are not ordinarily admissible, prepare destination runtimes and
   declared state migration with source preparation and destination reservations. Game-data
   preparation/application uses explicit transition authority outside the metadata gate.
4. Commit one next directory generation and its closed destination routes. Derive object
   transitions from old owner to owner of the **current** key in that proposed snapshot.
   Stage the resulting object ledger and transition records as the same logical publication.
   A reader seeing an intermediate combination must defer/reject, never execute.
5. Complete destination application under transition leases, validate complete holder,
   entity, passenger and service indices, then publish the transition completion and reopen
   ordinary admission with release/acquire ordering. The directory may become visible before
   application completes, as Stage 0 permits, only while the destinations remain closed.
6. Re-resolve mailboxes **and already-queued inbox work** against the new directory and
   object epochs immediately before execution. Location tasks use their location; object
   tasks resolve the current object identity/key. Do not reroute an object task by its old
   chunk key alone after a split. Publish READY only for complete independent closures.

Generation, RegionId, region owner epoch, object epoch and lease epoch remain separate:

| Event | Routing key | Object epoch |
| --- | --- | --- |
| Continuous move within R | Update to actual accepted position, with R fixed | Unchanged |
| Unrelated directory generation / same surviving R | Refresh directory/region fencing metadata | Unchanged solely for this reason |
| Topology assigns current key A → B | May stay identical | Increment once at that committed ownership transition |
| Merge into surviving A | Reconcile actual keys | A's resident objects keep epoch; objects arriving from B increment once |
| Split of A | Reconcile current keys and group locality | Objects retained by A keep epoch; each moved to a distinct successor increments once |
| Removal / new incarnation | Stop ordinary admission; tombstone old incarnation | Invalidate on removal; never reuse a token on respawn/re-admission |
| Explicit relocation | Follow transfer record | Increment once for committed owner change; local protocol may use a separate relocation/connection token to reject stale position intent |

Do not reuse RegionId for an unrelated runtime incarnation without explicit lifecycle
invalidation. Region-owner fencing changes still invalidate stale *region* envelopes;
they do not by themselves mean every resident object moved. Position-sensitive tasks
need a separate expected-position/operation precondition, not abuse of owner epoch.

T-13/T-14 still apply to topology-induced migrations: their source preparation, destination
admission, commit, application and completion may be a group record in the topology
transition, rather than individual asynchronous teleports. A split cannot scatter a live
vehicle graph; retain its connecting domain or postpone the split. Preserve connections
and required service partitions before opening the successor.

## Current implementation gaps and narrow correction plan

This is not evidence that the current one-worker test model satisfies future multi-worker
publication. Inspection found these concrete gaps:

| Location | Finding and required correction |
| --- | --- |
| `ObjectOwnershipRegistry.move` | Every call increments object epoch and fabricates immediately hidden SOURCE_PREPARED/DESTINATION_RESERVED states, even for two chunks in one owner. Introduce a same-owner routing update that requires the existing owner and preserves epoch; it must reject an attempted ordinary cross-owner update. Keep actual migration behind a transition record. |
| `ObjectOwnership` / `current` | The record lacks the previously declared RegionId. `current` increments object epoch on any generation or region-epoch change. Store the bound owner identity and apply committed transitions eagerly at topology publication. Comparing the old and new keys in the *new* directory loses the old owner. Lazy comparison alone also misses A → B → A if no read occurs between publications. |
| `PhoeniumLiveRuntime.admitMovePacket` | Publishes the requested chunk before replay. `handleMovePlayer` bytecode calls collision-resolved `ServerPlayer.move` at 689, and has correction/absolute-position behavior as well. The proposal is not the accepted result. Route the complete handler to the current player owner, prove/preflight its whole closure, then record actual accepted position under that same lease. This supersedes the older packet document's transfer claim. |
| Post-level reconciliation | Discovery remains useful for serial bookkeeping. It cannot repair a foreign mutation already performed or serve as geographic movement permission. A new same-region update API would be its correct normal path. |
| `TopologyPlanner.bufferClosure` | It iterates both keys from `component`; its final `component.contains(other)` is necessarily true. It proves no empty neighborhood, separation from another owner, or loaded-data readiness. `mergePlan` is a connected-component model of input holders, not a complete regionizer. Future validation needs active seeds, owned/reserved domains, all neighboring owners, and negative tests. |
| `TopologyCoordinator.advance` / `RegionRuntime` | Publishes the directory, calls `finishTransition` (which reopens ordinary work), and releases mailboxes under the metadata gate. There is no live state/object migration completion fence. Serial tests do not prove an atomic admission/lease race or future data visibility. Implement the ordering above before geographic execution. |
| `RegionScheduler.runOne` | Polls existing inbox work and applies its payload without generic execution-time route/generation validation. Object execution has an additional epoch/owner check; generic payloads do not. An envelope queued before a transition can remain in the old inbox. Submission-time resolution and transition mailboxes alone do not prove M-05. |
| Object lifecycle | Removed IDs remain in the entries map and `admit` rejects duplicates. A future respawn/reinsertion policy needs explicit incarnation handling; do not solve it by deleting epoch history. |

**Runtime changes in this review: NONE.** The routing/epoch distinction is proven, but a
standalone lazy RegionId comparison would not implement exactly-once publication or close
the packet pre-transfer problem. The review therefore specifies the cohesive next change
instead of presenting a partial ledger fix as the ownership boundary. No current accepted
Stage 1/2 test is weakened or renamed to conceal these limits.

The smallest next implementation is the metadata transition contract: separate actual
position bookkeeping from ownership commit, add recorded owner/incarnation identity and
eager object reconciliation at a closed topology boundary, and revalidate old inbox work.
Test that contract deterministically before choosing any live geographic movement hook.
Complete access/closure admission and chunk readiness remain further Stage 3 prerequisites.

Four deterministic inspection witnesses were also executed against the unchanged built
core. They confirm existing defects, not acceptance of the proposed design:

| Witness | Observed current behavior |
| --- | --- |
| Two adjacent keys both owned by A; move the entity from one to the other | Object epoch 0 -> 1 despite unchanged RegionId. |
| Republish the same owners with a new topology generation and unchanged region owner epoch | Object epoch 1 -> 2 on `current`, despite no owner or lifecycle change. |
| One-key component, radius 1, all eight neighboring keys absent | `bufferClosure` returns true. |
| Enqueue a generic location task in A; commit its key to B; advance scheduler | One mutation in A, zero in B. Submission occurred before the transition, so the transition mailbox did not capture it. |

The independently written inspection harness and output are outside the repository at
`../.audit/evidence/stage3-architecture/ArchitectureWitnesses.java` and
`core-witness-results.txt`. Reproduce after building from the Phoenium root with
`java --class-path build/classes/java/main ../.audit/evidence/stage3-architecture/ArchitectureWitnesses.java`.
These are deliberately baseline counterexamples; they must change when the defects are
fixed. They do not implement or pass future movement M-01–M-06 and are not live evidence.

## Deterministic future acceptance cases

These are test designs, **not tests run by this review and not live PASS evidence**.
Use explicit scheduler steps, manually acquired leases, controlled completion queues and
barriers/latches if exercising two test threads. Never use elapsed time to establish ordering.

| ID | Arrangement and explicit schedule | Required observation |
| --- | --- | --- |
| M-01 | Publish two neighboring keys under A; admit an entity at the first, queue an identity-bound task, move under A to the second, then execute the task. Include negative coordinates. | Actual key changes; RegionId/object epoch stay fixed; no transfer record; queued identity work remains valid. |
| M-02 | Freeze A's domain and place the swept-box/query padding at its edge. Arrange a requested sweep beyond it whose resolved endpoint would be inside. | Guard fires before the first foreign lookup/position store; B's read/write counters remain zero. Destination-only guard must fail this test. |
| M-03 | Acquire A and B's old leases. Publish a bridging holder/result request; try to admit B in the alternative ordering before and after dependency publication. Release A first, then B. | Old domains never expand; bridge cannot apply as ordinary work; no overlapping successor leases; first releaser cannot resume before convergence completes. |
| M-04 | Move within A; close transition admission; attempt publication while A lease is active; release and commit A → B. Read ledger repeatedly and publish an unrelated generation. Also commit A → B → A with no intervening object read. | Commit waits by continuation; exactly one epoch increment per actual owner change, none for reads/key moves/unrelated publication; no ABA reuse. Test split survivors and merged arrivals separately. |
| M-05 | Create one stale task before commit and deliver it after; also enqueue another into A's inbox before commit, then change the route. Exercise object and generic location payloads, REJECT and REROUTE. | Current owner receives rerouted work or it is rejected; old owner never mutates. An object task follows current identity even when its old chunk remains in A. |
| M-06 | Parameterize huge finite delta, noPhysics, oversized box, step height, projectile path, repeated moves in one tick and a callback reaching outside D. | Classified preflight defer only before effects, or hard failure before foreign access. Never silent clamp, fake state, foreign read, partial-tail replay or speed-limit assumption. |

Here M-01–M-06 are the movement cases requested for this review; qualify them as
`Stage3 movement M-xx` to distinguish the pre-existing compatibility M-xx identifiers
in `TEST_MATRIX.md`.

Additional cases needed for these proofs: incomplete passenger graph split rejection,
cached supporting-block reset on teleport, stale teleport acceptance, rejected player packet
leaving the ledger at the actual location, same-owner metadata generation change, removal/
respawn epoch non-reuse, and missing-but-owned collision data that would otherwise block.

## Stage and architecture disposition

No Stage 0 architecture is reversed. Frozen ticking ownership, transient neighbors,
buffer closure and boundary merges were already locked in `ARCHITECTURE.md` and T-08.
This review makes their admission property precise and corrects Stage 3's interpretation
that changing chunk coordinates necessarily transfers an entity. It tightens the proof
obligations for modded behavior and publication; it does not claim them implemented.

The ordinary movement design choice is resolved. Geographic closure enforcement, coherent
object publication, packet source admission and nonblocking chunk readiness remain open
implementation blockers. Real packet invocation, player crossing, non-player crossing,
chunk completion and stale completion evidence remain mandatory. A real ordinary crossing
test must represent same-owner spatial movement followed, if applicable, by an actual
quiescent topology owner change; it must not recreate an invalid adjacent A/B split just
to manufacture a crossing. No Stage 3 PASS tag may be created from this review.
