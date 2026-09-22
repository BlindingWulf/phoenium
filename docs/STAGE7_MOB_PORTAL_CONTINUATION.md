# Stage 7 native mob portal continuation

The repair supports the exact passenger-free vanilla `ZombifiedPiglin`, `Chicken`,
and `Pig` classes through Nether portals in Minecraft 26.2. Subclasses, vehicles,
passenger graphs, End portals, and arbitrary modded tick overrides are not covered.
The binding is enabled in the primary authoritative-FULL-false profile.

The preserved 2026-09-22 05:08:08 failure belongs to a Nether ZombifiedPiglin,
runtime ID 2826, near (-17.57,38,8.72). Its UUID, operation generation and requested
query coordinates are unavailable in the original crash. The player's reported
chest coordinates do not identify that query. Close counters were 81 admitted,
81 effects, zero failures; the crash was the mob's owner-held POI dependency.

## Native execution and retained work

`ServerLevel.tickNonPassenger` calls the actual mob tick in the regional callback.
ZombifiedPiglin inherits `Zombie.tick`: conversion work precedes `Mob.tick`, with
no Zombie suffix after it. Chicken and Pig reach `Mob.tick` through Animal and
AgeableMob, and dispatch their different native AI through the living tick tail.
The shared nested path is `Mob.tick -> LivingEntity.tick -> Entity.tick ->
Mob.baseTick -> LivingEntity.baseTick -> Entity.baseTick -> Entity.handlePortal
-> PortalProcessor.getPortalDestination -> NetherPortalBlock.getPortalDestination
-> getExitPortal -> PortalForcer.findClosestPortalPosition ->
PoiManager.ensureLoadedAndValid`.

The cut is immediately before `getPortalDestination`. Native prefix effects,
portal eligibility, process state and cooldown work already performed remain
committed. The handlePortal and Entity baseTick profiler frames are balanced while
unwinding. Each of the four after-super living/mob cuts has only `this` live;
there is no captured arbitrary Java operand stack. The native Entity base suffix
is reused from the existing item/pearl mechanism. Four additional native tails
are copied into their own declaring classes, retaining private calls, branches,
RNG and dispatch. Structural checks and fixed semantic fingerprints fail binding
if the supported native code changes. Original methods continue serving ordinary
ticks; only the retained operation runs their suffixes separately.

The four tails run in native order: LivingEntity.baseTick, Mob.baseTick,
LivingEntity.tick, Mob.tick. They retain living timers/effects, mob ambient RNG,
equipment and AI/aiStep work, animation/fall-flight state and control flags. The
full original tick is never replayed. The source object receives the remaining
native suffix even when native teleport returned a different replacement.

The enclosing dispatcher callback returns and releases its lease before the
coordinator advances. Advancement occurs before the next regional entity callback
and shared RNG draw, preserving the established finite order without replacing or
rewinding the random stream. Preparation never holds a source owner lease.

## Reached queries and native transfer

POI validation requests the exact native `EMPTY, create=true` dependencies. Its
native radius is 16 towards the Nether and 128 towards the Overworld. Those
enumeration calls are left intact, not replaced with a preload radius. Warm POI
state may skip those calls; other reached rectangle/block queries can request
FULL and create=false. Diagnostics report the actually reached status, coordinates,
create flag, selected native future, holder, requested-status slot, holder lifetime,
provider publication and returned native object when available. Missing provider
or slot data is explicitly unavailable/absent, not fabricated. Query observations
are scoped to `(entity type, UUID, native incarnation, operation generation)`.

Preparation uses the unchanged installed public chunk/provider path. The existing
provider-aware `ResidentChunkSourceBinding` remains responsible for destination
readiness across exact provider publication and lifetime changes. Source identity,
registry epoch/incarnation, route/topology, world, processor identity/state, entry
position, entity position/motion/rotation/pose and operation generation are checked
before effects and after preparation. Destination insertion separately requires
the existing FULL resident receipt; this does not redefine every POI query as FULL.

`NativePortalTransfer` and `CrossDimensionReplacementBoundary` are shared with the
existing non-player transfers. The mapped order is eject/create replacement,
restore, source removal, replacement position, destination insertion plus synchronous
tracking, reset empty time, post-transition, then native return. Source retirement
uses the source operation and destination insertion/tracking uses the destination
operation. A success marker is emitted only after the retained source suffix and
boundary settlement. Expected dimension retirement differs from external removal;
late/duplicate completion, source changes, world closure, shutdown and failed
partial insertion cannot retire a newer same-UUID incarnation.

## Connected death/menu recipient repair

The faithful installed menu-open death card exposed a second concrete failure:
native death removal retires entity tracking, while the connected dead player
remains in PlayerList and can still receive a chest block event. The nearby
recipient projection previously demanded a live entity publication and failed.
A position receipt is now captured immediately before the native tickDeath remove
call, made usable only after completed native removal, and attached to that exact
player incarnation. Only connection-recipient projection uses it. The original
live TrackingSession must remain valid on every access/send. Movement, old-list
retirement and terminal cleanup invalidate it. Entity tracking is not republished
and its validity checks are unchanged.

Native 26.2 deliberately ignores the numeric ID of a newly received close packet.
The existing admitted-operation identity guard, including delayed A work after B
reuses A's numeric ID, is preserved. The installed harness records ordinary close
A/open B separately from controlled already-admitted delayed A unit coverage.

## Validation boundaries

The red regression enters actual mob.tick, Entity.handlePortal, NetherPortalBlock
and native POI enumeration on the unchanged baseline. Green tests run ready and
controlled pending inputs in both directions for all three classes; endpoints for
native world queries/portal output are explicitly controlled in the fixture.
They check multiple queries, no lookup before callback release, exact suffix state,
replacement ordering, cancellation/invalidation and partial insertion cleanup.
The shared provider suite independently covers six stale-publication changes.
Installed evidence uses ordinary native gameplay and a separate observer JAR,
normal TCP Fabric client input, and the unchanged pinned core provider profile.
Modeled pending futures do not prove an installed pending-provider timing window.

Final candidate accounting and installed evidence live in the continuation campaign
`.audit/astra-max-mob-continuation-20260922-110554`, outside the production JAR.
Human Bedrock UI, legitimate older-client Via and two-client audio remain separate
client validation gates. This document does not declare Stage 7 complete.
