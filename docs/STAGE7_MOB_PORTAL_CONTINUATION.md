# Stage 7 native mob portal continuation

The current repair binds 33 reviewed, exact passenger-free vanilla mob classes through
Nether portals in Minecraft 26.2. It retains the earlier ZombifiedPiglin, Chicken,
Pig, and EnderMan results and adds the AbstractCubeMob family, MagmaCube and Slime.
Other exact classes with the same native caller structure are bound and exercised in
the transformed native-path fixture. This is not a universal Mob binding. Vehicles,
passenger graphs, End portals, modded overrides, and the distinct vanilla caller
families identified below remain open. The binding is enabled in the primary
authoritative-FULL-false profile.

The 2026-09-22 14:24:49 live failure was a MagmaCube runtime ID 2173 crossing
Nether to Overworld. The old exact-four selector did not capture its Mob.tick. Native
portal handling therefore proceeded inside the Nether owner callback. Already-ready
destination insertion called Overworld tracking while that callback still held the
Nether context and failed in RegionalTrackingService.addEntity. The source did not
return before destination work. NativePortalTransfer did not own that insertion;
its active operation was absent. This is a different first boundary from the older
ZombifiedPiglin owner-held POI dependency. Runtime ID 2173 is not an incarnation or
UUID. The old installed success and “Active failure: NONE” are historical.

The preserved 2026-09-22 05:08:08 failure belongs to a Nether ZombifiedPiglin,
runtime ID 2826, near (-17.57,38,8.72). Its UUID, operation generation and requested
query coordinates are unavailable in the original crash. The player's reported
chest coordinates do not identify that query. Close counters were 81 admitted,
81 effects, zero failures; the crash was the mob's owner-held POI dependency.

## Vanilla caller and selection coverage

All supported rows use the exact-class selector in `MobPortalTransitionManager`,
enter through the real native Mob.tick call, retain at Entity.handlePortal before
destination lookup, and run the four shared LivingEntity/Mob tails after transfer.
The existing Entity.baseTick tail also resumes. “Native-path” means a transformed
fixture executing the real mob tick and portal code with controlled world-query
endpoints; it is not an installed player observation.

| Exact concrete classes | Declaring outer tick / portal path | Selection and remaining caller work | Verification |
| --- | --- | --- | --- |
| Chicken, Pig, EnderMan, Blaze, Bogged, Cow, ElderGuardian, Ghast, Goat, Guardian, Hoglin, IronGolem, MushroomCow, Ocelot, Parched, Piglin, PiglinBrute, Pillager, Ravager, Sheep, SnowGolem, Stray, Turtle, Vindicator, Witch, WitherSkeleton, Zoglin | Mob.tick and Mob.baseTick; LivingEntity.tick/baseTick; Entity.baseTick/handlePortal | DIRECT_MOB_TICK; no outer tick tail | All 27 native-path both directions, ready and controlled delayed; Chicken/Pig/EnderMan installed |
| ZombifiedPiglin, Zombie, Drowned, Husk | Zombie.tick prefix before Mob.tick; same shared inner path | ZOMBIE_PREFIX; Zombie has no suffix after super tick | All four native-path both directions, ready and controlled delayed; ZombifiedPiglin installed |
| MagmaCube, Slime | AbstractCubeMob.tick prefix, AgeableMob.tick to Mob.tick, same shared inner path | CUBE_OUTER_TAIL; extracted AbstractCubeMob.tick suffix runs once on the source after native transfer | Both classes native-path both directions, ready and controlled delayed, sizes 1/2/4; both installed with fitting portal scene |

The 27 direct and four Zombie-family additions close selector holes that share the
reviewed call/continuation shape. The exact class check remains intentional: an
unreviewed override must not inherit this proof accidentally. The native-path test
asserts selected disposition through real Mob.tick; it does not manually call
`begin` to mask a missing binding.

Distinct ordinary vanilla paths remain an engineering blocker. WanderingTrader
inherits `AbstractVillager.teleport`, a different transfer override. Skeleton
declares its own `tick` with work around Mob.tick. AbstractHorse subclasses have
their own outer tick behavior; several aquatic mobs override baseTick or tick.
These families are not bound by the current selector and have no pinned continuation
or installed transfer proof. They can still reach a Nether portal via normal play;
their unretained path can run destination effects under the source owner. The
negative selector test confirms WanderingTrader and Skeleton stay unbound. Do not
interpret a green 33-class matrix as safe coverage for every ordinary mob.

