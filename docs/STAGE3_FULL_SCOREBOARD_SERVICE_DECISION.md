# FULL scoreboard service decision

2026-09-04. **APPROVE WITH A PRECISE, BOUNDED AMENDMENT (AMENDED).**
Approve an explicitly admitted, synchronous scoreboard service operation with
ordered connection submission. Do not defer the membership result or the original
server-side send calls into a later service task. Actual transport completion
remains asynchronous, as in vanilla. This resolves the scoreboard proposal;
**complete FULL implementation readiness is NO** for the concrete callback
dependencies in section 6. No production FULL binding is enabled by this review.

This narrowly amends architecture sections 6, 9 and 10 for the existing Stage 3
serial baseline. It does not authorize a second scoreboard, general Stage 4
services, a universal server-thread authority, parallel gameplay, or arbitrary
callback execution. FULL remains one synchronous continuation, followed by its
separately admitted holder publication. The next implementation must integrate
the service with that continuation, not build an interface-only service first.

## 1. Preserved baseline and source evidence

Starting HEAD: `62b52b4d1f9d0a0f8b3dcd0f2f198f43b937324f`; seven tracked
modifications and four untracked files, exactly as recorded in
[preservation review](STAGE3_FULL_REVIEW_PRESERVATION.md). Reviewed repairs,
three original witnesses and historical evidence were committed first as
`4d4e804` (`fix: preserve validated POI and fixture repairs with FULL witnesses`).
No architecture implementation preceded that commit.

Rebuilt the production sources and client test source set with
`gradlew.bat build compileClientGameTestJava --rerun-tasks --no-daemon`:
203/203 passed at preservation; **206/206 passed after three review tests**,
zero failures/errors/skips, BUILD SUCCESSFUL. No live client was run in this
review. The five previous server lifecycles remain historical evidence.
Both rebuilds retain the reported production JAR SHA-256
`CB2BCC215BEA6B318E3B4D0B057A4073051663DEFD14A92FE5C093DA42DC0D69`;
the installed external-server JAR matches. There is no artifact discrepancy.

Pinned Minecraft 26.2 merged-deobfuscated JAR was rehashed and the relevant
methods freshly disassembled; SHA-256
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.
Reproducible extracts and build logs are ignored under
`run/scoreboard-service-review/`. Method names and bytecode offsets below refer
to this artifact, not another Minecraft release.

| Exact source seam | Required semantics |
| --- | --- |
| `ChunkStatusTasks.lambda$full$0`, offsets 34–119 | Construct/reuse, replace proto (new branch only), status supplier, runPostLoad at 87, loaded flag, block entities, ticks, unsaved listener, return actual chunk. |
| `$full$1 -> postLoadProtoChunk -> EntityType.loadEntitiesRecursive` | Stream consumption synchronously creates/loads root and passenger entities; this is not detached parsing. Reload instead uses `SerializableChunkData.lambda$postLoadChunk$0`, legacy entity insertion and block-entity NBT loading. |
| `Entity.load` at 598; `LivingEntity.readAdditionalSaveData` at 193–208 | Virtual additional-data reader invokes saved Team callback synchronously. Subsequent fields, subclass readers, entity insertion and passenger handling follow it. |
| `LivingEntity.lambda$readAdditionalSaveData$0`, 10–54 | Resolve team, add string UUID, **use boolean result** to decide warning. Missing team performs no mutation. Callback returns void. |
| `Scoreboard.addPlayerToTeam`, 0–36 | If any prior team exists, invoke virtual removal first (even the same team); put member-to-team mapping, then add to the team's collection and return its boolean. |
| `ServerScoreboard.addPlayerToTeam`, 3–39; `removePlayerFromTeam`, 3–33 | Base mutation; construct ADD/REMOVE packet; broadcast; updatePlayerWaypoint; setDirty; return. ADD notifications/dirty update only when base returns true. |
| `PlayerList.broadcastAll(Packet)`, 0–40 | Iterate all players, in list order, call each listener's send; no dimension filter. |
| `ServerCommonPacketListenerImpl.send(Packet, listener)`, 36–92 | Calls actual Connection.send synchronously; wraps thrown failure in ReportedException. Connection uses its pending-action queue or channel event loop; return is not network acknowledgment. |

