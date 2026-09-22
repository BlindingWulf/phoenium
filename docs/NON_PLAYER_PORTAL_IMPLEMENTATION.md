# Portal destination preparation repair

Baseline: 2ed5a836ae8e65ca77430f0314eef840b452fd95, parent
 e1f80b7c71a950b27e0e540f35e371395d299bac, existing
stage7/respawn-lifecycle-repair worktree. Earlier evidence and candidates remain
in the project-root .audit/non-player-portal-repair-20260921 directory. This
repair's receipts are in .audit/portal-destination-preparation-20260922.

## Proven missed boundary

The 2026-09-21 22:58:26 Red Sand ItemEntity crash (runtime ID 225) occurred before
any transfer record. Its UUID, operation generation, incarnation, route and failed
chunk coordinates are UNAVAILABLE. Runtime ID and position cannot recover them.
The installed item mixin selected Entity.handlePortal's Entity.teleport call.
That cut was too late: getPortalDestination had already called native
NetherPortalBlock.getExitPortal -> PortalForcer.findClosestPortalPosition ->
PoiManager.ensureLoadedAndValid -> Level.getChunk -> getChunkBlocking while the
regional entity owner still held its lease. Local 26.2 bytecode/source and the
native lookup regression establish ChunkStatus.EMPTY, create=true for the POI
validation query, not FULL. Native transfer's later FULL admission is separate.

A baseline red test was executed before production edits. It starts at native
ItemEntity.tick -> Entity.tick/baseTick/handlePortal -> PortalProcessor, with a
controlled NetherPortalBlock lookup endpoint reaching an unfinished dependency
through the original ChunkSynchronousAccessGuard. It reproduces the original
ChunkProtocolException. Its early-cut binding reads the compiled production
mixin selector, so the baseline's late cut cannot pass the regression.

## Retention, preparation and application

The item cut now precedes PortalProcessor.getPortalDestination. Native cooldown
and portal-prefix state are already complete. It retains the exact native item,
world, processor, portal/entry/time/inside state, operation generation and registry
incarnation/epoch/route. Only exact ordinary ItemEntity and NetherPortalBlock
attempts without a passenger graph are admitted. Custom subclasses are outside
this continuation. No placeholder transition or null suspension signal is returned.

Typed baseTick and ItemEntity.tick return seams unwind the source callback. The
remaining ServerLevel.tickNonPassenger suffix is profiler/pop and empty passenger
iteration. The existing owner-free coordinator then executes native destination
search exactly once. Tests check OwnershipContext is empty AND every real scheduler
lease is inactive. Nested owned drains reject preparation; reentrant owner-free
drains cannot repeat a search already on stack. Source identity and portal state
are checked before/after preparation, admission and suffix consumption. A native
null destination resumes the original source suffix once without transferring.

NativePortalTransfer and CrossDimensionReplacementBoundary remain the replacement
mechanism. The original virtual teleport performs native serialization/restore,
source retirement, destination position/insertion, post-transition effects and
ItemEntity.teleport's native merge. Source/destination effects have separate owner
operations. The original source then completes its base/item suffix once. Its
later age/pickup-delay writes are not copied into the already-created replacement.
The quantity regression merges incoming Red Sand 3 into destination Red Sand 60,
producing total 63 with one native replacement and one original item suffix.
Existing exact cleanup journals and original exception propagation are preserved.
Committed source retirement is never resurrected by replaying a tick.

## Thrown pearl and player hit composition

ThrownEnderpearl already had the correct pre-destination cut. The pinned bytecode's
server super.tick invocation is ordinal 1 (decompiler branch order is misleading).
Its single RetainedPearlTickContinuation retains collision, section and owner
locals; no item continuation is registered for a thrown pearl. Preparation now
checks exact portal/registry state, rejects reentrant replay and records phases.
The native replacement's ticket suffix and the original typed base/projectile/
throwable/pearl suffix ordering remain. A retired source cannot collide again.
The tick capture is cleaned in a finally wrapper, including cancellation/failure.
A retained ticket suffix cannot mutate a stale respawn/reconnect owner pointer.

Destination projectile travel and player travel are separate operations. At real
native onHit, the owner resolves through the existing scoreboard's current UUID
and exact live connection/listener session. A foreign-owner player hit is retained
before player cooldown mutation and teleport. Native collision/particles/endermite
prefixes have already occurred; they are not replayed. The pearl callback returns.
The coordinator validates both identities and invokes native ServerPlayer.teleport
under the player's source owner. The existing PlayerDimensionTransitionManager
cuts after committed native source removal and resumes its original native
insertion/packet/advancement suffix under the destination owner. Player damage
runs there; pearl sound/discard run under the exact pearl owner. All are consumed
once. A same-world local native hit keeps its native path.