## Native execution and retained work

`ServerLevel.tickNonPassenger` calls the actual mob tick in the regional callback.
ZombifiedPiglin inherits `Zombie.tick`: conversion work precedes `Mob.tick`, with
no Zombie suffix after it. Chicken and Pig reach `Mob.tick` through Animal and
AgeableMob, and dispatch their different native AI through the living tick tail.
EnderMan inherits tick/baseTick through Monster and PathfinderMob directly from Mob;
its own aiStep clears jumping, updates persistent anger, then calls Monster.aiStep.
It has no outer tick suffix or TeleportTransition override and uses the same cuts.
This fourth exact class was added after a natural Enderman reached foreign-owner
destination tracking during the first final W4 restart (2026-09-22 12:03:13,
runtime ID52, source Nether near 0.70,65,-0.25). That failed candidate and restart
remain preserved. No new continuation primitive or gameplay restriction was needed.
The shared nested path is `Mob.tick -> LivingEntity.tick -> Entity.tick ->
Mob.baseTick -> LivingEntity.baseTick -> Entity.baseTick -> Entity.handlePortal
-> PortalProcessor.getPortalDestination -> NetherPortalBlock.getPortalDestination
-> getExitPortal -> PortalForcer.findClosestPortalPosition ->
PoiManager.ensureLoadedAndValid`.

MagmaCube and Slime both inherit `AbstractCubeMob.tick`; neither declares a tick
method. That outer method updates squish state before calling AgeableMob.tick and
has landing particles/sound, ground state and squish decay afterward. Its fifth
pinned suffix preserves those native branches, dispatch and random draws. The
cube-only after-super cut cancels that outer suffix during retention. On completion
the suffix runs exactly once on the source cube under the source operation, even if
native teleport returned a distinct destination replacement. Size NBT and health
take their native replacement path. Native `AbstractCubeMob.remove` splits only on
death at size greater than one; CHANGED_DIMENSION retirement of a living cube does
not split it.

The inner cut is immediately before `getPortalDestination`. Native prefix effects,
portal eligibility, process state and cooldown work already performed remain
committed. The handlePortal and Entity baseTick profiler frames are balanced while
unwinding. Each of the four after-super living/mob cuts has only `this` live;
there is no captured arbitrary Java operand stack. The native Entity base suffix
is reused from the existing item/pearl mechanism. Five additional native tails
are copied into their own declaring classes, retaining private calls, branches,
RNG and dispatch. Structural checks and fixed semantic fingerprints fail binding
if the supported native code changes. Original methods continue serving ordinary
ticks; only the retained operation runs their suffixes separately.

The four shared tails run in native order: LivingEntity.baseTick, Mob.baseTick,
LivingEntity.tick, Mob.tick, then the AbstractCubeMob.tick tail for cubes. They retain living timers/effects, mob ambient RNG,
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

The red regression enters actual Mob.tick, Entity.handlePortal, NetherPortalBlock
and native POI enumeration on the unchanged baseline. Green tests run ready and
controlled pending inputs in both directions for all 33 selected classes; endpoints for
native world queries/portal output are explicitly controlled in the fixture.
They check multiple queries, no lookup before callback release, exact suffix state,
replacement ordering, cancellation/invalidation and partial insertion cleanup.
The shared provider suite independently covers six stale-publication changes.
Installed evidence uses ordinary native gameplay and a separate observer JAR,
normal TCP Fabric client input, and the unchanged pinned core provider profile.
Modeled pending futures do not prove an installed pending-provider timing window.

The previous exact-four checkpoint lives in
`.audit/astra-max-mob-continuation-20260922-110554`; the new Magma repair accounting
and installed evidence live in `.audit/astra-max-magma-repair-20260922-150510`.
Human Bedrock UI, legitimate older-client Via and two-client audio remain separate
client validation gates. This document does not declare Stage 7 complete.