The three original `FullPostLoadScoreboardContractTest` witnesses are retained.
They observe Overworld REMOVE, Nether REMOVE, Overworld ADD, Nether ADD, and
membership already changed when a controlled send throws. Two added witnesses
prove same-team REMOVE/ADD with true return, absent-team/absent-member behavior,
and a failure during REMOVE leaving neither membership nor a new dirty flag.
These use real methods with controlled sinks; they are not FULL or real-client
acceptance. Their `getPlayerByName` stub returns null and does not prove waypoint
behavior. Commands `TeamCommand.joinTeam/leaveTeam` discard individual booleans,
but that does not license changing the methods' return contracts.

## 2. Authority, admission and synchronous visibility

**Authority:** one server-session scoreboard coordinator owns access to the
existing `ServerScoreboard` and its team graph. It grants a finite operation
turn, identified by session and operation ID. A FULL turn is bound to the actual
lifecycle, WorkId and claimed FULL StageId. Regional authority owns the entity;
the explicit turn independently authorizes scoreboard access. The physical
thread, an entity UUID, or the region lease alone supplies no service authority.

Reserve the turn, bounded record capacity and connection-directory version
before claiming FULL, with no region lease held. If unavailable, park the typed
stage at its existing pre-supplier boundary. Validate/pin the reservation with
the stage claim, then invoke the original whole supplier once. Do not first
mutate team membership during preflight. A competing FULL remains unclaimed;
two regions never wait on one another's lease or block inside a scoreboard call.
Release an unused reservation on stale delivery and reserve again for the same
StageId under its current route; a claimed stage pins its turn through exit.
Turn grants/releases and journal/directory publication have explicit
release/acquire synchronization on coordinator metadata; an ordinary boolean
or an assumed thread identity is not a publication protocol.

This is an exclusive **service scheduling reservation**, not a monitor held
around FULL. It temporarily excludes other scoreboard operations while the
original supplier runs. No custody/topology/index gate is held during vanilla
execution. Actual scoreboard-state critical sections cover only primitive
state access and journal publication. They end before send calls, waypoint or
other player callbacks, entity work, executor submission or future completion.
The coordinator must never take an owner lease or pump another owner. Service
reservation metadata is handled before custody admission; stage claim consumes
an already-ready immutable reservation, so there is no reverse lock acquisition.

All legacy serial phases that touch protected state participate in this same
turn discipline. Commands, save snapshots, login snapshots, existing serial
level ticks and other synchronous readers acquire their turn before their
enclosing execution/owner claim. The serial scheduler may reserve it
conservatively for a whole such phase. This preserves the present one-worker
baseline; it is deliberately not a scalable multiworker gameplay design. A
caller cannot encounter a busy service after beginning a synchronous regional
body. Guarding only restoration while ordinary accesses bypass it is invalid.

**Return contract:** team membership must be updated before restoration returns.
The saved callback uses the boolean for logging. No immediate membership test
was found in the following base LivingEntity field assignments, but subsequent
virtual readers, registration/waypoint work and ordinary callers can observe
membership. They see each original committed prefix and final state, never a
queued intention. Reentrant *known* vanilla scoreboard calls use the same turn
and preserve original nesting; arbitrary callback execution is not admitted.

**Deferral:** network delivery/flush may finish later. Moving invocation of
`send` itself after restoration is not approved: synchronous send exceptions
currently prevent later waypoint/dirty work and sometimes the ADD. An outbox
alone would change that behavior. The chosen contract needs no asynchronous
membership service execution and no new continuation inside entity loading.
If an implementation requires that, its legal parking point is before the
entire original FULL supplier (and before earlier mutations there), not inside
`Optional.ifPresent`, Entity.load, passenger recursion or runPostLoad.