The read-only session receipt uses the existing lifecycle journal; it creates no
session and does not change death/respawn replacement. Pre-source stale hits retire
only the exact pearl. Session/binding loss after a pearl transfer commits source
retirement is a terminal FAILED operation, with no replay or mutation through a
new same-UUID player pointer. Shutdown/world closure cancels queued operations;
it cannot certify gameplay success. Reentrant dimension drains cannot duplicate
source/destination work.

## Source/test matrix

| Entity | Native lookup / scheduling | Retained caller suffix | Classification |
|---|---|---|---|
| Dropped item | ItemEntity.tick -> baseTick -> handlePortal under regional owner; native POI may wait | Exact base + item suffix, now retained before lookup | CONFIRMED SAME HOLE at baseline; repaired and controlled-native tested |
| Thrown pearl | ThrownEnderpearl -> Throwable/Projectile -> baseTick under regional owner | One existing collision/flight continuation; own early capture | COVERED BY EXISTING PATH for preparation, strengthened; native hit/player composition repaired |
| Passenger-free pig | Pig.tick -> Mob/LivingEntity -> baseTick -> handlePortal under regional owner | No complete LivingEntity/Mob continuation | CONFIRMED SAME HOLE; native pig regression reaches original guard; mob support deferred |
| Player-only portal | Network listener tick -> tickPlayer -> ServerPlayer.doTick -> Player.tick; owner-free serial path. Regional ServerPlayer.tick does not invoke Player.tick | Existing player dimension source-retirement/destination suffix | COVERED BY EXISTING PATH; native handlePortal/player transition regression in both directions |

Mobs require a materially different LivingEntity/Mob base and tick continuation;
a generic Entity suspension would discard their remaining native work. This repair
does not claim mob, passenger/vehicle, arbitrary projectile, custom subclass or End
portal support. Ender pearls dropped from inventory do not test thrown pearls.

## Evidence and limitations

New tests exercise native item/pearl ticks before lookup, native Nether portal/POI
search requesting EMPTY/create=true, both directions and ready/incomplete controlled
dependencies, native no-destination behavior, original failure propagation, stale
processor/world/removal/incarnation, native item replacement/merge, native projectile
flight/onHit, real native player teleport source and destination suffixes, separate
owners, stale session, consecutive independent throws and player-only portal entry.
Existing respawn/session, tracking/packet, provider resident-binding and lifecycle
regressions remain in the affected selection. Production mixin selectors and suffix
call ordering are checked independently against mapped bytecode.

Controlled endpoints: world storage/tracking environment, chunk dependency future,
portal placement failure, selected physics, collision stimulus, packets, damage,
sound and advancement. The native POI tests do not manufacture a C2ME slot or claim
installed pending-provider evidence. The composed hit tests preserve native flight,
onHit, player teleport prefix and actual native suffix instructions, with those
environment endpoints controlled. The final jar's fresh Fabric startup separately
checks actual mixin binding, readiness and normal stop, not portal gameplay.

The final affected selection is the preserved 32-class list plus
NativePortalDestinationPreparationTest and NativePearlPlayerHitTest. The broader
selection uses the preserved bounded-suite.gradle unchanged. Its nine exclusions
are FullNonselfInputDecisionTest, C2meDiskReadOperationTest, C2meDiskReadRxTest,
C2meFullProviderBinaryTest, C2meGenerationProviderBinaryTest,
C2meNativeAvailabilityTest, C2meNativeFailureSettlementTest, C2meReadProviderBinaryTest
and C2meSendReadyInputsTest. No new exclusions or weakened assertions were added.
Final commands, counts, source fingerprints and XML are in this repair's audit.

Historical accounting remains separate: the earlier unfiltered 1,264 tests with
73 failures was not green; the 1,225-count reconciliation remains unresolved.
The original 2ed5a83 artifact creation had a broader 1,207/1 failure followed by
276/276 affected tests. Luna subsequently reran the unchanged broader selection
on clean 2ed5a83 and recorded 1,207/1,207; neither result substitutes for this
repair's final-source verification. No new provider binary audit was performed.

Diagnostics-only milestones use PHOENIUM_NON_PLAYER_PORTAL,
PHOENIUM_PORTAL_DESTINATION and PHOENIUM_PEARL_PLAYER_HIT. They include exact identities,
operation generation, known worlds/routes, owner context, observed requested chunk/
status/create and first failing phase; unknown values are explicit. Selected futures
are observed as native objects, never invented provider slots. Diagnostic exceptions
cannot replace an original failure. Capture/crossing/source-retirement is not settled
player travel; any crash or scheduler FAILED overrides aggregate counters and exit 0.

The immutable candidate identity, all eight pinned provider/config identities,
packaging audit, final startup receipt and short live action cards are recorded in
LUNA_PORTAL_DESTINATION_VALIDATION_HANDOFF.md in the project-root audit directory.
Only Luna with the user's real client actions can establish Bedrock gameplay
acceptance. No Stage 7/W4/all-entity/End-portal acceptance follows from this repair.
