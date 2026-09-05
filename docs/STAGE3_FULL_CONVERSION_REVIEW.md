# Stage 3 FULL conversion review and load regression repair

2026-09-05 current checkpoint: [D2/D3 approved](STAGE3_FULL_D2_D3_APPROVAL.md),
but a [concrete neighboring collision/FULL cycle](STAGE3_FULL_NONSELF_QUERY_CONFLICT.md)
blocks integrated entry within that direct-self authority. The
[implementation ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md) records the
query-failure safety correction, 264 passing tests, final runtime regressions
and exact remaining gates. The review history below is preserved.

## Current D1–D3 disposition — 2026-09-04

[STAGE3_FULL_D1_D3_DECISIONS.md](STAGE3_FULL_D1_D3_DECISIONS.md) supersedes
the open-decision conclusions below while retaining this source inventory and
M1/M2/scoreboard ordering. D1 is **RESOLVED WITH DOCUMENTED AMENDMENT** through
typed identity/target-validation facts, not foreign object authority. D2
**REQUIRES USER APPROVAL** for pre-admission legacy-name/cache/persistence
effects and observation timing. D3 **REQUIRES USER APPROVAL** for the proposed
private exact-object query that breaks the source-proven self-FULL cycle.
No consent is invented and no production authority is installed.

The new witnesses establish that Mob.setTarget validates through concrete
target predicates; saved FishingHook's reader is empty; cache IOException is
caught while runtime failures retain their original propagation; and the actual
FULL supplier can finish after a caught conversion failure with team effects
already committed. They run original proto and reload post-load paths, exact
future selection, frog finalization/collision, and seeded random checks with
explicit controlled environment endpoints. The self-query witness does not
enter managedBlock or claim a live hang. Dynamic non-air collision shapes and
mutually dependent non-self work remain named binding/closure obligations in
that decision, not implied fixture coverage.

Baseline **221/221**, final clean build **253/253**, no failures/errors/skips,
production artifact unchanged. Cleanup checkpoint `a99d647` and all existing
scoreboard/cleanup witnesses remain intact. **FULL callback closure: INCOMPLETE;
integrated implementation ready: NO; production FULL: NOT ENABLED.** Next action
is the explicit decisions/acceptance sequence in the linked document, rather
than treating the older “decision needed” text as unexamined evidence.

## Prior callback review at 84a01d4 — 2026-09-04

**Cleanup: FIXED in `a99d647c1ed39b1a96d5267e6f08f06ba17d8945`. FULL callback
closure: INCOMPLETE. Integrated FULL implementation ready: NO.** Three precise
semantic decisions remain (D1–D3 below). The accepted
[scoreboard amendment](STAGE3_FULL_SCOREBOARD_SERVICE_DECISION.md) is unchanged.
This review specifies the additional synchronous participation needed by its
eventual FULL caller; it installs no coordinator or production FULL authority.

Starting HEAD was `8c0072ac559bf53dee5672ee97c29f76020834c8`, clean, 206 tests.
The dedicated cleanup commit passed 212 tests. The original 34-cancellation
fixture now requires zero orphan records; 272 cancellations across eight batches
on one service root also end at zero. The handoff records the exact completion
sequence, final-artifact missing-route probe, normal runtime regression, and
preserved unsuccessful probes. This callback amendment is a separate commit.

Final clean build (`clean build compileClientGameTestJava --no-daemon`): **221
tests, 0 failures/errors/skips**, BUILD SUCCESSFUL. Production JAR SHA-256 is
`347E1EB30F078CBFF4CBB969244AED6E0324A5C0486DA61B894566D46210F796`, identical to
the cleanup artifact used for the accepted live probe and normal regression.
Build log and evidence manifest: `run/full-callback-closure/final-build.log`
and `final-evidence.json`. This review changes tests/documentation only.

### Source and evidence boundary