**Boundedness and callbacks:** one membership primitive is a fixed number of
map/set operations with normal amortized container costs, not a player scan.
Team removal and login/save snapshots are finite but scale with team/scoreboard
size; they need their own admitted turn and capacity reservation. Prepare
immutable copies outside a short metadata lock while the service turn excludes
writers. No arbitrary size cap may silently reject legitimate team semantics.
Capacity pressure parks before mutation. Unknown overridden readers, collection
implementations, `onTeam*`, `setDirty`, entity readers and Fabric callbacks are
not proven safe by this audit; exact transformed call-site coverage must reject
an unsupported closure before FULL entry, not omit its effects.

## 3. Required participating access surface

| Protected state / accesses | Participation rule |
| --- | --- |
| `Scoreboard.teamsByName`, `teamsByPlayer`; `PlayerTeam.players` | Saved restore; add/remove membership overloads; add/remove team; loadPlayerTeam; entityRemoved/dead-entity cleanup all use the same operation authority. Both membership indexes remain vanilla's single authority. |
| Team properties and component values; getPlayerTeam/getPlayersTeam/getPlayerTeams/getTeamNames/getPlayers, pack and property getters | Every live read, mutation, escaped collection iteration and component edit participates. Getter locking alone is insufficient: vanilla returns live teams/collections. Private use stays inside the turn; outside consumers receive immutable versioned data, not those views. Membership-dependent gameplay still requires its own region authority. |
| `PlayerTeam` setters -> `ServerScoreboard.onTeamAdded/Changed/Removed` | Capture create/modify/remove-team notifications and preserve waypoint/dirty order. Removing a team removes its name and reverse mappings; vanilla leaves the removed team's member collection available for waypoint callbacks. Do not clear it or invent member REMOVE packets. |
| `ServerScoreboard.dirty`, load/store/pack, `ScoreboardSaveData.setData` | All dirty writers (including scores/objectives) and the dirty test/reset + snapshot participate; otherwise a membership dirty update can be lost. Preserve setDirty's original call position and false-return path. Freeze persistence values during the turn, perform storage I/O later; no second authoritative scoreboard or unconditional dirty repair. |
| Initial scoreboard sync | `PlayerList.updateEntireScoreboard` captures team definitions/members and objective/display/score initialization coherently; therefore those initialization readers and conflicting writers share snapshot ordering. No general score-operation redesign is approved. |
| Player/session directory and listener replacement | Place/remove/respawn/reconfiguration and any actual listener/Connection replacement publish connection metadata through the existing serial connection lifecycle, with the handshake in section 5. The entity projection is not this directory. |
| `updatePlayerWaypoint`, `updateTeamWaypoints` | Name resolution uses safely published name/session metadata with vanilla case-insensitive matching. Preserve callbacks after broadcast, before dirty, outside state critical sections, under their separate authority. They are not authorized by a scoreboard turn to read foreign players. |

The direct restored UUID normally names no online player, but the old witnesses
do not establish that fact. Preflight must establish the exact null-lookup
branch from a pinned directory, or obtain the separately valid waypoint
authority. Never replace a legitimate player match with null. Ordinary team
changes/removal still run their genuine waypoint branches in their existing
serial execution phase; their participation above prevents scoreboard races
without claiming regionalized waypoint implementation.

## 4. Mutation, notification order and partial failure

Do not make REMOVE+ADD a rollback transaction. For a prior membership:

1. Original removal changes both membership indexes. Record this committed
   prefix and its next ordered notification slot before releasing state access.
2. Construct the REMOVE at its original semantic position, freeze its values,
   publish the record, and synchronously submit it to each recipient in the
   captured vanilla order, outside the state critical section. Then execute
   original waypoint work and dirty notification.
