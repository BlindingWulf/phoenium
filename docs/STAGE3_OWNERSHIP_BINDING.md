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