Minecraft is 26.2, Java 25, Loader 0.19.5. The pinned deobfuscated Minecraft JAR
was rehashed: `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.
`genSources` produced a readable view under
`run/full-callback-closure/source/`; original bytecode remains authoritative.
The decompiler's development interface additions are not evidence that the full
development Fabric API is installed on the external server.

The **scoped production environment** is vanilla plus Phoenium and the actual
external-server modules: fabric-api-base, fabric-command-api-v2
3.1.0+00cb03469e, fabric-lifecycle-events-v1 4.1.4+29b6eb019e. The development
dependency is Fabric API 0.159.0+26.2; installing all its other modules would
require a new callback inventory. No compatibility mods are covered. Existing
offline command-line client tests remain test-only.

Source inventory: `ChunkStatusTasks.full`'s supplier (`lambda$full$0`),
`LevelChunk` constructor/setBlockEntity/runPostLoad,
`ChunkStatusTasks.postLoadProtoChunk`,
`SerializableChunkData.postLoadChunk`'s callback, `EntityType.create`,
`loadStaticEntity`, `loadEntityRecursive`, `loadPassengersRecursive`, and the
actual `EntityTypes`/`BlockEntityTypes` factories. The factory for PLAYER returns
null; it is not a saved ServerPlayer construction path. FISHING_BOBBER has a
factory despite noSave/noSummon and cannot be excluded merely by those flags.
The declaration census (`override-inventory.json`, 274 selected declarations)
was used to find concrete dispatch, not as a proof that every transitive method
is safe. The table below exposes the unresolved branches rather than claiming
complete recursive closure from base-class review.

`FullCallbackAuthorityContractTest` adds **nine semantic witnesses**:

1. A real waypoint manager reads the receiver's level/gamerule even when a real
   first-tick LivingEntity returns no connection.
2. Original `ServerEntity.addPairing` → `WitherBoss.startSeenByPlayer` →
   `ServerBossEvent.addPlayer`: bundle send precedes boss packet; a thrown boss
   send leaves boss membership committed. Pairing-data collection is controlled.
3. Original listener registration mutates its list before debug's live entity
   lookup; a lookup failure leaves the listener registered.
4. Original Jukebox removal marks removed, then dispatches its game event; a
   failure prevents the following level event without undoing removal.
5. Original EntityReference resolution returns and caches the actual foreign-
   dimension entity, including its liveness read; the directory lookup is controlled.
6. Original old-owner conversion consults the name cache before offline fallback;
   a UUID string bypasses that cache.
7. Original EntityType runtime-exception catch can retain a nested boss-operation
   prefix while an enclosing supplier succeeds. Its factory is controlled;
   this is not a complete Entity.load or FULL supplier test.
8. Real `CachedUserNameToIdResolver.get` on a miss invokes a controlled profile
   repository and writes an actual temporary usercache file before returning.
   No authentication/account/network lookup is performed by the witness.
9. Original Tadpole `setAge` → `ageUp` and its conversion callback synchronously
   reach the difficulty query; conversion construction/query endpoints are controlled.

These are **semantic witnesses**, not approved-contract concurrency tests,
production-binding tests or live FULL acceptance. The cleanup tests exercise the
real bound EMPTY graph; its live acceptance is separate. No test here proves
network delivery, a complete transformed FULL supplier or arbitrary callbacks.

### Operation inventory and authority contract

In this table **R** means one converged, frozen region enclosing every accessed
live object/section; **W** means the named world registry's finite logical turn;
**C** means the exact connection-session submission entitlement and directory
cut from the accepted scoreboard decision; **S** means that decision's scoreboard
turn. These are different authorities. W never authorizes live entity reads.
All required turns/capacity and footprint receipts precede the FULL claim.
All map/list critical sections end before any callback, send, executor or future.
“Covered” below is a contract mapping, never an assertion that its binding exists.

| Exact operation and FULL reach / concrete source | Mutable state and authority / geographic footprint | Required synchronous result and notification order | Reservation and failure/partial-effect rule | Existing contract / remaining gap |
| --- | --- | --- | --- | --- |
| `EntityType.create` → concrete constructors → `Entity.<init>` → `ServerLevel.getNextEntityId` | Global atomic ID counter plus **world tracker-ID collision lookup**; W tracker/ID registry. New entity fields under exclusive input custody, then R. Feature/difficulty/registry reads use the reserved world-context version. | ID, data tracker, attributes, goals and initial position exist before load; constructor's virtual setPos is included. | Reserve ID/tracker access before construction, not only before `ChunkMap.addEntity`. Constructor failure can consume an ID; do not roll it back. | Architecture §§4/9; explicit W participation here. Factory failure is caught by loadStaticEntity. |
| `Entity.load` → actual additional-data readers and data-tracker setters | New entity graph, equipment/effects/brain, dimensions, UUID, bed position; R for any addressable aliases. Pos and SleepingPos may name different chunks from the source chunk. | Preserve reader order: saved Team is an intermediate effect, not the last callback. FirstTick suppresses `refreshDimensions`' free-position search for newly constructed entities; it is not a general callback exemption. | Freeze input and derive positions/aliases without executing readers for reconnaissance. S before the first Team/getTeam access. | §§4/6/10 plus S. D1–D3 prevent full closure of this row. |
| `LivingEntity` saved Team → ServerScoreboard; `ConversionParams.single` and `ConversionType.convertCommon` also read/change teams | Existing team graph S; newly constructed/converted entity R; connections C. | Exact boolean, same-team REMOVE/re-add, original recipient loops, waypoint then dirty; conversion reuses S and records distinct nested occurrences. | Null player-name branch must be established from C's pinned name/session directory; a match requires the separate waypoint/R authority before access. | Accepted scoreboard §§2–5, unchanged. No unconditional null stub or disconnected coordinator. |
| `PersistentEntitySectionManager.addWorldGenChunkEntities/addLegacyChunkEntities` → `addEntity` | knownUuids, sectionStorage, section contents, visible lookup, callback field, chunkVisibility; W entity registry + R at **actual entity positions**. | UUID check before insertion; false duplicate result remains false. Worldgen invokes onCreated; legacy loaded=true skips it. Accessible sections immediately start tracking; ticking sections register ticking. Root insertion can precede passenger loading. | W registry turn and complete R before first lookup. Preserve insertion prefix if callback throws; do not retry root or pretend insertion was atomic. | §§4/9/10. Maps are not a license to inspect their entity payloads without R. |
| `ServerLevel.EntityCallbacks.onCreated/onTrackingStart` → `ServerWaypointManager.trackWaypoint/createConnection` | waypoints set, receiver set, connection table under W waypoint; receiver level/gamerule, transmitter range/firstTick and possible receiver position/team/tracking reads under R/context/S. Footprint includes every inspected receiver, even before a distance rejection. | Set insertion precedes receiver loop. createConnection puts table entry before connect; replacement updateConnection calls connect before put. Preserve both orders. | W waypoint + C + S and R for reachable receiver branch. FirstTick returns empty inside LivingEntity, **after** manager receiver access. Exception leaves exact set/table prefix. | Named finite-registry extension M1 below; semantic witness 1. |
| `LivingEntity.makeWaypointConnectionWith`; `WaypointTransmitter` EntityBlock/EntityChunk/EntityAzimuthConnection | Transmitter icon/attributes/team, receiver properties/position/chunk visibility and saved connection state; W waypoint, S, C, R for live entities. Same-world receivers for this manager; ordinary team callbacks can select a player in another world. | isReallyFar / isChunkVisible selects actual connection; connect/update/disconnect submission occurs at the original call before return. | Frozen input branch, no receiver-region acquisition from inside S. Direct restore normally has no name match, but proof is C metadata, not UUID convention. | M1 and accepted scoreboard §3. Foreign entity reference branches remain D1; ordinary non-FULL team operations reserve their own enclosing phase. |
| `onTrackingStart` → `ServerChunkCache.addEntity` → `ChunkMap.addEntity` / `TrackedEntity.<init>/updatePlayers/updatePlayer` | entityMap, tracker state, seenBy and world player list W tracker; **every** iterated player's position, view distance, connection, tracking view/chunkSender state; R for players/vehicles, C for session state. | Insert tracker before recipient loop. Read position/view before visibility decision. Add seenBy before pairing. Preserve false/not-tracked removal path, list order, passenger-derived range. | W tracker + C + full R before first access, including constructor ID collision lookup. Failed pairing leaves entityMap and seenBy prefix; do not repeat pairing to “repair” it. | §10's tracking snapshot intent is made precise by M1/M2. Same thread/nearby recipients are insufficient. |
| `ServerEntity.sendPairingData/addPairing` → concrete packet factories and `LivingEntity.updateDataBeforeSync` | entity data/effects, syncable attributes, equipment copies, passengers/vehicle and leash/owner references; R plus W tracker, S where referenced, C. | Update dirty effects; add spawn packet, tracked data, attributes, equipment, passengers/vehicle, leash data in original order; send bundle; invoke startSeenByPlayer; only then tracker debug registration/initial state. | C order spans all these packet families. Packet creation failure produces no bundle; bundle send failure skips startSeen; later callback failure keeps accepted bundle. No stage replay. | M2; witness 2 covers the suffix. Projectile/FishingHook owner resolution exposes D1. |
| `WitherBoss.startSeenByPlayer` → `ServerBossEvent.addPlayer`; Raider reader → `Raid.addWaveMob/updateBossbar/setLeader` | Boss membership/dirty/progress and raid maps W boss/raid; newly loaded raider R; **all existing raiders whose UUID/health is read** also R, even outside the chunk. Boss recipients C. | Wither adds membership then sends. Raid inserts/replaces wave member, calculates health/progress, broadcasts then marks raid dirty; leader update follows successful addWaveMob and changes new raider equipment. | Reserve boss/raid turns and entire referenced-member footprint before reader. No whole-raid rollback or invented dirty update after send failure. | §9 finite service, explicitly bounded to registration/read/notification (M1). This is not raid gameplay regionalization. |
| Allay/Warden `updateDynamicGameEventListener` → `DynamicGameEventListener.add/move` | Allay's vibration + jukebox listeners; Warden's vibration listener; position-source reference/cache and previous/new section registries R. | Resolve position, unregister old section, set lastSection, then register new. `getChunk(FULL,false)` is synchronous and may schedule/wait; “false” does not prove ready. | Preflight exact getter results/readiness for every target. Already-ready access only during R; otherwise park before supplier. A dependency on this same unpublished FULL cannot be satisfied by an owner wait (D3). | §§6/7/10 no-wait/enclosure. UUID-backed position resolution additionally needs D1 when foreign. |
| `EuclideanGameEventListenerRegistry.register/sendDebugInfo` | listeners/listenersToAdd, processing state R; debug enabled-subscriptions W debug; BlockPositionSource or EntityPositionSource/UUID lookup W entity + R; tracker/subscriber lookup W tracker/debug + C. | Register first; then debug subscriber check, source/radius lookup, then immutable debug packet submission. Diagnostic work is not discarded. | W debug/tracker/C ready before registration. Failed live lookup/send retains registered listener, as witness 3 proves. | M1/M2. No “registration is metadata-only” assumption. |
| `LevelChunk.registerAllBlockEntitiesAfterLevelLoad` → `EntityBlock.getListener` / `ServerLevel.onBlockEntityAdded` / `updateBlockEntityTicker` | R block entities, local ticker wrappers/section listener maps; W world blockEntityTickers and debug maps. EntityBlock uses GameEventListener.Provider: Sensor (including Calibrated), Shrieker, Catalyst. | Listener registration, debug value registration, ticker lookup/rebind/level insertion in that order, now. Selecting a ticker does not run it. | Named world registrations reserve W before entry. Partial registration remains; ticker failure does not roll back listener/debug work. | §§9/10 with M1. Concrete ticker execution remains out of scope. |
| `LevelDebugSynchronizers.registerEntity/registerBlockEntity/startTrackingEntity` → `TrackingDebugSynchronizer.SourceSynchronizer` | sleeping/enabledSubscriptions/source maps/subscriber UUID sets W debug; entity UUID/brain-presence and block position R; exact player debug subscriptions/tracker membership W/C plus R where live. | Register source getter closures synchronously. Mob checks brain-dead **now**; getter evaluation is later. Initial cached values are submitted after pairing; SourceSynchronizer looks up that entity's stored value and subscription. | Register retained getters with entity/block lifecycle; later poll must run under that target's own authority. No evaluating foreign suppliers while holding debug-state locks. | M1/M2. Base Entity/BlockEntity no-op; concrete Mob, Bee, Breeze, Beehive overrides are named below. |
| `LevelChunk` constructor and reload post-load → `setBlockEntity`; `BlockEntity.loadStatic/loadWithComponents` | Pending NBT/local block map/old instance R; components and newly constructed instance exclusive until installed. | loadStatic constructs and loads **before setLevel**. Therefore null-level branches in new Lectern/Spawner/Command/Structure/TrialSpawner readers do not run their live effects. Proto-carried instances are installed, not reloaded. keepPacked retains NBT. | Validate actual aliases/types and existing local replacement under R. Factory/loadStatic catches Throwable and returns null; replacing an old instance occurs after map put and can throw. Never generalize null-level proof to arbitrary already-live loadWithComponents. | §§4/10; concrete overrides below. Synchronous original catches preserved. |
| `BeaconBlockEntity.setLevel/setRemoved`, `PotentSulfurBlockEntity.setLevel`, `HopperBlockEntity.setBlockState`, base clearRemoved | Local fields R; Beacon reads minY, sulfur reads gameTime; Beacon removal sends sound through world's recipient selection and consumes world random sound seed. | Beacon level sets scan Y; sulfur initializes eruption tick once; hopper recomputes facing. Beacon removal sends sound **before** base removed flag. | Frozen world-context/random entitlement and C with recipient footprint before call. A sound failure can leave old beacon not marked removed. | M1/M2; future geyser/beacon/hopper ticking is not included. |
| `JukeboxBlockEntity.setRemoved` → `Level.gameEvent` → `GameEventDispatcher.post` and levelEvent(1011) | Removed flag R; loaded chunk/section lookups and listener iteration R; W chunk registry/debug/tracker, C. JUKEBOX_STOP_PLAY notification radius **10**, with section-rounding accesses before exact distance filtering. | Mark removed; visit immediate listeners in original section/list order, collect/sort BY_DISTANCE, invoke them, send debug event if applicable; then level-event broadcast. Allay.JukeboxListener clears jukebox/dancing state synchronously. | Enclose every visited listener source and callback target, not just center. Vanilla vibration tags exclude this event; do not extrapolate to mount/equip/conversion events or changed data packs. Witness 4 proves failure prefix. | §§6/7/10 plus M1/M2. D3 covers recursive conversion/discovery beyond a ready footprint. |
| Entity load's concrete riding/position/reader hooks (next table) | New passenger tree plus already-inserted roots R; world registries W; S/C for nested conversion/team/send. | `loadPassengersRecursive` calls startRiding(force=true, sendEventAndTriggers=false), but concrete overrides still run. Input positions, bed positions, passenger redirection and new converted entities belong to closure. | Preflight without running constructors/readers twice. Preserve load stream's root-before-passenger order. | §§4/6/10; D1–D3 explicitly remain uncovered, not silently disabled. |
| `registerTickContainerInLevel` → `LevelTicks.addContainer`; `setUnsavedListener` → ChunkMap eager-save set | allContainers, nextTickForContainer, installed onTickAdded callback, eager-save membership W; chunk fields R. | Publish both tick containers now; no tick execution. Already-unsaved chunk calls listener immediately. | W ticks/save metadata before call; release unused reservations on preclaim failure. Existing finite map operations, no owner-to-service wait. | Architecture §9, no new semantic blocker. |
| Fabric `ChunkStatusTasksMixin.onChunkLoad` at supplier return; EntityType/section-manager/entity/block-event mixins | Fabric loaded-chunk cache W, holder event-full-status field R; entity spawn reason/loaded flag local; event subscriber arrays tied to pinned bootstrap set. | CHUNK_LOAD then CHUNK_GENERATE on non-Imposter branch, then ordered full-status event transitions before supplier returns. Built-in CHUNK_LOAD subscriber marks loaded cache. Block load/unload and entity load/unload dispatch stay at injected positions. | W Fabric cache + holder R before supplier. Exceptions propagate from original event iteration; partial event prefixes survive. Not permission to admit arbitrary new subscribers. | §9 plus M1; actual installed module bytecode in `fabric-callbacks.txt`. Phoenium has no chunk/entity/block load-event subscriber; its setPosRaw/removal derived-index callbacks still participate in the existing index contract. |

### Concrete dispatch closure notes

These distinctions constrain an implementation; none may be replaced by a base-
method-only approval:

| Dispatch family | Concrete server-relevant implementations and constraints |
| --- | --- |
| Position / data changes | Entity, Display, BlockAttachedEntity, Shulker setPos; AbstractMinecart reapplyPosition. ArmorStand, AreaEffectCloud, Interaction, AgeableMob, Zoglin, Zombie, Piglin, Phantom, AbstractCubeMob, fish and painting dimensions; HangingEntity direction; ItemFrame item/bounds; SulfurCube fuse; Camel/Nautilus timers; Frog/Sniffer/Warden/Armadillo animation state. New firstTick suppresses the collision-fudge branch, not positional mutation or index observation. ItemFrame's reader passes false to setItem/setRotation, suppressing its neighbor-update branch specifically. Client-only visual branches are excluded by source guards, not by a feature disable. |
| Riding | Mob can drop an already-resolved leash; fresh loaded leash data normally holds delayed references, so establish that branch from exact instance state. Shulker adjusts attach face before super; Piglin may redirect to the third-level top passenger; AbstractHorse rotates the passenger; HappyGhast sends harness sound, changes passenger list, then scans **all level players and their root vehicles**, possibly synchronizing position/still flags. Marker/OminousItemSpawner reject through couldAcceptPassenger; force does not bypass that check. HappyGhast sound is not suppressed by the false mount-event flag. |
| Packet factories | Base Entity; ItemFrame/LeashFenceKnot/Painting direction/block position; FallingBlock block-state ID; Warden emerging flag; Projectile/FishingHook live owner resolution (D1). EnderDragonPart is rejected by addEntity before tracker construction; Marker has zero tracking range. Their throwing packet factories are not ordinary pairing branches. |
| Reader world effects | NeutralMob implementations: EnderMan, Bee, IronGolem, PolarBear, Wolf, ZombifiedPiglin restore anger target via D1. Raider subclasses restore shared raid state and boss progress. TamableAnimal and AbstractHorse legacy Owner route is D2. Villager refreshBrain stops the newly built brain then registers goals/schedule using world clock and positional environment/biome layers; reserve context and biome footprint, never run a live brain to discover it. ZombieVillager.startConverting changes effects and broadcasts entity event 16 immediately. HappyGhast reader may sync still/position state. Tadpole's mature-age reader converts immediately (D3). |
| Other reader-specific limits | EnderDragon phase load starts from newly constructed HOVERING: phase begin resets local state; old flaming-phase cleanup is not present on that fresh instance. MinecartSpawner's BaseSpawner.load restores spawn data; its anonymous override only broadcasts on a later broadcastEvent, not load. SpawnerBlockEntity's setNextSpawnData live update is behind its null-level check during loadStatic. Skull only decodes ResolvableProfile here; it does not resolve it. Vault, TrialSpawner, loot-container and command/structure readers decode state with the loadStatic null-level restriction above. ItemEntity can discard an empty loaded stack; preserve removal/index notification without fabricating an item. Trident loyalty computation uses enchantment predicates/context and must be included in the same world-context and regional-read preflight. |
| Block-entity installation overrides | setLevel: Beacon and PotentSulfur; setBlockState: Hopper; clearRemoved: base only; setRemoved: Beacon and Jukebox. Sensor/CalibratedSensor, Shrieker and Catalyst supply concrete listeners through EntityBlock's Provider default. Their vibration/catalyst ticking is not invoked merely by getListener. |
| Debug value overrides | Mob registers path, goals and (when brain is not dead) brain getters; Bee adds hive/flower/travel/blacklist getter; Breeze adds target/jump getter; Beehive adds DebugHiveInfo getter. Registration is immediate, evaluating those closures is a later independently authorized operation. SourceSynchronizer's initial entity send uses cached lastSyncedValue; do not perform a new gameplay getter there. |

### Specified additions (M1/M2) for the eventual integrated FULL caller

**M1 — named world-registry turns and enclosure receipts.** Keep each existing
manager's state under its named owner: entity/section and tracker-ID registries,
waypoint table, tracking records, raid/boss registration, debug sources/subscribers,
tick containers, eager-save metadata and Fabric loaded-chunk cache. The serial
scheduler reserves their finite operation entitlements as a bundle before R,
alongside S and C. This refines architecture §9's existing registry boundary; it
does not give S authority over those maps. Every conflicting legacy command,
login/remove/respawn phase, serial tick and save access participates before its
enclosing body. No mutex is held for that whole body.

Footprint discovery consumes **owner-published routing/alias metadata**, not
unauthorized live object traversal: entity UUID/incarnation/chunk, player session
and root-vehicle chain, waypoint receivers, tracker candidate players, raid member
incarnations, section listener position-source identities, block replacement
types and ready-chunk versions. Supply immutable versioned world context for
feature/difficulty/gamerules, clock/environment layers and registry values;
reserve existing mutable random/ID/container accesses explicitly where used.
The fixture's current entity projection does not contain this complete metadata;
extending it is concrete implementation work, not evidence that authority already
exists. Version changes before claim invalidate the whole preflight cut.

For same-world live readers, use architecture §7's quiescent convergence to
enclose **all accessed** entities/sections before claim. This includes distant
players read before range checks, HappyGhast root vehicles, and all raid members
read for health. Do not choose a convenient radius and hope it covers them.
Preflight on-demand regional observations are independently admitted prior stages;
FULL owns no region while collecting them. Unknown callbacks or unresolved
foreign-world dependencies do not receive automatic entitlement. D1–D3 below
prevent a claim for the currently unresolved closure.

**M2 — connection ordering shared by synchronous packet families.** Bind pairing,
boss, waypoint, debug, sound and level-event submission to C's same exact session
and serial connection acceptance boundary already required for team traffic.
Recipient-selection live reads occur under R/W; freeze each packet at its original
construction point and invoke its original send synchronously. Preserve actual
iteration order, including HashSet iteration where vanilla uses it; do not invent
sorting or order across sockets. No later gameplay update may overtake that
session's spawn bundle, nested boss callback or initial debug data.

The scoreboard snapshot S/catch-up/LIVE handshake remains unchanged. Tracking
activation cannot expose a new session to pairing before required login state has
been submitted. The connection turn serializes that activation with the original
PlayerList/level/tracker registration edge. Respawn's reused listener retains
session order; new actual connections get new sessions. These entitlements permit
sends, not arbitrary access to the ServerPlayer behind the handle. Already
accepted sends are never replayed after failure, disconnect or a later FULL error.

### Combined service/region admission order

1. Register the exact original FULL continuation with lifecycle/WorkId/StageId,
   actual holder/reservation and exclusively retained input. Preserve Imposter
   reuse and the generation wrapper's actual returned R. Compute the required
   callback closure and prerequisite/footprint receipts without invoking it.
2. With no region held, resolve every required ready chunk and regional metadata
   observation; request convergence if needed. D1–D3 inputs remain unclaimed.
   Reserve bounded record/connection capacity and the finite turn bundle in fixed
   order: **server session/connection directory C, scoreboard S, then named world
   turns in (world-instance, service-kind) order**. This is a logical grant
   sequence, not nested OS-lock acquisition. If any member is unavailable, release
   acquired unused grants and park the typed stage with an authoritative wakeup.
   No service call is allowed to discover “busy” midway through a region body.
3. Before the regional claim, revalidate session/sync generation, all input and
   footprint versions, exact holder/reservation/status, lifecycle/work validity,
   receipts, route/epoch, service grants and shutdown/convergence fences. The
   custody claim consumes only already-ready immutable entitlement tokens;
   it does not call services or acquire their locks. Service publication uses
   release/acquire metadata synchronization, consistent with the accepted S rule.
4. Topology change before claim releases all unused turns; re-resolve the same
   StageId and repeat preflight. No default route. Claim/retirement uses the existing
   custody winner. A successful claim pins one R and its service turns through
   synchronous exit; topology cannot enlarge that frozen domain mid-body.
5. Run the original supplier once, preserving its thread-sensitive vanilla getter
   behavior as well as explicit authority. For example getChunkNow returns null
   off the server thread: a worker adaptation cannot silently drop game events.
   That physical execution constraint does not make thread identity authority.
   Execute only validated callbacks, ready local reads, reserved primitives and
   original synchronous sends. No owner wait, other-region lease, executor pump,
   deferred callback substituted for a synchronous return, or unreviewed closure.
6. On normal or exceptional exit, record reached prefixes and release unused
   occurrence/capacity slots; committed records remain until connection/service
   acknowledgments settle. Release R/claim in finally before future completion.
   No custody/index/service state lock survives into completion. Preserve supplier
   result/error independently from nested operation outcomes. Admit FULL holder
   publication separately, revalidating lineage/topology/barrier and prerequisites.

This serializes overlapping world services and **all FULL suppliers needing S**
for a server session, including time spent in synchronous packet submission.
Convergence can enclose many regions. No performance acceptability is claimed;
measure only after correctness and the unresolved decisions are settled.

### Three unresolved semantic decisions — do not implement around them

**D1 — synchronous live cross-dimension entity references.**
`NeutralMob.readPersistentAngerSaveData` calls
`EntityReference.getLivingEntity(reference, level)`. `EntityReference.getEntity`
uses `ServerLevel.getEntityInAnyDimension`: own UUID lookup followed by other
levels in server iteration order. It calls `isRemoved` on the returned live
entity, caches the exact reference, and returns it synchronously to setTarget.
Projectile/FishingHook pairing factories also resolve a live owner and read its
ID. The same UUID can be present in different level lookup domains; preserve
origin-first lookup order and current liveness, not merely a server-wide UUID map.

R is world-bound. Same-world convergence, C and S cannot authorize those foreign
reads. **Decision needed:** define an explicit read/alias capability with a
specified observation cut and lifetime for these precise foreign fields and
original object identity, including conflicting removal/replacement, or amend the
synchronous operation boundary so the reads can be performed before region entry
without changing their result/visibility. Returning a proxy/null, caching an
unversioned pointer, or reserving a second region under R is not approved.
Witness 5 proves the live-reference contract; it does not implement the remedy.

**D2 — legacy owner-name lookup and persistence inside entity loading.**
TamableAnimal and AbstractHorse readers call
`EntityReference.readWithOldOwnerConversion` for a string Owner, then
`OldUsersConverter.convertMobOwnerIfNecessary`. Before its offline fallback it
calls `CachedUserNameToIdResolver.get`: expiration mutates indexes, hits update
MRU, misses call `GameProfileRepository.findProfileByName`, then addInternal/save
can synchronously write `usercache.json`. This is neither immutable context nor
a bounded scoreboard primitive; a concurrent map does not remove I/O. The actual
cache-miss witness writes the file before the method returns with a controlled
repository, requiring no account. Offline test mode is not a proof that this
path performs no lookup/I/O.

**Decision needed:** an explicit pre-FULL profile-resolution/persistence receipt
with approved timing and failure semantics, or a change to the synchronous FULL
boundary. Moving lookup/cache/save before an earlier Team callback can perform
effects that vanilla would never reach if that callback fails; moving save later
changes return visibility. A service turn held over I/O under R violates §6.
Proving that the exact post-DFU inputs exclude every legacy string Owner is an
alternative only with an enforced source-backed invariant; no such invariant
was established here. Do not rewrite Owner tags or disable ownership restoration.

**D3 — in-load entity conversion and a potential self-FULL dependency.**
`Tadpole.readAdditionalSaveData` passes unbounded saved Age to `setAge`; Age at
`ticksToBeFrog` invokes `ageUp` immediately. `Mob.convertTo` creates the frog,
runs `ConversionType.SINGLE` (including team/effect/reference operations), then
the original conversion callback requests
`ServerLevel.getCurrentDifficultyAt(frog.blockPosition())`. That method calls
`getChunk(FULL,false)` before frog finalization, collision-fudge, sound, nested
addFreshEntity and source discard. “false” allows an in-flight FULL future and
managedBlock. At the source chunk this may be the same FULL not yet published;
requesting it as a prerequisite can create a cycle, and the guard cannot simply
be suppressed after the conversion prefix has committed.

**Decision needed:** establish and enforce an input/lifecycle invariant that makes
these queries already-ready, or approve exact in-progress same-stage read access
(including which partially converted LevelChunk fields are visible), or amend
stage atomicity for this nested conversion. No staged-read capability exists in
the accepted contract. Returning fabricated difficulty/chunks, clamping Age,
deferring conversion, or silently treating the accepted reader input as corrupt
would change vanilla behavior. Witness 9 proves the synchronous callback edge;
the full self-dependency is a source-based risk conditional on the claimed input,
not a claimed live deadlock reproduction. The failed `/forceload` cleanup probe
is separate and is not used as proof of this Tadpole branch.

### Partial failure and publication are separate outcomes

Record at least `(nested operation result, committed prefix, caller caught?,
supplier result, publication result)`. A team/raid/boss/waypoint primitive may
commit, a send/callback may throw, `Entity.load` may wrap it and
`EntityType.loadStaticEntity` may catch/log RuntimeException and return empty;
FULL may then return the original LevelChunk successfully. BlockEntity.loadStatic
has its own Throwable catch/null result. Preserve both genuine policies.

The primitive's state and accepted sends remain committed even when its nested
result is failure. Mark the unexecuted notification suffix failed-unsent (or
uncertain at an ambiguous handoff); never roll back, replay the supplier, resend
accepted records or mark the nested operation successful just because FULL
returned. Conversely, a caught vanilla exception alone is not a failed FULL
stage. Publication may proceed only if the complete supplier succeeded and its
separate publication claim passes. A real Phoenium authority breach additionally
fences admission out of band, so a vanilla catch cannot turn it into accepted
FULL publication. PostLoad is cleared only after normal return; nonnull after
failure is not permission to retry its committed prefix.

**Next task:** decide D1–D3 and close their specific witnesses/contracts. Only
after closure is complete, implement the integrated original FULL supplier,
service/connection binding, extended holder/work lifetime and separate FULL
publication together. No isolated scoreboard implementation. Keep server-only,
workers=1, parallel=OFF, R-CHUNK-01/Stage 3=PARTIAL, Stage 4=NOT STARTED.

## Historical review and preservation context

Date: 2026-09-04. Starting checkout: `62b52b4d1f9d0a0f8b3dcd0f2f198f43b937324f`.

**The requested FULL checkpoint is not implemented or accepted.** The existing
PARSED_APPLY and separate STATUS_PUBLISH(EMPTY) binding remains in place. This
change adds a reproducible witness for a concrete FULL ownership question,
repairs a POI bookkeeping race encountered in real-server regression, and repairs
the automated fixture. FULL_CONVERT, STATUS_PUBLISH(FULL), and later
ticking/readiness/send/demotion authority remain unbound. Stage 4 is not started.

## Focused source finding requiring a contract decision

The pinned Minecraft 26.2 synchronous FULL supplier performs construction or
reuse, holder replacement, full-status supplier installation, post-load work,
loaded-flag mutation, block-entity and tick-container registration, and unsaved
listener installation. The accepted architecture treats that body as one stage.
The separately registered holder completion callback is the publication edge.

A legal post-load branch reaches a server-wide mutation and network dispatch:

```
ChunkStatusTasks.lambda$full$0
  LevelChunk.runPostLoad
    ChunkStatusTasks.lambda$full$1 / postLoadProtoChunk
      EntityType.loadEntitiesRecursive (including passenger trees)
        Entity.load -> LivingEntity.readAdditionalSaveData
          LivingEntity.lambda$readAdditionalSaveData$0 (saved Team)
            ServerScoreboard.addPlayerToTeam
              Scoreboard.addPlayerToTeam / previous-team removal
              PlayerList.broadcastAll
                ServerGamePacketListenerImpl.send (players in every dimension)