3. Only after removal returns, perform original mapping put and member-set add;
   retain its exact boolean. If true, construct/publish/submit ADD, then waypoint
   and dirty, in original order. Same-team follows the same steps.

State writes commit when the corresponding original map/set effects occur;
there is no all-or-nothing transaction over arbitrary failing virtual methods.
Successful primitive state plus its preallocated journal descriptor is published
under the service's state protocol before another observer can enter. A failure
inside a primitive records the exact reached prefix, including a mapping put
before a failing collection add; it must not advertise completed membership.
Packet-construction failure after a state write is a real failed prefix, not
permission to roll back or to synthesize a packet. Slots are allocated before
entry, with progress/terminal state, so later allocations are not required to
remember the effects already reached.

Order is explicit: **mutation REMOVE before ADD; construction REMOVE before
ADD; journal publication REMOVE before ADD; per-session submission REMOVE
before ADD**. Across recipients, retain the witnessed all-REMOVE-recipient
loop before the all-ADD-recipient loop. There is no assertion about which of
two different clients physically receives bytes first. Each surviving session
observes its own FIFO order; later team deletion/recreation/property updates
cannot overtake this operation. Empty/missing-team operations generate exactly
the notifications vanilla generates, including none for absent saved team.

Records contain service sequence, operation/occurrence ID, immutable member
string, team name and frozen packet values, plus recipient **session** IDs and
progress. A membership packet already snapshots name/action/member strings in
the pinned code. Team-definition `Parameters` and `PlayerTeam.pack` retain
Component references, so merely retaining those vanilla objects is insufficient:
deep-freeze their serialized value or an immutable detached value graph while
authorized. Never inspect live entity/team/player objects later to reconstruct
an old notification. Exact connection handles belong privately to connection
authority, not to a retained gameplay object closure.

The finite in-memory journal remains owned by the service after mutation until
every recipient slot is accepted or explicitly terminal. Wake-up loss cannot
lose the record: the dispatcher consumes the authoritative ordered journal.
Normal dispatch is synchronous at the original broadcast edge. Use one serialized
publisher and per-session sequence/cursor; no later record passes a pending
earlier record. Accepted handoff to the existing Connection send boundary is
recorded once. A controlled failure before acceptance can be classified; a throw
after an ambiguous transport handoff is **uncertain**, never retried blindly.
Exactly-once guarantees cover the StageId mutation claim, journal insertion and
successful controlled enqueue, not socket delivery or crash recovery.

| Event | Required settlement |
| --- | --- |
| FULL stale before restore | No claim, mutation or batch; reroute unchanged StageId if valid; actual lifecycle retirement uses its existing private terminal path. |
| Restoration committed; later entity/post-load work fails | Preserve committed state/accepted notifications. Continue original exception handling; never replay restoration or the FULL prefix. |
| REMOVE submitted; ADD construction/insertion/submission fails | Keep removal and every reached ADD effect. Preserve recipient prefix, failure cause and dirty/waypoint positions; remaining suffix is failed-unsent, not a successful dropped broadcast. No rollback or retry of accepted entries. |
| Original send throws | Stop that vanilla broadcast at the same point and propagate its original failure policy. Earlier recipients keep their accepted sends; later recipients remain unsent. A failed operation is not guaranteed to converge every connected client. |
| Entity.load catches a failure | It wraps Throwable in ReportedException; EntityType.loadStaticEntity catches RuntimeException, logs and returns empty. Thus FULL may continue after a failed entity load while team effects remain. Do not falsely claim every restoration failure fails FULL. A Phoenium authority breach must additionally fence admission out-of-band so this catch cannot turn it into an accepted successful FULL. |
| Shutdown with records | Fence new turns/claims. An already claimed synchronous stage retains its existing service/connection reservation until exit; settle its reached records in order. No new gameplay callback on the shutdown caller. Closed recipients become terminal; report unresolved/uncertain handoffs, release record payloads after acknowledgments. No restart replay journal. |
| Recipient disconnects | Close that exact session capability. Already accepted transport data may be lost; unsent slots become terminal-disconnected. Never redirect them to a new session. |
| Duplicate FULL/stage delivery | Observe existing claim/outcome and notification occurrence IDs. Zero repeated mutations, batches or accepted sends, including after later failure. Keep bounded tombstones until all registered deliveries settle, then release. |

