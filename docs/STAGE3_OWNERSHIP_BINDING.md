# Stage 3 ownership binding

## Scope

Stage 3 retains one simulation lane and keeps parallel gameplay off. The accepted Stage 2 redirect remains the only live level-tick interception. It does not turn an outer level lease into object authority.

`ObjectOwnershipRegistry` is a narrow, derived ledger. It stores an entity or player UUID, kind, current position-derived `RoutingKey`, directory topology generation, region epoch, and a separate object epoch. `RegionDirectory` remains authoritative for location ownership: the ledger re-resolves it before use and cannot independently grant mutation authority.

During each controlled live level tick, the runtime reconciles Minecraft's actual entity collection while the Stage 2 lease is active. New `Entity` and `ServerPlayer` instances are admitted, coordinate changes create a SOURCE_PREPARED -> DESTINATION_RESERVED -> OWNED transition in the ledger, and disappeared entries are removed. Moving or removing an object increments its object epoch, invalidating previously queued work.

The temporary Stage 2 whole-world region is published explicitly as a directory world-default route. Real entity positions are still recorded as chunk routing keys; later geographical topology can replace the default with concrete routes without creating a second registry.

## Mutation rule

An owned operation must enter as `OwnedObjectEnvelope`, resolve the *current* object binding, then become a normal typed `RegionTaskEnvelope`. At execution, `ObjectOwnershipRegistry` checks all of: object state, object epoch, current directory route, executing region, and active ownership context. Thus a lease for region A cannot mutate an object currently owned by region B, even if the call is nested inside a controlled outer `ServerLevel.tick`.

The generic transfer record/passenger graph contract from Stage 1 remains the required protocol for future real passenger-group adoption; Stage 3's object ledger never permits independent partial-group commit.

## Discovery versus transfer boundary

Reconciliation after `ServerLevel.tick` is intentionally a serial discovery/backstop mechanism for vanilla non-player entity insertion, removal, and position bookkeeping. It is **not** the future parallel entity-transfer boundary: vanilla entity simulation can already have performed movement by the time the enclosing tick returns. Before multiple workers or independently owned entity ticking, an earlier entity movement/teleport transition hook must reserve the destination and route the mutation under the transfer record before Minecraft changes region-sensitive state.

Player movement is narrower: its admitted packet exposes a destination position. Stage 3 therefore establishes the player ownership transition from the packet target before replaying the real vanilla handler. Non-player post-tick movement must not be treated as equivalent proof.

## 2026-09-02 ordinary non-player movement escalation

Stage 3 inspected the shipped official-named Minecraft 26.2 bytecode, rather than selecting a
hook by method name. The representative ordinary movement path is:

```text
living/entity tick caller
  -> Entity.move(MoverType, Vec3)
     -> maybeBackOffFromEdge / collide (resolved movement Vec3)
     -> addMovementThisTick(from, resolved-to)
     -> Entity.setPos(resolved-to)
     -> collision flags / setOnGroundWithMovement
     -> Level.getBlockState / checkFallDamage
     -> applyMovementEmissionAndPlaySound / setDeltaMovement
```

`Entity.move` bytecode places the resolved destination in a local value immediately before the
`setPos(Vec3)` invoke (the call sequence is bytecode offsets 276--310 in the inspected 26.2
artifact). That is a useful *discovery* candidate, but it is not a valid independent ownership
transfer authority. At that point `addMovementThisTick` has already mutated entity state, and
the same synchronous invocation continues through destination-sensitive mutable work listed
above. Moving the ledger to Region B there while allowing the tail to remain under the existing
Region A outer lease violates T-01/T-03. Cancelling and replaying the method under B leaves the
vanilla tick caller observing an uncompleted movement; executing its tail separately requires
splitting private method-local state and changes vanilla semantics. Redirecting every ordinary
movement caller is not a narrow 26.2 boundary and is unmanageably broad across entity types,
teleports, vehicles, and passengers.

Other candidates were rejected for the same evidence-backed reasons:

| Candidate | Result | Rejection reason |
| --- | --- | --- |
| `Entity.setPos` / `setPosRaw` globally | Rejected | Used by construction, teleports, reconciliation, and arbitrary callers; it does not carry the movement protocol or allow ownership-authorized completion of `Entity.move`. |
| Invoke before `Entity.setPos(Vec3)` inside `Entity.move` | Rejected as transfer authority | Destination is known, but the remaining synchronous method mutates destination-sensitive live state under the old lease. |
| After `ServerLevel.tick` reconciliation | Discovery only | All ordinary movement mutation has already occurred. |

Minimal reproduction: configure a serial A/B routing boundary and move an ordinary entity across
it in one `Entity.move` call. A pre-`setPos` ledger update makes the post-`setPos` tail a
Region-A mutation of a Region-B object. Delaying the update makes `setPos` a Region-A mutation
after the object should have crossed. Neither outcome is permitted, and waiting for B is
prohibited by T-05. This is therefore an **architectural escalation blocker** for real
non-player Region A -> B transfer. No `Entity` Mixin was added, no second worker was enabled,
and no Stage 3 PASS claim is made.