```

This is synchronous during the original post-load body. A restored UUID can
already belong to another team: the old membership removal and REMOVE broadcasts
precede the new membership addition and ADD broadcasts. Rejecting at packet send
is too late to provide atomic preflight; the scoreboard has already changed.

`FullPostLoadScoreboardContractTest` contains three passing witnesses:

1. The original restoration callback, original ServerScoreboard mutation and
   original PlayerList broadcast loop synchronously reach Overworld and Nether
   recipients from an admitted FULL-shaped pending stage.
2. Previous-team removal and saved-team addition preserve REMOVE-before-ADD
   order across both recipients.
3. An exact exception injected at the final send leaves the earlier membership
   mutation applied, disproving a late-rejection rollback assumption.

These are semantic witnesses, **not FULL adapter acceptance tests**. Constructorless
test environments supply vanilla getters and controlled network sinks. They do
not run a complete LevelChunk conversion or a real network team broadcast.
The exact call path above is supported separately by pinned bytecode.

The region lease is world-bound; extending a chunk's regional footprint cannot
grant authority over a server scoreboard and connections in other dimensions.
Architecture section 9 declares finite metadata services, and section 10 forbids
using a chunk lease to authorize mutable foreign-player access. Section 17 says:

> changing identity, service ownership, future settlement, stage atomicity or lease lifetime requires architecture review

### Proposed review direction (not approved or implemented)

Declare a narrowly scoped server-wide service for saved-entity team restoration
and its ordered outbound team notifications. Specify that it owns scoreboard
membership and this dispatch operation, independently of regional chunk authority.
The serial control lane must preserve the original return/failure behavior and
REMOVE/ADD order. Any service reservation or prerequisite must be ready before
FULL entry; no region may wait for a service or another region while holding its
lease. The service must not become a general arbitrary-world callback executor.

The implementation must demonstrate the complete conversion branch, passenger
restoration, failure ordering, current service evidence, and actual outbound
notification behavior before acceptance. Merely adding a lock, skipping Team,
moving dispatch after FULL returns, or granting the center lease global authority
does not resolve the question. Approval of this direction would not itself prove
that the entire FULL footprint is closed.

A question requesting this specific service-ownership decision was sent during
the task and remained unanswered when this report was prepared. No such change
has been silently added to the architecture.

## Implemented regression repairs

### POI pending-load metadata

A fresh server reproduced `ArrayIndexOutOfBoundsException: Index -1 out of bounds
for length 257` during the adapter's pending-load removal while vanilla worker
prefetch could insert/rehash the same fastutil map. The baseline adapter accessed
the shared pending map and loaded set without vanilla's `SectionStorage.loadLock`.

`PoiLoadBookkeeping` now uses that existing monitor for bounded source-identity
checks, pending removal and loaded-set acknowledgement. Original POI installation
still executes outside the monitor under the stage lease. No callback or future
completion is invoked by the bridge. Three tests require the exact monitor for
every map/set access, prohibit premature acknowledgement and preserve replacement
source identity. The broad-lock source test has one named exception for this
bridge, with the restriction retained for every other production source file.

### Finite external-server fixture

The old explicit A/B fixture covered only a small region near the origin, while
actual vanilla spawn selection and client generation requested chunks outside
it. This caused private cancellations and startup waits. The harness now opts
into an explicit support map around the actual sampler-selected spawn candidate
and the test corridor. The support radius is 22 chunks: spawn search 5, client
view 5 plus its border 1, generation dependencies 11. A deterministic test checks
the pinned FULL-to-EMPTY dependency radius, the declared corners, the A/B boundary
and absence of a world-default fallback. Ordinary fixture callers retain their
original small geometry.

The real-client target test no longer infers index insertion from the aggregate
entity count rising; unrelated entities can unload/despawn concurrently. It checks
the tagged target position and requires the subsequent real interaction's index
hit and complete admitted execution path. The external test server uses 2 GiB
maximum heap and emits the existing chunk-binding trace.

An intermediate client run with insufficient support retained 68 custody entries
after 34 private cancellations. Widening the declared fixture avoids those missing
routes in the acceptance runs; this is **not** a general repair or acceptance of
missing-route cancellation/retention behavior. That baseline terminal path remains
a separately identified issue.

## Validation and artifact identity

The starting clean build passed **196 tests** with zero failures, errors or skips.
The updated deterministic suite has **203 tests**: the original 196, three FULL
semantic witnesses, three POI bookkeeping tests and one fixture support test.

Final command: `gradlew.bat clean build runStage3ChunkTrace runStage3ClientGameTest --no-daemon`.
It completed successfully. The final XML aggregate is **203 passed, 0 failures,
0 errors, 0 skipped**. These are deterministic tests; the five real-server
lifecycles below are additional evidence, not added to the test count.

| Final-artifact lifecycle | Seed | Exact EMPTY captures / parsed applications / EMPTY publications |
| --- | --- | --- |
| Fresh generation 1 | -7666213150661820950 | 1,058 / 1,058 / 1,058 |
| Fresh generation 2 | -2096787328781571592 | 1,058 / 1,058 / 1,058 |
| Persisted reload of fresh 1 | -7666213150661820950 | 9 / 9 / 9 |
| Real client, lifecycle 1 | 5166915986533674833 | 2,046 / 2,046 / 2,046 |
| Real client, lifecycle 2 | 3203045256913256104 | 1,862 / 1,862 / 1,862 |

All five servers stopped normally. Every shutdown reported zero recovery
applications, private unloads, stale reroutes, duplicate applications, barriers,
and all reported chunk/service retention counters. The total is 6,033 parsed
applications and separately admitted EMPTY publications, not FULL admissions.
All nine persisted applications were marked `reload=true` and registered their
light input batches. This run exercises normal success and shutdown; it does not
newly establish real supersession or exceptional-load correctness.

The client JSON reports real TCP connection and usable world, same-owner
movement and indexed interaction, cross-owner movement and interaction rejection,
target removal, disconnect projection cleanup, second-lifecycle reinitialization,
and normal termination. All corresponding flags are true, with an empty failure
field. It is a usability/packet regression, not proof of FULL/ticking/send authority.
Some older descriptive fields in the existing chunk trace JSON remain observer
metadata; the authoritative EMPTY measurements above come from binding STOPPED
counters in the saved production server log.

Production artifact: `build/libs/phoenium-0.2.0-stage2.jar`.
SHA-256: `CB2BCC215BEA6B318E3B4D0B057A4073051663DEFD14A92FE5C093DA42DC0D69`.
The actual JAR installed in the external server's `mods/` has the same hash.
The starting commit remains HEAD; these source/test/document changes are uncommitted.

Pinned merged-deobfuscated Minecraft JAR SHA-256:
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.

Final evidence is copied to `run/full-conversion-audit/final/`: `server-process.log`
contains the five STOPPED summaries, `STAGE3-CHUNK-RUNTIME.json` and `STAGE3-CLIENT.json`
contain the two harness outcomes, per-run logs and world seeds accompany them,
`test-results/` contains all JUnit XML, and `evidence-hashes.json` identifies the
copied top-level evidence files. Earlier failed and intermediate passing runs are
kept separately and are not counted in the final-artifact totals.

Evidence is preserved under ignored `run/full-conversion-audit/`, including pinned
bytecode extracts, initial failed-run logs and thread dumps, and the successful
regression JSON/logs. The harness uses the offline test identity `PhoeniumTest0`,
localhost, a separate production server, one simulation worker and parallel
gameplay OFF. No personal account, launcher, desktop automation or client-side
production mod is introduced.

## Resume boundary

After the service-ownership decision, extend the existing registrar/custody graph:
retain actual holder lifecycle identity beyond EMPTY settlement; register FULL
work using the actual winning vanilla reservation; preflight the complete
synchronous footprint; claim the original conversion exactly once; capture the
exact returned R and adjacent holder handler; and admit FULL publication separately.
Fresh generation wraps the task future in `ChunkStep.apply` while reload can
return it directly, so do not assume the static FULL task's future is always the
holder's returned R. The Imposter branch and actual replacement hook must be
bound from the pinned code rather than inferred from prose.

These missing adapters and identity extensions are implementation work, not
additional architecture blockers. Tests still needed include actual FULL result
identity, stale-owner publication, wrong-lifecycle and duplicate publication,
supersession and terminal settlement, followed by fresh/reload/client evidence
with authoritative FULL counters. No existing EMPTY counters establish those
properties.