Service completion means the synchronous operation returned/threw with its
submission outcomes recorded. FULL completion means the entire supplier returned
its original chunk or its original error edge ran. FULL-holder publication is a
later separately admitted stage and cannot be inferred from either event.

## 5. Connection directory and initialization ordering

The existing contracts declare connection authority and typed notifications
(architecture section 10; packet-ingress connection lifecycle policy). The
current code does **not** provide an immutable outbound recipient directory:
vanilla PlayerList uses an ArrayList, exposes live players, and
`PlayerListLifecycleMixin` only updates the owned-entity projection at placement
TAIL/removal HEAD. `RegionDirectory` snapshots routes, not connections. Do not
reuse any of these as a safely published recipient snapshot.

Bind a narrow snapshot to that existing PlayerList/connection lifecycle, not a
new generic networking framework. Its serial owner publishes an immutable list
through an AtomicReference/volatile reference (release/acquire), containing
server-session + monotonic connection-session ID, immutable profile name and
opaque exact listener/Connection capability. Publish at actual registration,
closure and replacement edges; consuming code reads that snapshot once under
its admitted turn. Async close changes a connection-owned atomic closed state;
submission and close have one connection acceptance order. No mutable player
inspection or recipient region lease is needed for team packet submission.
Keep actual Connection.pendingActions/channel event-loop machinery and normal
send behavior; no parallel raw send path. Connection.send has mutable counters
and flush bookkeeping, so the existence of a concurrent pending queue alone
does not grant arbitrary concurrent callers permission.

Login needs a two-edge handshake. Pinned `placeNewPlayer` calls
`updateEntireScoreboard` at 454 **before** adding the player to `players` at 609
and `playersByUUID` at 624. Placement TAIL is therefore too late:

- At initial-sync entry, under a service turn, create INITIALIZING session state
  and take coherent scoreboard cut S. Freeze and synchronously submit the entire
  initialization sequence to that exact connection (team definitions/members,
  then original objectives/display/scores), with no state lock around sends.
- After releasing the turn, every update later than S is retained for that
  initializing session in sequence order. It must not slip between pieces of
  the snapshot or be lost in the pre-registration gap. Entries at/before S are
  covered by the snapshot and are not resent as deltas.
- At actual list registration, serialize activation with the same service:
  submit catch-up `(S, activationCut]` after initialization, then publish LIVE.
  Later updates follow it. If sync/placement fails, close/discard that session's
  pending work with explicit accounting; no unbounded initializing-session log.
  Admission capacity is reserved before snapshot; new update pressure cannot
  block a region midway through FULL.

An update racing the snapshot is wholly before S (included), or after S
(queued afterward). Team removal/recreation uses this same ordering. If a
connection begins a later full reinitialization, use a new sync generation/cut
on the same session and fence the previous delta stream before that snapshot.
Disconnect removes only the exact session, even if the same UUID has rejoined.
Reconnect gets a new session and a new snapshot, never old-session deltas.
Dimension change retains the same connection session and its team FIFO. Vanilla
respawn replaces ServerPlayer while reusing its listener (PlayerList.respawn,
offset 74); player-object replacement alone must not create a new connection
identity. An actual listener/Connection replacement does. These are required
bindings/tests, not claims that a directory class already exists.

## 6. Remaining synchronous FULL inventory

