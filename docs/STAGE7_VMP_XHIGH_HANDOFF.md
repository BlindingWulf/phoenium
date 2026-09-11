# Stage 7 VMP optimized-tracking handoff

Updated: 2026-09-10

## Status

- Provider: VMP `0.2.0+beta.7.236+26.2`
- Locked artifact: `run/stage6/frozen-audit-045834b/research/artifacts/vmp/vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar`
- SHA-256: `E1BAEB5E1A0948C9FC49415B93EBAAC4007355094B432EA151B3C8CCE7FFE678`
- Locked configuration keeps `use_optimized_entity_tracking=true` and `optimized_entity_tracking_use_staging_area=true`.
- VMP optimized nearby-entity tracking with Phoenium regional tracking: **BLOCKED / PARTIAL**.
- VMP is secondary/optional and is not a Stage 7 release blocker.
- No production compatibility patch is accepted from this investigation.

## First real failure

The historical Stage 6 W4 real-client run was not a trace-only defect. Immediately after
`PhoeniumTest0` disconnected, the server failed in `RegionalTrackingService.removeEntity` with:

`removed tracker retains a connection outside the live session cut`

The same exception later appeared under `Exception stopping the server`. The missing persisted
`server.stop.begin` / `server.stop` receipts therefore follow from the failed lifecycle path; they
are not sufficient evidence for a trace-writer defect.

Historical evidence:

- `run/stage6/frozen-audit-045834b/runs/vmp-client-w4/server/stage4-client-server-stage6-vmp-client-w4.log`

## Discriminating repair probe

An uncommitted probe added a fail-closed post-removal assertion that checked the real
`ChunkMap.TrackedEntity.seenBy` sets for the departing player. It compiled, passed
`RegionalTrackingAdapterContractTest` and `CoreAcceptanceTest`, and its candidate JAR passed the
locked VMP console W1/W4 fresh+restart runs.

The strict W4 real-client reproduction then failed with:

`removed player remains in an authorized tracker projection`

That failure occurred after the original `ChunkMap.removeEntity` body completed. It proves at
least one real `TrackedEntity.seenBy` set still contains the departing connection; this is not only
a stale Phoenium projection. The probe was removed and must not be recovered as a compatibility
fix.

Probe evidence:

- `run/stage7/vmp-repair-client-w4/server/stage4-client-server-stage7-vmp-repair-client-w4.log`
- console diagnostics: `run/stage7/vmp-repair-w1/` and `run/stage7/vmp-repair-w4/`

## Exact provider conflict

VMP class:

`com.ishland.vmp.mixins.playerwatching.optimize_nearby_entity_tracking_lookups.MixinThreadedAnvilChunkStorage`

targets `net.minecraft.server.level.ChunkMap` and makes these semantic replacements:

1. `ChunkMap.addEntity`'s `TrackedEntity.updatePlayers(List)` is redirected to
   `NearbyEntityTracking.addEntityTracker`.
2. The reverse tracker collections in `addEntity`, `removeEntity`, and `move` are replaced with
   empty collections for VMP's entity map.
3. `TrackedEntity.broadcastRemoved()` in `removeEntity` is redirected through
   `NearbyEntityTracking.removeEntityTracker`.
4. `ChunkMap.tick()` is an `@Overwrite` whose tracking body is
   `nearbyEntityTracking.tick(distanceManager)`.

Phoenium class:

`dev.phoenium.mixin.RegionalChunkMapTrackingMixin`

injects at `ChunkMap.tick()` HEAD, calls `RegionalTrackingService.tick(...)`, and cancels the
method while regional services are active. `RegionalTrackingService.tick` directly performs
owner-routed `TrackedEntity.updatePlayer(...)` calls and refreshes its projection from the real
`seenBy` set.

The result is a provider-authority split: VMP has replaced the native tracking engine with
`NearbyEntityTracking`, but Phoenium cancels that provider engine and independently mutates the
same real tracker relationships.

## VMP structures that become semantically relevant

`com.ishland.vmp.common.playerwatching.NearbyEntityTracking` owns:

- `AreaMap<TrackedEntity> areaMap`
- `Reference2ReferenceLinkedOpenHashMap<ServerPlayer, ReferenceLinkedOpenHashSet<TrackedEntity>> playerTrackers`
- `Reference2LongOpenHashMap<TrackedEntity> tracker2ChunkPos`
- `ObjectLinkedOpenHashSet<StagedTracker> stagingTrackers`
- `ReferenceLinkedOpenHashSet<TrackedEntity> trackerTickList`

With the locked staging option enabled, `addEntityTracker` initially places a tracker in
`stagingTrackers`. `tickStaging` promotes it to `areaMap` after 200 provider ticks. The main
`tick0` updates tracker positions, reconciles each player's `playerTrackers` set against the
area-map query, creates/removes `TrackedEntity` relationships, and advances tracker positions.

`NearbyEntityTracking.removePlayer(ServerPlayer)` removes the player from every staged tracker,
then from every tracker in that player's provider-owned `playerTrackers` set. If Phoenium creates
or retains a real `seenBy` relationship outside those provider-owned sets, VMP has no index entry
from which to remove it at disconnect. That is consistent with the reproduced real-client leak.

### Sibling optimized-tracking state that is also authoritative

The fresh current-HEAD locked-JAR review on 2026-09-11 expanded the provider surface that a
future adapter must preserve. The primary `MixinThreadedAnvilChunkStorage` overwrite is not the
complete tracking engine.

- `optimize_nearby_entity_tracking_lookups.MixinThreadedAnvilChunkStorageEntityTracker` retains
  a provider-side `Set<ServerPlayerConnection> seenBy`, implements `updateListeners(...)`,
  `tryTick()` and provider start-tracking/range behavior. Disconnect/removal must settle this
  connection-keyed state as well as the native `TrackedEntity.seenBy` relationship.
- `optimize_nearby_entity_tracking_lookups.MixinEntityTrackerEntry` owns provider data-sync cadence
  through `vmp$tickAlways()` / `vmp$syncEntityData()` and tick/update/teleport counters.
- `optimize_nearby_entity_tracking_lookups.MixinServerPlayerEntity` owns previous-position state
  used by provider movement detection.
- the primary storage mixin also participates in `playerMap` and movement-time tracker selection;
  those paths are part of player/range transitions, not incidental caches.
- `optimize_nearby_player_lookups` has sibling consumers in `MixinServerWorld`, `MixinMobEntity`
  and `MixinSpawnDensityCapper`. They use nearby-player state for nearest-player queries, mob
  targeting and local spawn-density caps. An adapter that changes area-map/player-index semantics
  must keep those consumers coherent.

These structures are provider-owned derived indexes/scheduling state, not a new Phoenium ownership
authority. A future adapter must route their transitions through existing Phoenium owner/session
facts while preserving the provider's lifecycle and fail closed if the locked provider shape drifts.

## Why the small repairs are rejected

Refreshing or dropping Phoenium's cached listener set is invalid because the real `seenBy`
relationship survives.

Injecting Phoenium-created relationships into VMP's private `playerTrackers` map would address one
disconnect symptom while leaving VMP's overwritten tick engine, staging lifetime, area-map moves,
tracker tick list, and provider position bookkeeping bypassed. It cannot support an overall
compatibility claim.

Wrapping only VMP's direct `removePlayer` calls is also insufficient. With staging enabled,
provider removal walks all staged trackers. Those trackers may belong to distinct Phoenium owners.
Phoenium cannot synchronously run those mutations from the departing player's owner through an
owner-to-owner wait. Pre-converging every staged tracker into one closure can collapse an
unbounded/high-frequency tracking set into one owner, which violates the regional architecture
and the Stage 7 compatibility rules.

## Architecture work required before an adapter can be accepted

A future XHigh/Astra investigation must choose one authoritative tracking protocol and preserve
VMP's intended optimized semantics without adding a second ownership system. The first design
question is how VMP's area-map/player-index/staging transitions can be admitted as finite,
owner-routed Phoenium operations while preserving provider lifecycle ordering.

At minimum, an acceptable design must cover:

- VMP tracker insertion and staged promotion;
- player-index additions/removals;
- tracker movement in the provider area map;
- position-update and server-entity tick behavior;
- disconnect removal including staged trackers;
- entity removal and `broadcastRemoved` ordering;
- movement and range transitions;
- topology migration during retained provider relationships;
- failure propagation and stop settlement.

It must use current Phoenium ownership/incarnation/session receipts, keep VMP's optimized feature
enabled, avoid blocking across owners, avoid a world-wide tracker closure, and fail closed on
provider-shape drift.

### Future lifecycle contract and acceptance matrix

| Lifecycle row | Provider surface that must remain coherent | Required future contract | Minimum acceptance evidence |
| --- | --- | --- | --- |
| tracker staging and promotion | `stagingTrackers`, `tickStaging`, `tracker2ChunkPos`, `AreaMap` | admit staging/promotion as finite owner-routed transitions; preserve provider order and lifetime without global closure | deterministic staging/promotion fixture plus locked-provider runtime trace |
| player indexes | `playerTrackers`, `playerMap`, connection-keyed tracker `seenBy` | one authoritative relationship transition updates every provider/native projection exactly once under the retained live session | add/remove/reconnect fixture proving no missing or duplicate listener/index entry |
| area-map movement | `AreaMap.update`, provider previous-position state, movement tracker selection | route position/range change from the current entity/player owner; stale owner/incarnation work reroutes or rejects without cross-owner waits | crossing/range test with deterministic add/remove ordering and no stale cell membership |
| server-entity tick/data sync | `trackerTickList`, `tryTick`, `vmp$tickAlways`, `vmp$syncEntityData` | preserve provider tick/data cadence while relationship mutation stays owner/session validated | native/provider comparator for position/data updates over a bounded movement script |
| player disconnect | `NearbyEntityTracking.removePlayer`, staged trackers, `playerTrackers`, connection `seenBy` | retire the departing session from native and provider indexes exactly once; staged trackers must settle without gathering all owners into one closure | real-client disconnect/reconnect with zero retained departed connection and no session-cut failure |
| staged/entity removal | `removeEntityTracker`, `stagingTrackers`, `areaMap`, tick list | removal invalidates later staged/move/tick work and settles every provider index exactly once | removal-before-promotion and removal-after-promotion fixtures plus real runtime trace |
| `broadcastRemoved` / stop-tracking | redirected `TrackedEntity.broadcastRemoved`, listener/data-sync state | preserve provider/native stop-tracking order and prevent replay after relation retirement | strict ordered event comparator including packet/listener removal |
| range and topology change | area-map queries, provider view distance, player lookup consumers, Phoenium region publication | retained relationships migrate using existing directory/object epochs; provider nearby-player consumers observe one coherent published state | boundary-crossing/topology test plus nearest-player/mob-cap consistency checks |
| failure and server stop | all staged/index/tick state plus Phoenium session/ownership receipts | close admission, settle or invalidate outstanding provider transitions, then prove no retained connection/index state | injected failure plus normal stop with outstanding staged/tracking work and clean terminal receipts |

The first implementation artifact should be the strict native/provider tracking comparator used by
these rows. Building that comparator before a new live run prevents a disconnect-only symptom fix
from being mistaken for engine compatibility.

## Shortest reproduction

The strict real-client W4 path is the discriminating reproduction. Reuse the locked Stage 6 VMP
artifact/config and the Stage 4 client harness. The expected failure is the disconnect lifecycle,
not the earlier console-only W1/W4 result.

Before any new live run, reread `docs/AGENT_MINECRAFT_TESTING.md` and use a unique evidence
directory.

## Acceptance gate if architecture work resumes

Do not remove this BLOCKED status until the locked VMP optimized tracker first has the strict
native/provider comparator above, then passes a real-client disconnect/reconnect lifecycle with no
retained native or provider connection listener, followed by W1/W4 fresh+restart lifecycle and the
strict comparator on the accepted adapter. A bounded multi-player tracking sample should follow if
cheap. Console startup/save/restart alone is not sufficient.