The following inventory covers the already visible chain, not every mod. An
existing architectural classification is not adapter acceptance. **Do not enable
FULL until these finite closure obligations are established for its input.**

| Operation | State / authority | Contract | Synchronous requirement / unresolved dependency |
| --- | --- | --- | --- |
| LevelChunk construction, proto replacement, status supplier | Chunk fields, actual holder lower-status slots; region + holder coordinator | Existing §§3/10 | Complete in supplier; retain Imposter branch (no replacement), exact returned R and adjacent holder edge. Constructor can set block entities with virtual setters; audit actual supported instances. |
| Entity constructors/load/readAdditionalSaveData, passenger recursion/startRiding | Entity graph, IDs, attributes/equipment/brain, riding callbacks; exclusive entity custody + complete regional footprint | Existing §§4/10 | Team is not last effect; EntityType stream processor can insert a root before passenger traversal finishes. Reposition/riding/type-specific overrides and constructor side effects are not all verified. Preflight root/passenger positions, aliases and callbacks; no replay to collect effects. |
| Saved Team and ordinary conflicting team accesses | Server scoreboard + connection state | This decision | Synchronous result and ordered send submission; exact waypoint lookup branch must be established. |
| Entity UUID/section/visible lookup insertion and level callback installation | PersistentEntitySectionManager known UUIDs, section storage, EntityLookup; regional records + world registry coordinator | Existing §9 finite registry contract | `addEntity(entity,false)` invokes onCreated; accessible/ticking visibility may immediately start tracking/ticking. Must preserve duplicate UUID outcome; no center-only assumption. |
| onCreated/onTrackingStart waypoint branch | Waypoint manager sets/table and receivers | Separate waypoint authority **unresolved** | trackWaypoint -> createConnection reads receivers; makeWaypointConnectionWith/connect can emit notifications. LivingEntity firstTick can short-circuit connection creation but does not remove manager insertion/receiver checks. Do not assume every entity has zero range or no receivers. |
| onTrackingStart -> ChunkMap.addEntity -> TrackedEntity.updatePlayers/updatePlayer -> ServerEntity.addPairing | Tracker maps, seenBy, player positions/view distances, entity pairing, debug subscriptions | Existing §10 tracking snapshot intent; exact synchronous pairing closure **unresolved** | Source shows immediate foreign-player reads and pairing calls when section accessible. Cannot label all entity insertion metadata-only or solve it by team-packet dispatch. |
| Other entity callbacks | navigatingMobs, dragonParts, entityTickList; dynamic game-event listener targets | Existing §9 shared registries + regional records | Preserve synchronous insertions; convergence for actual section targets. Type-specific listener/position-source effects and pairing overrides remain unverified. |
| Reload post-load block entities | keepPacked NBT or BlockEntity.loadStatic -> setBlockEntity; local map/old instance removal | Existing §§4/10 | Happens inside runPostLoad, not ticking readiness. Factories/readers/setRemoved can be virtual; actual supported block-entity closure still required. |
| registerAllBlockEntitiesAfterLevelLoad | EntityBlock.getListener, section listener registry, ServerLevel.onBlockEntityAdded, ticker lookup/registration | Regional section + named world ticker/debug registries | `EuclideanGameEventListenerRegistry.register` calls sendDebugInfo; `ServerLevel.onBlockEntityAdded` registers debug data. Debug subscriber dispatch and entity position-source lookup are **unresolved** when active. No permission to disable debug features. |
| registerTickContainerInLevel | LevelTicks allContainers/nextTickForContainer, onTickAdded callback installation | Existing §9 finite world registry service | addContainer publishes both containers now; callbacks execute on later tick insertion. Container ownership/index synchronization must be bound; no tick execution here. |
| setUnsavedListener | Chunk listener + ChunkMap.chunksToEagerlySave | Existing §9 metadata coordinator | If already unsaved, invokes setChunkUnsaved immediately. Finite set insertion, synchronous before return; do not treat as only future callback. |
| runPostLoad completion / FULL publication | postLoad nulling, flags, future/holder | Existing §§8/10/11 | postLoad is cleared only after successful callback return. A failure leaving it nonnull is not retry authorization. Publication must revalidate separately; future completion may run additional dependents. |

**Readiness NO is based on visible semantic dependencies, not missing adapter
classes.** Waypoint receiver work, immediate tracking/pairing, debug dispatch
and unverified concrete entity/block-entity overrides lack a closed synchronous
authority proof. Finish that bounded proof from these named seams before
claiming integrated FULL entry is legal. Existing finite registry adapters and
holder identity extension are implementation work under accepted contracts;
they are not new architecture blockers. Do not expand this review into general
tracking, spawning, optimization-mod compatibility or Stage 4 implementation.

## 7. Missing-route cancellation: OPEN WITH REPRODUCTION

Preserved original evidence:
`run/full-conversion-audit/client-first-failure-server.log`, first failing
terminal at line 24887 (chunk -13,-17, POI_INSTALL/EMPTY/0, CANCELLED), final
summary at line 25205: 1,873 registrations, 1,839 applications/publications,
34 private unloads, custodyEntries=68. Hash:
`8DC57A967508A235E7DA68FBA6483D4765B1E4D7D970613CEE3FE466FDFC9337`.
Companion `client-first-failure.json` hash:
`309DEB8F65B170877B3999A710747322A5E46C1392EA36F93754459B4C3B5DBC`.
These were neither overwritten nor reclassified as successful evidence.

The old support map failed to cover legitimate client/generation demand. That
is a **fixture coverage failure**, not supersession, and there was no registered
pending topology transition to make the route temporarily absent. The current
pump maps every absent route to CANCELLED. Under architecture §7, park only
behind a real registered topology transition with a resolution/terminal event;
a permanently unroutable active reservation is an explicit admission/barrier
error. It cannot invent unload for still-valid demand. An actually cancelled
request follows its authenticated terminal path. Never add a world fallback or
park terminally invalid work indefinitely.

The original report identifies this intermediate fixture coverage failure;
its exact intermediate source/JAR was not preserved as a separate tracked
checkpoint. This review preserves its log and reproduces the current code's
matching cancellation/retention path, not an artifact-equivalent rerun of that
old server lifecycle.

The new deterministic test
`ParsedApplyFutureBindingTest.missingRouteCancellationRecreatesTwoTerminalCustodyEntriesAfterCleanup`
uses the real load graph, delivery pump, pending stages and services: arm one
EMPTY graph, remove its only route, complete parsed input first, then complete
POI. It repeats 34 times and confirms the same 68 retained entries after shutdown.

**Diagnosis:** `PendingVanillaStage.control` completes an outcome synchronously.
`ChunkLoadGraph.abort` controls POI, parsed, recovery and publication in a loop.
Controlling parsed reenters settleTerminal/cleanupIfSettled, which releases the
work/lifecycle and custody maps. The outer abort loop then controls recovery
and publication; `ChunkCustodyModel.disarmLocked` inserts CANCELLED for a null
state, recreating those two orphan StageIds after cleanup. This does not require
a thread race. Redundant LoadEnvironment cancellation is included in the test.

Classification: **other = custody stage-state metadata** (LOAD_RECOVERY and
STATUS_PUBLISH StageId -> CANCELLED), not retained load graph, pending callback
payload, Minecraft holder/work token, future index, POI/light receipt, or
chunk-type metadata. The original log reports all those other counters zero;
the deterministic test confirms released inputs/futures/services, no leases or
invocation, and the exact two stage IDs. It does not instantiate production
runtime future indexes, so their zero evidence comes from the original log.
The IDs retain value lineage only, not Minecraft holder references. Aggregate
counts before world-root disposal do not alone prove an indefinitely reachable
process heap leak; the terminal ledger nevertheless violates cleanup contract.

The passing new test explicitly **characterizes the defect** with expected
2/68; it is not cleanup acceptance. A repair must disarm all branches before
terminal completion can trigger final reclamation and prevent late controls
from recreating forgotten stages, without suppressing legitimate new stages.
Replace its orphan assertions with absence/zero when repaired. Required exact
cleanup assertions: all dependent/inner/holder futures terminal; original
success preserved if already completed; each old slot settled once; no POI,
parsed, recovery or publication execution for pre-claim terminal work; no
retained input/continuation, queued delivery or active lease; zero graph,
returned/POI/synchronous indexes, light batch/occurrence/receipt, POI receipt,
metadata, lifecycle, work, demand, claim, disposition and stage entries after
all registered callbacks acknowledge. Re-run late/duplicate control, before/after
arming, producer completion and shutdown variants; verify replacement-holder
state untouched and temporary topology absence eventually reroutes exactly once.
The wider valid fixture is not this regression and no cleanup fix is claimed.

## 8. Integrated acceptance and exact next task

Keep all existing witnesses. Add bound service/FULL tests, using controlled
scheduling and sinks, for:

| Case | Required result |
| --- | --- |
| Saved team; absent team; same team; absent member | Match vanilla return, warnings, membership, dirty and notification behavior, including same-team REMOVE/ADD. |
| Prior membership replacement and failure at each primitive/send/waypoint point | Exact prefix/state/cause, no fabricated rollback, no duplicate journal entries or retry after later failure; cover EntityType's swallowed RuntimeException branch. |
| Two region-owned restorations sharing a scoreboard | Reserve before claim; serialize service state, no owner wait/second lease, no bypass by commands/queries/team setters/save. |
| Cross-dimension notifications and later team mutation | Immutable committed values; global loop order and per-session FIFO; no live player reads at dispatch. |
| Snapshot/update races and initialization gap | Update before snapshot, during attempted snapshot, after snapshot before PlayerList registration, and during re-sync; one coherent initialization followed only by later ordered deltas. |
| Disconnect/reconnect, respawn and dimension change | Old-session work never targets new connection; reused listener retains order; closed/failed initialization frees resources. |
| Stale and duplicate FULL, including after service commit | Stale pre-claim mutation=0; duplicate invocation/batch=0; no replay after later failure or topology change. |
| Shutdown at reserved, claimed, committed and partially submitted boundaries | Ordered settlement of pinned work; no new claims, no callback under state/custody locks, no raw shutdown gameplay, bounded resource cleanup and reported uncertain sends. |
| Missing-route path | Fix/re-run section 7 with all zero cleanup assertions; distinguish topology pending, invalid coverage, cancellation and real lifecycle retirement. |
| Complete FULL and publication | New/reload/Imposter branches, real holder lifecycle and actual returned R, passenger and block-entity callbacks, separate publication claim, genuine vanilla failure handling. |

Actual clients remain required afterward for initial team state, ordered
membership visibility across two dimensions, reconnect and real transport
behavior; controlled sinks alone establish neither client acceptance nor
network exactly-once. Existing movement/client regression is separate evidence.

**Exact next task:** start from this decision and preserved tests; close the
named callback authority/footprint gaps in section 6 before FULL entry, diagnose
and repair the reproduced terminal cleanup ordering, then implement this
coordinator/connection binding together with the original FULL supplier and its
separate holder edge. Extend actual holder/work identity beyond EMPTY, preflight
all named prerequisites, run the matrix and the fresh/reload/account-free client
ladder with authoritative FULL counters. If a named callback cannot satisfy its
bounded contract, report that concrete conflict before enabling FULL; do not
substitute disconnected service scaffolding or a center-only lease.

Production server-only; simulation workers=1; parallel gameplay=OFF;
R-CHUNK-01=PARTIAL; Stage 3=PARTIAL; Stage 4=NOT STARTED.
