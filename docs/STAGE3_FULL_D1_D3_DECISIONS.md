# FULL D1–D3 decisions

2026-09-05 update: the user has explicitly **APPROVED** the bounded D2-P and
D3-X amendments below. See [the approval record](STAGE3_FULL_D2_D3_APPROVAL.md)
and [implementation/gate ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md).
The original review's pending-approval statements below describe its historical
state; their descriptions of observable differences and remaining obligations
are preserved. Approval is not evidence of production implementation.

2026-09-04. Start: `84a01d4352dc5b937da8ff0c35dd1df9414bd017`, clean.
Cleanup checkpoint `a99d647c1ed39b1a96d5267e6f08f06ba17d8945` is an ancestor
and is preserved without modifying its implementation or assertions.

| Decision | Disposition | Selected contract |
| --- | --- | --- |
| D1 | **RESOLVED WITH DOCUMENTED AMENDMENT** | Exact references are aliases, never foreign gameplay authority. Extend the existing identity/world-registry protocol with versioned, owner-published identity and target-validation facts; consume those facts synchronously at the original lookup/validation calls. |
| D2 | **REQUIRES USER APPROVAL FOR AN OBSERVABLE CHANGE** | Prepare the legacy-name operation, including its cache effects and completed persistence attempt, before service reservations and FULL admission. Explicitly permit these effects for a subsequently stale or unreached Owner callback, and move its identity observation to that preparation cut. |
| D3 | **REQUIRES USER APPROVAL FOR AN OBSERVABLE CHANGE** | Permit exact conversion-private access to the actual in-progress chunk for the identified self-dependent difficulty/collision queries. This allows a saved conversion to finish on a schedule where the pinned getter would wait for its own supplier. Preserve already-ready and actual-null branches. |

These are definite dispositions, not missing-helper deferrals. D2 and D3 have
concrete, bounded witnesses and precise proposed amendments below. **Their
observable changes have not been approved or implemented in production.** D3
is deliberately not called vanilla-equivalent: the cyclic branch has no normal
vanilla return with which to claim equivalence. Resolving the architectural
choice does not manufacture user approval for that new progress behavior.

**FULL callback contract closure: INCOMPLETE. Integrated FULL implementation
ready: NO**, pending D2/D3 approval and the implementation acceptance below.
Production FULL: **NOT ENABLED**. Server-only; simulation workers=1;
parallel gameplay=OFF; R-CHUNK-01=PARTIAL; Stage 3=PARTIAL; Stage 4=NOT STARTED.

## 1. Evidence and scope

Read with the [ownership architecture](STAGE3_CHUNK_OWNERSHIP_ARCHITECTURE.md),
[FULL review](STAGE3_FULL_CONVERSION_REVIEW.md),
[scoreboard decision](STAGE3_FULL_SCOREBOARD_SERVICE_DECISION.md), and
[preservation record](STAGE3_FULL_REVIEW_PRESERVATION.md). The prior review's
bounded vanilla/installed-Fabric census remains the environment boundary;
this is not a fresh compatibility-mod census.

Verified configuration: Minecraft 26.2, Java toolchain/release 25, local Java
25.0.4.1, Loader 0.19.5, development Fabric API 0.159.0+26.2, Loom 1.17.20,
Gradle wrapper 9.5.1. Gradle's parallel build option is not parallel gameplay.
`fabric.mod.json` remains server-only; no main/client/config/build file changed.

The exact recorded artifact is
`C:/Users/Admin/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
Rehashed SHA-256:
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.
Do not confuse it with Loom's processed project-local development JAR,
`minecraft-merged-043a8b3edf-26.2.jar`, SHA-256
`B85B40FF77A813A46A4078F04B1CA4FA291942F01AF146F9F5F391D4D9F5FFAB`.
The latter is the development test dependency, not a different Minecraft release.
Fresh disassembly of the recorded artifact is saved in ignored
`run/full-d1-d3-decisions/pinned-bytecode.txt`; the existing generated readable
view is `run/full-callback-closure/source/`. Method names below refer to these
26.2 methods, including concrete dispatch, not another version.

Authlib 9.0.75 was also inspected locally, SHA-256
`1F77E70240548B9CD233DA0E12938BBBE5597AC9E94F6C6B07577FAF1461A951`;
`authlib-bytecode.txt` records its actual HTTP and exception edges. No profile
network requests, accounts, credentials, launcher or GUI automation were used.
The testing policy was read before running the deterministic Minecraft-aware
tests. No external server/client session was necessary for these decisions.

Rebuilt baseline: `build compileClientGameTestJava --rerun-tasks --no-daemon`,
**221 tests, zero failures/errors/skips**. Final clean build is recorded in
section 7. All new fixtures are under `src/test`; they install no coordinator,
placeholder production interface, FULL mixin or production authority.

## 2. D1 — a reference is an alias; synchronous consumers have distinct rights

### Exact lookup and consumer behavior

`EntityReference.getEntity(UUIDLookup, Class)` first checks its cached *actual
object*. If that object's final `Entity.isRemoved` is false, it returns that
same object without consulting the supplied lookup or checking its dimension.
If removed, it restores the cached object's UUID to the unresolved side and
resolves again. An unresolved reference uses the lookup, performs the class
test, then checks removal, caches and returns the actual object.

`getEntity(Level, Class)` chooses the player-directory path only if the requested
class is assignable to Player. `getLivingEntity` therefore uses
`ServerLevel.getEntityInAnyDimension`, including for a resulting ServerPlayer.
That method checks the origin world's UUID map, then other server worlds in
their existing iteration order. It stops at the first nonnull entry. **The
subsequent class/removal rejection does not restart the search in another
world.** `getPlayerInAnyDimension` instead uses PlayerList's player-by-UUID map.
A single UUID-to-object global map would change this contract.

| Reached operation | Actual use of the result | Authority and permitted replacement of foreign reads |
| --- | --- | --- |
| `NeutralMob.readPersistentAngerSaveData` | Stores anger end time/reference, resolves it, invokes virtual `setTarget` synchronously. | Caller entity R/exclusive construction custody; reference-fact service for lookup/removal. Restore `anger_end_time` or legacy `AngerTime` with the original caller-world clock. |
| `Mob.setTarget -> asValidTarget` | Player creative/spectator checks; virtual caller `canAttack(target)`; stores the exact accepted pointer in `Mob.target`. | Caller R; foreign creative/spectator/type/enemy-visibility facts. This is **not** just reference storage. |
| `Mob.canAttack -> LivingEntity.canAttack` | Rejects GHAST type; for a Player checks **caller's** world difficulty; otherwise invokes target `canBeSeenAsEnemy`. | Target type/fact receipt; caller's reserved difficulty context. No target-world difficulty query occurs in this chain. |
| Target's `LivingEntity.canBeSeenAsEnemy/canBeSeenByAnyone/isAlive` | Invulnerability, spectator state, removed state, health. | Owner publishes the exact predicate fact. No foreign `entityData`, health or abilities object escapes. |
| Concrete target overrides | Player also checks abilities.invulnerable and gameMode; Axolotl checks playing-dead; ArmorStand replaces visibility with `!isInvisible && !isMarker`, **without the base health check**. | Exact dispatch version plus these scalar facts, not a generic “alive means attackable” approximation. |
| Concrete neutral callers | Bee/PolarBear use inherited target setter; IronGolem additionally checks player-created/PLAYER and CREEPER type; Wolf uses TamableAnimal's `isOwnedBy`, which compares `target == getOwner()`; EnderMan's setter changes its own speed/data/timer; ZombifiedPiglin samples its own two anger-delay timers before super on its null-to-nonnull branch. | Preserve original caller-side virtual execution, source state and random consumption. Wolf's additional owner lookup uses the same identity protocol; no target mutation occurs in these setters. ZombifiedPiglin's existing target validation is another occurrence, not an assumed null. |
| `Projectile.readAdditionalSaveData` | Reads Owner into an EntityReference; base setter stores it. Pairing later calls `getOwner`, then `owner.getId`. | Caller R; target identity/removal/runtime-ID facts. No target position, inventory, health or connection access is needed for that packet's owner data. Preserve `getId`'s zero-ID exception. Runtime ID/UUID are versioned assigned identity, not inherently immutable Java fields. |
| `FishingHook.getAddEntityPacket` | Same owner lookup; absent owner uses the hook's own ID, unlike Projectile's zero. | Same identity facts if the hook actually has an owner. **Saved FishingHook reader is empty and does not call super**, so saved Owner NBT does not set one. Its concrete `setOwner(Entity)` and removal can write `player.fishing` through `updateOwnerInfo`; those calls are not licensed by a packet/read-reference permit. |

`getTargetUnchecked` returns a raw retained alias, whereas `getTarget` validates
again. Later anger updates read death/creative/spectator state; projectile ticks
can traverse owner vehicles/passengers, boxes and world interaction methods.
Those later uses require their own preflight and authority. The FULL grant is
not transferable to later gameplay. OwnableEntity root-owner/team traversal
also has no blanket permission from this decision.

### Selected service-boundary amendment D1-F

**Old:** M1 world registry turns protect directory/container integrity but do
not authorize foreign live reads; existing ObjectOwnership/interaction indexes
are routing hints and have neither the required per-world UUID lookup order nor
complete predicate facts. Same-world convergence cannot span dimensions.

**New, bounded to the named consumers:** extend those existing owner-published
identity records with a typed reference-fact projection, owned by the named
world identity service. It contains the exact opaque object alias, server/world
instance, object incarnation, reference revision, assigned UUID/runtime ID,
concrete dispatch identity, removal state and the specific validation facts
above. C supplies the separate player-directory/session cut when that lookup
overload is used. Do not replace or infer the actual source entity owner.

Owner-side publication derives facts under that object's existing authority.
Every writer of a contributing field (including health/data-tracker updates,
invulnerability, gameMode/abilities, axolotl/armor-stand flags, UUID/ID assignment,
removal, unload, transfer and respawn) reserves the corresponding named fact
turn **before its enclosing regional/serial phase**. In the one-worker baseline
this can conservatively cover the whole already-serial phase. Publish the
updated fact revision synchronously with each reached mutation before another
consumer can read it, including mutations in a partially failing callback.
Do not publish only at tick end. Birth/lookup insertion and removal retain their
original different observation points. Cached live aliases are not made absent
merely because no addressable lookup entry currently contains them.

FULL reserves these named turns in the same `(world-instance, service-kind)`
order as M1, revalidates their revisions before R, and holds the finite logical
turn through the original synchronous body. Other participating writers remain
**unclaimed** until that turn is released. They never hold a region while
waiting. No region is leased, frozen or acquired by the fact service, no foreign
object is read through the returned alias, and no service-state lock spans a
callback. This explicitly changes service participation; calling an extra
foreign region lease a “reference guard” is rejected.

At an original foreign read call, consume the current service-owned scalar
projection, preserving the exact branch/short-circuit result. For example the
foreign `target.canBeSeenAsEnemy` call is serviced as the audited predicate,
not run on a live foreign object or replaced by a generic proxy. Source-side
`setTarget` overrides continue at their original positions. Same-owner calls
may use the original fields under R. `isRemoved`, assigned ID and UUID access
inside EntityReference must obey this protocol too; merely intercepting the
outer directory lookup would leave unauthorized reads behind it.

The observation point remains the **original call**, not the first speculative
preflight lookup. Preflight describes possible aliases and publishes facts;
the pinned service cut ensures no conflicting foreign change can slip between
claim and consumption. Same-body earlier mutations update the service facts
before the later call, so preflight does not erase prefix visibility. Discovery
must include the UUID maps of every world that the ordered search can visit,
even a world whose map currently contains no match: a concurrent insertion
there could otherwise change the first result. Cache-hit calls need the cached
incarnation's facts even if another same-UUID object has since appeared.
The server world's ordered membership/instance cut is pinned by the existing
world-lifecycle admission fence as well: world creation/replacement/teardown
must publish between claims, never alter the searched world set mid-body.

**Allowed:** store/return the actual reference in caller-owned state; `==`, null
comparison, `instanceof`/class identity; inspect immutable detached identity
values provided by the fact capability; consume the enumerated facts; read or
mutate the caller under R. **Forbidden:** arbitrary target virtual calls,
target setters, mutable fields, abilities/tracker/container references, vehicle
traversal, target-world access or connection access through that pointer.
Even `equals/hashCode` require their own known implementation, not an assumption
that they compare pointers. S and C never turn the alias into gameplay authority.

### Identity, lifetime and invalidation

* Correlate each occurrence with FULL lifecycle/WorkId/StageId, exact retained
  holder/reservation, immutable input path, and an entity-reference instance or
  caller field. Use object `==` plus monotonic incarnation/revision, not UUID,
  dimension name, or identityHashCode alone.
* Before claim, removal/replacement, lookup insertion, movement/dimension
  transition, predicate changes or a changed dispatch/context cut invalidate
  preparation. Release all unused grants and rediscover under the same StageId.
  A removed cached object is converted to its old UUID and re-resolved in the
  current original order; never silently substitute a new object before that
  vanilla edge. A still-live object moving dimension remains the original cached
  pointer, with freshly established authority/facts. A respawned replacement is
  a new object incarnation even if its connection session continues.
* During the finite claim, participating foreign changes cannot occur. They
  queue before acquiring their own regions. Changes made by this body under R
  remain visible in order through synchronous fact publication. Any unexpected
  missing entitlement is an out-of-band barrier, not a null entity result or a
  caught vanilla exception that allows FULL success.
* After claim exit, the capability expires. Vanilla's cached pointer may remain
  until its original owner field is overwritten, cleared or collected. Retaining
  that alias neither pins the entity loaded nor preserves authority. On every
  later use, revalidate liveness/incarnation and the operation's actual read set;
  movement renews route authority without automatically changing cached identity.
  Capture the last UUID/identity needed for removed-alias fallback in the alias
  sidecar. Retire directory payloads after registered readers acknowledge;
  per-reference sidecars follow the actual reference lifetime, rather than an
  unbounded global strong-reference tombstone map.
* Return separate internal outcomes FOUND(alias,facts), NOT_FOUND (including
  the original type/removal rejection), and PRESENT_NOT_ENTITLED/STALE. Only the
  legitimate original absence branch maps to null. The latter outcomes park
  before claim or fence an unexpected escape. They never manufacture absence.

**Behavior preserved:** actual object identity, origin-first lookup, cached
incarnation preference, synchronous return/validation, source-side effects and
failures. **Permitted scheduling change:** conflicting fact writers serialize
before admission, in the already-serial environment. **Additional observable
change approved here:** none. This is a narrow service amendment, not permission
to cache an old predicate indefinitely. Missing complete writer coverage would
invalidate the implementation, not justify a second owner lease or an old fact.

D1 evidence: ten `FullEntityReferenceDecisionTest` methods cover same-owner
control, exact ordered world lookup/type rejection, actual NeutralMob/Golem
saved-reader dispatch, foreign target predicate calls, Player/Axolotl/ArmorStand
differences, removal/replacement, movement, cached reuse, denied-vs-absent,
projectile owner ID and saved FishingHook's empty reader. Controlled predicate
leaves and version examples are not a production fact coordinator. Limit:
every participating writer and retained-alias consumer still needs a bound
implementation/test before FULL admission; no general foreign gameplay permit
has been designed or claimed.

## 3. D2 — choose pre-admission preparation, requiring explicit consent

### Original branches and visibility

`LivingEntity.readAdditionalSaveData` restores Team before subclass Owner work.
`TamableAnimal.readAdditionalSaveData` calls super, resolves Owner, then stores
the reference/tame flags and sitting state. Its broad catch is around assigning
owner/tame state, **not around the lookup itself**. AbstractHorse calls super,
sets eating/bred/temper/tame, then resolves Owner. UUID-coded Owner bypasses
legacy conversion. String Owner enters `OldUsersConverter.convertMobOwnerIfNecessary`.

| Step/branch | Exact behavior |
| --- | --- |
| Empty or length >16 string | UUID parsing, invalid form returns null. A valid UUID string bypasses cache/network/file access. |
| Nonempty string length <=16 | `nameToIdCache.get(originalName)` before authentication/offline fallback. |
| Cache name lookup | Lowercase Locale.ROOT. Expired entry removes UUID and lowercase-name indexes and sets needsSave. An unexpired hit advances MRU and returns its NameAndId, with no save for this hit. |
| Cache miss, invalid player name | Local unknown-profile rule. This pinned StringUtil test rejects length >16 and characters <=32 or >=127; do not substitute a different-version username regex. |
| Cache miss, valid name | `profileRepository.findProfileByName(lowercaseName)` is called even if offline fallback is enabled. Authlib's actual implementation uses an unauthenticated HTTP GET and catches MinecraftClientException, logs, returns Optional.empty. It does not need or use a personal account token. |
| Empty repository result | If resolveOfflineUsers=true, create a local offline profile using the **lowercase cache name**. Otherwise return empty. An unexpected provider RuntimeException is not caught by this cache method. |
| Resolved profile | addInternal computes one-month expiry, safeAdd advances MRU and mutates both maps, then calls save. Memory mutation precedes serialization/write and survives failure there. |
| Save | Snapshot top 1,000 profiles by MRU, format dates/build JSON/serialize, then open/truncate UTF-8 file, write and close synchronously. Physical IOException is caught silently by vanilla. Serialization/runtime failure outside or inside that IOException-only catch propagates. A partial/truncated file is possible. |
| Expired entry followed by empty resolution | needsSave still causes save of the removal, despite no identity result. An ordinary empty miss with nothing expired does not save. |
| Converter receives empty cache result | Dedicated authenticated branch calls lookupPlayers -> findProfilesByNames; actual Authlib uses HTTP POST, normalizes names for requests, has its original retries/callback failures. Successful callback adds to cache (including synchronous save) before adding to its local result list. Failed callback logs; empty result list returns null. Offline/singleplayer branch derives UUID from **original-case name** and itself does not add/save. |
| Return to loading | Caller observes resolved UUID/null or propagated runtime failure. It does **not** observe an IOException as failed identity resolution, nor does successful identity imply successful persistence. Nevertheless the synchronous write/close attempt has finished before normal return. |

Thus “asynchronously save later” loses an observable file/order property;
“fail identity whenever the file fails” changes an observable return. Neither
is the selected contract. Persistence receipt must say SUCCEEDED,
FAILED_IO_CAUGHT, or THREW(cause), not always “saved”. Preserve genuine null,
Authlib's caught network failure policy, converter fallback and propagation.

### Smallest conflict and rejected alternatives

The smallest conflicting reachable sequence is:

```text
claimed FULL -> Entity.load -> LivingEntity saved Team mutation/send
            -> TamableAnimal/AbstractHorse Owner(name)
            -> cache miss -> provider -> map mutation -> JSON -> file write/close
            -> Owner callback return
```

One coherent synchronous continuation may not hold R or C/S/W while waiting
for profile or storage I/O. Precomputing the entire `get` earlier preserves
completed-before-return but changes which prefix exists: Team may throw before
Owner is reached, or FULL may become stale without invocation, after cache/file
effects have already occurred. The preparation/stale witness demonstrates this
with the real cache and an actual temporary file.

* UUID/snapshot substitution alone does not preserve MRU, expiry removals, map
  insertions, save attempts, their failures or their order relative to Team.
* Speculative profile response alone can be prepared without committing cache
  state, but a reached cache miss still needs its synchronous write. Prewriting
  a temporary file still leaves rename/open/truncate/close I/O at commit and
  changes vanilla's partial-write/failure behavior. It is not an I/O-free commit.
* Keeping S or a cache turn over that I/O, synchronously pumping another region,
  or parking a Java stack under R is forbidden. A per-name future is a parked
  request, not permission for a region or cache service to block.
* Splitting at the reached Owner edge can preserve Team-before-lookup effects,
  but releases S/R after a partially committed prefix. Unrelated operations can
  then observe/interleave with that prefix; reacquiring and restarting the
  whole supplier would duplicate it. This would require an explicit resumable
  entity-load/stream/passenger continuation and changed atomicity, including
  preserved Java catch boundaries. It is a larger observable amendment than
  the selected early-preparation rule, not an existing typed stage feature.

No enforced DFU/input invariant excludes all supported legacy strings. The
review therefore selects the following specific scheduling amendment, **pending
user approval**; it does not disable legacy ownership restoration.

### Proposed D2-P preparation contract

1. Input is an immutable original Owner value, original casing, input digest,
   entity type and exact root/passenger path, server/cache instance, provider and
   auth/offline-policy generation, plus lifecycle/WorkId/FULL StageId and
   occurrence ordinal. Enumerate possible reader occurrences without executing
   constructors/readers. A response contains detached UUID/null/original-cause,
   the cache observation/version, mutation/MRU sequence and completed save-attempt
   receipt (when required). It contains no loaded entity, world or holder closure.
2. Run the logical cache/profile operation **before C, S, W and R**. Cache hit,
   removal/addition/MRU and save-snapshot steps use short cache-state operations.
   Release them before invoking the provider, JSON work or storage. Pin only
   immutable request/result records. After provider completion, reenter the
   cache coordinator, revalidate the key/policy revision and apply the selected
   result or redo preparation before claiming FULL. Never let a stale response
   overwrite a newer authoritative cache entry. Finish the required persistence
   attempt before issuing the consumable receipt.
3. Order file snapshots/writes by cache save sequence through a dedicated storage
   queue; an older snapshot cannot overwrite a later completed one. This is an
   asynchronous storage operation, **not a cache/C/S/W scheduling grant** held
   by an I/O worker. Cache reads and metadata turns remain available while I/O
   is outstanding. Only later writes to that same file necessarily await earlier
   writes, as owner-free I/O requests. No storage completion waits for FULL or a
   caller's return; no region is needed to finish it.
4. Logical identity observation is the completed preparation's selected cache/
   profile cut. Validate input, policy/cache identity mapping and receipt again
   just before FULL claim, after obtaining short cache validation in its declared
   preclaim service position. MRU-only changes do not change a resolved identity;
   do not run another `get` just to validate and accidentally duplicate its
   effects. Mapping/policy/expiry change **before claim** invalidates the receipt.
   Release every C/S/W grant before any renewed lookup or I/O. Once claimed,
   the receipt defines the earlier observation for that occurrence; expiration
   or mapping changes after that cut do not change this in-flight result. That
   moved observation, including date boundaries, is part of the requested consent.
5. At the original Owner edge, consume the correlated result exactly once.
   Apply the original returned UUID/null or throw the original propagated cause
   there, preserving Entity.load/EntityType's catches and every source-side
   prefix. Unreached throwing receipts do not retroactively fail a skipped
   entity. Do not conflate FAILED_IO_CAUGHT with resolution failure. A write's
   completion before callback return is required; no fire-and-forget persistence.
6. Duplicate deliveries of one occurrence share its record/outcome. Separate
   logical calls, including different entities naming the same user, retain
   separate MRU/return occurrences. Share only compatible pending provider work;
   after the first commits, revalidate the second as the actual cache hit/miss
   it now is. Case folding deduplicates cache/provider keys, not the converter's
   original-case offline fallback. An input mismatch never reuses an occurrence.
7. If FULL becomes stale, revoke only that subscriber's authority to consume.
   Dispose its payload once producers acknowledge. Already committed cache/file
   prefixes remain, because rolling them back could erase another valid request.
   Unstarted orphan preparation can be cancelled; late responses cannot publish
   to a retired WorkId or resolve a replacement holder. Earlier external profile
   activity, MRU changes and writes may survive even if Owner is never called.
8. Shutdown fences new preparation and all new claims. Account for outstanding
   producer and file occurrences separately. Discard an uncommitted response
   after terminal acknowledgement; drain already committed save attempts in file
   order and release their receipts. Real I/O/provider errors retain their
   actual outcomes. A bounded shutdown timeout reports unresolved I/O, never
   successful persistence; callbacks must not touch disposed cache roots. Keep
   the minimal operation/root alive until its real completion/cancellation
   acknowledgement, not a region, service turn or whole entity graph. No durable
   replay log or forced interruption of a half-written file is implied.

**Preserved:** identity/null/error mapping at the reached Owner callback,
case distinctions, cache mutation-before-save prefix, write-attempt settlement
before callback return, original entity catches and no supplier replay.
**Already permitted scheduling:** waiting before regional admission and moving
detached computations. **Additional observable changes requiring consent:**
early provider/cache/MRU/file effects on unreached/stale work; the earlier
identity/expiry observation; ordering these prepared calls/file snapshots ahead
of later FULL callbacks. These are explicit; preparation is not claimed to be
vanilla-equivalent.

D2 evidence: thirteen `FullLegacyOwnerDecisionTest` methods use real
OldUsersConverter/CachedUserNameToIdResolver methods, controlled single/bulk
providers, temporary files and bounded latches. They cover the entire requested
hit/miss/failure/persistence/duplicate/stale/shutdown matrix, expired removal,
case-sensitive offline fallback and the distinction between caught physical I/O
and propagated save/runtime failure. The small `PreparationProbe` is an eager
preparation **counterexample**, not a production concurrent cache coordinator
or proof of cancellation of an arbitrary network library. No permission to
make the proposed early effects has been inferred from a passing test.

## 4. D3 — self-dependency established; exact-object remedy selected, not approved

### Exact cycle, branches and result use

Both saved routes enter the same reader. Generated/proto data reaches
`ChunkStatusTasks.full -> new LevelChunk -> postLoadProtoChunk ->
EntityType.loadEntitiesRecursive`; reload's Imposter branch reuses the
LevelChunk created by SerializableChunkData.read and calls the original
`SerializableChunkData.postLoadChunk` processor, using addLegacyChunkEntities.
That processor loads entities before its block-entity loop. Root/passenger
stream insertion/catch order remains as documented in the prior inventory.

`Tadpole.readAdditionalSaveData` calls the superclass, then
`setAge(input.getIntOr("Age",0))`, **then** sets AgeLocked. Age >=24000 (the
pinned ticksToBeFrog) calls ageUp even when the saved AgeLocked is true. Age is
not clamped. Mob.convertTo checks source removal, creates a frog, executes
ConversionType.SINGLE/convertCommon (position, references, effects, attributes,
team transfer and other original state), then the Tadpole callback does:

```text
getCurrentDifficultyAt(frog.blockPosition())
  -> getChunk(sectionX, sectionZ, FULL, false)
  -> [nonnull] getInhabitedTime + getMoonBrightness(position)
  -> DifficultyInstance(caller difficulty, overworld clock, inhabited, moon)
frog.finalizeSpawn(..., CONVERSION, null)
frog.setPersistenceRequired()
frog.fudgePositionAfterSizeChange(source dimensions)
source.playSound(TADPOLE_GROW_UP, .15, 1)
Mob.convertTo: addFreshEntity(frog); source.discard(); return frog
Tadpole reader: setAgeLocked(saved value)
EntityType stream: original source/root and passengers continue
```

Frog finalization chooses a variant, initializes jump memory and invokes the
AgeableMob/Mob suffix. Difficulty is an actually constructed argument; its
getter cannot be discarded merely because a particular suffix does not use
every field. The world random sequence includes variant choice, jump-delay
sample, follow-range bonus and handedness; sound uses the **separate**
Level.soundSeedGenerator. Conversion's entity RNG and source overrides also
remain at their original calls. The witness asserts both seeded world and
sound-generator consumption for its controlled single-variant environment.

The exact self-dependency is **SOURCE-PROVEN**, with a bounded executable
witness of the future-selection edge, not a live hung-server reproduction:

```text
X's actual FULL supplier is executing runPostLoad
  -> mature saved Tadpole conversion callback
  -> ServerLevel.getCurrentDifficultyAt, FULL / loadOrGenerate=false
  -> ServerChunkCache cache miss; visible holder, ticket <= FULL threshold
  -> getChunkFutureMainThread -> holder.scheduleChunkGenerationTask(FULL)
  -> getOrCreateFuture(FULL) returns X's existing unfinished slot Y
  -> managedBlock(Y::isDone), then join

Y completes successfully through GenerationChunkHolder.applyStep's handler
  -> completeFuture(FULL, X), only AFTER that same supplier has returned
```

`replaceProtoChunk` completes/replaces slots only below FULL (`length - 1`). It
does not resolve Y. False suppresses adding a ticket; it does **not** suppress
scheduling or waiting on an existing entitled holder. On the bounded witness's
exact holder, the original private future-selector and original holder scheduler
return the same `fullSlot` by `==` while that original supplier is running.
The witness stops before managedBlock by a deliberately injected diagnostic
exception. EntityType catches it, so this **injected-stop supplier** succeeds;
that is not evidence of an unmodified getter completing or of a live deadlock.

The cycle is conditional: an already-ready cache/holder result returns normally;
a cached null, absent holder, disallowed status or insufficient ticket can
produce the genuine null/unloaded path. ServerLevel's null branch uses zero
inhabited/moon values and skips the moon lookup. Preserve it. Off-thread
ServerChunkCache adds a main-thread submit/join; moving the body to a worker
does not fix the authority/wait issue. Physical getter semantics remain an
explicit constraint, never implicit authority.

The later collision-fudge call also matters. Entity.findFreePosition reaches
CollisionGetter -> BlockCollisions -> Level.getChunkForCollisions, again
FULL,false. It visits a rounded block envelope, potentially neighboring chunks,
then block collision shapes and the world border. Dynamic shapes such as
ShulkerBoxBlock.getShape can call Level.getBlockEntity -> getChunkAt, a FULL,true
query on the physical server-thread branch; the block entity access may create
an instance through EntityCreationType.IMMEDIATE. Do not label the whole suffix
an inhabited-time-only read or silently return the worker's null block entity.

### Alternatives and the minimum D3-X amendment

* Making public FULL a readiness prerequisite for **this** callback requires
  its own postcondition before its only producer may execute. Rejected.
* The previous-status object already has inhabited time, sections and biome
  data. Precomputing **the complete DifficultyInstance** before claim is not
  justified: it also observes world clock, difficulty and positional environment
  attributes, and null-vs-present is significant. Earlier observation would need
  additional consent. Instead retain actual data and call the original query at
  its original point. No guessed zero, copied placeholder LevelChunk or synthetic
  ready future is acceptable.
* Splitting after SINGLE/team transfer does not make Y able to complete: Y still
  depends on the paused supplier. Publishing Y to release that pause exposes a
  partially converted chunk. A generic typed continuation alone therefore does
  not remove the cycle. It would need a larger public atomicity change.
* Selected: a **private, exact-conversion object capability** for the query's
  needed fields/operations, with no status publication. This is smaller than
  splitting the conversion and does not postpone it to another tick.

**Old:** architecture §§6/8/10 require ready getter results and provide no
same-conversion unpublished-object exception.

**Proposed new D3-X, pending approval:**

1. Before claim, prove that the supplier's actual input/holder/work is entitled
   to FULL and that the original relevant query would select its own incomplete
   FULL slot, not a ready/null/error result. Retain exact input and borrow/POI/
   light receipts; reserve context, random, registry, ticket (if required),
   block-entity callback and collision/neighbor footprint authority. The
   prerequisite is a *validated plan to bind the actual object*, not an object
   or conversion effect that does not exist until the supplier runs.
2. Inside that one successful R claim, after the original constructor/reuse and
   setFullStatus, bind the **actual local LevelChunk** to an unforgeable
   `(world-instance,lifecycle,WorkId,StageId,claim,holder,object,reason)` capability.
   New branch: exact object wrapped by replaceProtoChunk's Imposter. Reload:
   the original wrapped read object, with no replacement. Never create a chunk
   just to answer a query, or infer identity from coordinates alone.
3. Permit only this operation's audited direct-self query chain. Difficulty may
   use getInhabitedTime on that object; all other difficulty/environment reads
   retain their existing context authority and observation point. Collision may
   use the initialized section/block data and the original local block-entity
   operations if that dynamic-shape branch is in the admitted footprint. The
   ChunkAccess/LevelChunk constructors initialize inhabited time, sections,
   local/pending block-entity maps, heightmaps and listener maps before postLoad.
   Generation borrows must have ended. These fields are usable at that point;
   loaded/ticking/send readiness is not required for these specific operations.
   Reload's remaining block-entity NBT loop is **not** declared already complete.
   Keep original lazy creation/replacement and later loop order, with the
   reserved callbacks and their failure prefixes; do not preinstall that suffix.
4. Keep the ordinary query classifier's cache/holder/ticket/result selection.
   A genuine ready value/null/error wins unchanged. Only the exact unfinished
   self-slot case takes the private object route before any wait. For a reached
   FULL,true dynamic-shape lookup, preserve the original ticket-addition prefix
   under its named ticket service; do not silently erase that effect. It may
   take the exception only while the same already-entitled holder/work remains
   selected; a path requiring new distance-manager work must have been prepared
   before claim. This is a typed getter call-site adaptation, not a global
   `getChunk` fallback or main-thread test.
5. No public holder slot, ChunkCache last-FULL entry, ready flag, future or Fabric
   loaded cache receives the private object. The original supplier's later
   setLoaded and registrations occur only in their original order. Revoke the
   capability on normal/exceptional synchronous exit before completion handlers.
   It cannot be retained by a continuation, saved view or unadmitted callback.
6. Different work/claim/world or neighboring coordinates cannot use it. Even
   neighbors inside the converged R need their **ordinary** ready result and
   exact lifecycle receipt. Preflight the full collision/environ/listener closure
   without executing conversion. If a neighbor dependency itself leads back to
   X, detect the cycle before reservation; do not leave X and Y parked forever
   or pretend the neighbor is absent. The direct-self rule does not solve a
   mutually dependent pair of different FULL operations. Such a pair would
   require a further explicit continuation/input amendment; this review does
   not assert its absence in arbitrary saved worlds or turn a detected pair
   into successful admission. This is a remaining implementation/closure limit,
   not permission to disable those saved entities.

The minimum observable consent is: **for the identified self-dependent query,
use the actual in-progress local state and finish the original conversion,
instead of waiting for its own publication.** This preserves the original
callback sequence and observed ready/null results for already-terminating
branches, but supplies progress and subsequent effects on a formerly cyclic
branch. It is explicitly not merely a wall-clock-delay change. No approval for
new neighbor/public visibility is bundled into it.

D3 evidence: nine `FullTadpoleDecisionTest` methods run the original FULL supplier
and saved reader on both proto and original reload-postLoad/Imposter branches.
They cover underage/mature AgeLocked input; original frog conversion, finalization,
collision and sound; future identity before publication; exact local object and
field validity; wrong work/neighbor/outside-call rejection; explicit publication
after supplier return; duplicate delivery of the same original AsyncSupply;
null difficulty semantics; caught sound failure with surviving team prefix;
and an uncaught registration-sink failure after conversion. A real server
scoreboard and empty waypoint manager run normally. Controlled endpoints are
minimal registry/context, world insertion sinks and transport, plus the proposed
getter boundary. The dynamic non-air block-entity shape branches and mutually
dependent neighboring FULL operations are **source obligations, not passing
fixture claims**. No test licenses replaying a nonnull postLoad callback after
failure. Generic StageId exactly-once/cleanup remains covered by the existing
custody tests; an AsyncSupply duplicate witness alone is not that authority.

## 5. Integrated dependency and reservation contract

This diagram is the proposed combined implementation after approval; its
conditional edges must not be mistaken for current production capability.
Relative C -> S -> W order is preserved. D1-F is a named world service within W.
D2-P adds a **short cache validation grant P between C and S**, held only while
forming/validating the finite bundle, never over preparation or I/O. Release P
after successful claim establishes the approved earlier observation, before
the original body. Ordinary cache-only operations need no C/S/W; an operation
needing both must obey this order. This makes section 3's cache validation
position explicit rather than leaving an implicit late cache-lock edge.

```text
actual holder / work / input registration, no gameplay authority
                 |
        +--------+--------------------------------------+
        |                                               |
 owner-published identity/facts,                 D2-P preparation [CONSENT]
 ready non-self chunks, context, footprint        cache short steps -> provider
 and convergence observations                    -> revalidate/commit/snapshot
        |                                        -> owner-free file queue
        |                                        -> settled attempt/result
        +---------------------+-------------------------+
                              |
         readiness dependency check (including callback-induced cycles)
                              |
        C connection/session entitlement and bounded notification capacity
                              v
        P short cache validation grant (only when D2 is present)
                              v
        S scoreboard turn
                              v
        W named turns sorted by (world-instance, service-kind)
          entity registry / D1 facts / tracker / waypoint / raid-boss /
          debug / ticks / eager-save / Fabric cache / required tickets
                              v
        revalidate ALL receipts + exact lineage + route + fences
          stale/busy -> release ALL unused grants -> owner-free preflight
                              v
        ONE converged world-bound region lease + unique FULL claim
          release P; no profile/storage work remains to be waited on
                              v
        original supplier ONCE
          construction/reuse -> bind D3-X exact local capability [CONSENT]
          original calls: D1 fact observations, D2 result consumption,
          conversion/sends/partial effects, original catches and suffix
                              v
        record real supplier outcome; revoke D3; release R/claim and turns
          before future completion; committed notification records retain
          only immutable payloads/exact-session capabilities until settlement
                              v
        separate FULL status publication prerequisite + fresh admission
                              v
        terminal acknowledgement and finite metadata/payload reclamation
```

“Ready” does not mean “all callback effects already committed.” The body is
the producer of team mutations, entity insertions, D3's concrete object binding,
loaded flags, ticker registrations, notifications and its actual result. None
is an entry prerequisite for that same body. D2's changed early effects are the
explicit exception needing consent, not an accidental circular admission rule.

| Required item | Producer / availability | Validity protection and consumer | Stale action / release |
| --- | --- | --- | --- |
| Input, holder, work and lower-status borrow completion | Original load/generation and lifecycle binder, before FULL readiness | Actual object equality, lifecycle/work reservation, frozen input/alias revision, POI/light receipts; consumed by FULL constructor/reuse and final publication | Reroute current work or authentic retirement; retain through supplier + holder edge + registered acknowledgements; never redirect to replacement holder. |
| Entity lookup/predicate receipt | Target owners publish facts before claim; service supplies current cut at each original read | D1-F world/lookup/alias/field revisions plus C when player lookup requires it; participating writers reserve before their own claims | Reprepare on preclaim change; finite fact grant ends at body exit. Vanilla alias may remain without permission; sidecar follows its reference lifetime. |
| Legacy owner result/save-attempt receipt | D2-P job, only after real resolution and required I/O attempt settles | Immutable occurrence/input/policy/cache cut; P validates before claim; Owner consumes original result/cause at its actual call | Release all grants to refresh; stale work loses its subscriber, not committed cache/file effects. Minimal producer records survive until actual acknowledgement, including shutdown. |
| Non-self chunk/context/footprint receipts | Independent preflight producers/owner observations and generation; before any grants | Exact requested getter result (including legitimate null), lifecycle, data/context/route version, no active compute alias; consumed by original callbacks | Version change releases bundle and rediscovers; ordinary unfinished dependency parks owner-free; cyclic dependency is a reported closure conflict, not a fabricated ready input. |
| C sessions/capacity | Connection lifecycle/initial-sync/catch-up, before S/W/R | Exact server/session/sync generation and send acceptance order | Disconnect closes exact capability, not a replacement session; unused grants release on staleness. Accepted sends/pending outcome records settle once without replay. |
| S and W logical turns | Named coordinators before claim, in declared order | Grant IDs + release/acquire publication + all conflicting phase participation; consumed synchronously by original primitives | Unavailable/stale releases all unused grants; claimed ones pin through finite exit. No state lock spans calls; immutable committed records outlive grants only as needed. |
| D3 private exact-object capability | **Original constructor/reuse during claim**, not preflight | Exact work/claim/holder/object/reason, valid initialized fields, one R; only audited direct-self query consumes | No post-claim widening/reroute; unexpected escape fences admission; revoke on exit. Never installed into public cache/slot or offered to neighbors. |
| Supplier completion/prefix outcomes | Original body and existing Java catch boundaries | Unique claim, original result/error, separate authority barrier flag | No replay or rollback. Release region/context/claim before dependent execution; preserve already committed prefixes on either outcome. |
| FULL publication prerequisite | Successful entire supplier plus still-entitled holder/work, completed mandatory service handoffs and no barrier | Separate publication-stage claim revalidates lineage, route, shutdown, all required actual receipts | Route change can publish same result at new owner; retirement/barrier rejects old publication with authenticated settlement; never require public FULL to produce its own supplier result. |

The graph's no-cycle argument is about **dependencies**, not just ordering:

* No C/P/S/W reservation is held while awaiting a provider, JSON/file job,
  readiness query or convergence. A failed bundle acquisition releases prior
  grants instead of keeping them while queued for a later grant.
* D1 service calls consume already-published facts; they do not submit target
  work or wait for its owner. Writers publish facts from their own admitted
  mutations and never require a response from the reading FULL operation.
* D2 completion needs no region or C/S/W grant. Cache publication is a short
  independently available step, with file order carried by immutable requests.
  Later cache commands must not hold P and ask for earlier C. Claimed work does
  not wait for cache revalidation or another profile attempt.
* D3 direct-self capability is produced after construction and before the nested
  query. It depends on neither public FULL nor a later callback. The preflight
  graph still checks non-self callback prerequisites; the self exception cannot
  be used to hide a two-work cycle. A detected cycle keeps the implementation
  gate incomplete with its exact dependency, not indefinitely green/parked.
* A callback that could require a missing grant or expand the footprint must be
  accounted for before invocation. Fixed service order does not make such a
  callback safe. Synchronous notifications finish at the original connection
  submission boundary; they never await recipients' regional gameplay or an
  initializing session's future activation.
* P, D1 facts, input/context, connections and routes are explicitly revalidated.
  Full publication revalidates again. Staleness after an accepted synchronous
  effect is not permission to replay it or claim it never happened.

## 6. One operation through staleness, partial failure, publication and cleanup

This is one WorkId with two **preclaim admission attempts**, not two executions.
It combines the source/actual-supplier witnesses with the existing custody
regressions; there is no production integrated-FULL test claim.

| Point | Outcome, retained state and release |
| --- | --- |
| Preflight succeeds | Retain exact original input/holder/work, non-self readiness/context and identity facts. With D2 consent, retain completed result/save receipt. No region or gameplay claim exists. |
| C/P/S succeed; another footprint/context prerequisite becomes stale | No callback has run. Revalidation rejects this attempt; release C/P/S and any W/capacity, drop superseded receipts and park the same StageId. D2's already-reached cache/file effects survive under the proposed consent. Do not try to claim with a known stale prerequisite. |
| Fresh preflight and all reservations succeed; regional claim wins | Pin one R, unique claim, C/S/W and exact identities. P may now be released: the approved D2 result cut is fixed. Retain all live input/object aliases needed by the body. |
| Original supplier reaches mature Tadpole conversion | It constructs/reuses the actual chunk, binds private D3 access, restores source team, constructs frog, commits conversion team changes, reaches difficulty/finalize/collision, then sound. These effects are produced here, not preconditions falsely required earlier. |
| Sound submission throws after conversion prefix | Mark the sound operation's real failed/uncertain submission suffix. The team change, consumed RNG, constructed frog and any accepted prior sends stay reached. Mob.convertTo has not yet added the frog or discarded the source on this failure branch. |
| Entity.load wraps, EntityType catches/logs RuntimeException and returns empty | That nested load failed. The stream omits that root; the outer postLoad callback may return normally, clear postLoad and allow the supplier's loaded/registration suffix. The passing caught-failure witness demonstrates this policy through the actual reader and supplier. Do not force the entire supplier to fail. |
| Alternative: registration consumer throws outside EntityType's catch | Converted effects already reached remain. The supplier fails; postLoad may remain nonnull and loaded may still be false. The uncaught-sink witness records this separate branch. A surviving callback field is not restart permission. |
| Supplier returns or fails | Record `(nested result, exact prefix, caught?, supplier result, authority-barrier state)` separately. Revoke D3; release unused occurrence capacity and C/S/W on synchronous exit. Release R and the active claim before future completion/handlers. Accepted immutable notification records retain their exact-session payloads until acknowledged; no live target graph is retained for later packet reconstruction. |
| FULL publication | A successful supplier with a caught vanilla nested failure may proceed through a fresh separately admitted publication if lineage/prerequisites still hold. Uncaught supplier failure or a Phoenium barrier cannot publish. A barrier is recorded out of band even if a vanilla catch swallows its thrown marker. Publication route drift reroutes the existing result; it does not reconvert. Shutdown/retirement settles the authorized old edge without rewriting a newer holder. |
| Terminal cleanup | Retire work under custody **before** terminal completion can reenter settlement. Disarm unclaimed callbacks, then acknowledge real producers/deliveries. APPLIED is not evidence that the claim has exited: retain legitimate claim-bound inputs/receipts until the claim map says released. Late controls/bindInput cannot recreate retired stages. Finally reclaim indexes, receipts, holder/work/lifecycle records and sidecars whose registered users have settled. |

The repaired 34-cancellation missing-route negative test still requires zero
retention. The 8×34 shared-root batches, terminal reentry, late old tokens versus
replacement/unrelated work, claimed-light and APPLIED-before-claim-release
regressions remain unchanged. Existing scoreboard and nine callback witnesses
remain unchanged. No broad lock, tombstone leak or early reclamation is traded
for D1–D3 progress.

## 7. Implementation gate, contention and verification

No implementation sequence is **authorized as ready** while D2/D3 consent is
missing. If those exact changes are approved, use this concrete sequence:

1. Bind the accepted scoreboard/connection services with all conflicting legacy
   readers/writers, initial-sync/catch-up and per-session send ordering. Integrate
   named world services and D1 fact publication with the actual entity lifecycle
   and each enumerated predicate writer; verify original branch/exception results
   and no access through raw foreign aliases. Do not add an unused service shell.
2. Add the exact FULL holder/work extension: real holder equality, original
   startedWork and generation borrow/refcount fences across proto, reload,
   Imposter reuse and publication. Extend the existing PendingVanillaStage
   registration/arming discipline; keep the original returned future edge.
3. Bind D2 preparation and occurrence consumption only with its consent, with
   per-key/provider deduplication, cache revisions, ordered completed I/O receipts
   and real shutdown accounting. Exercise its interleavings with C/P/S/W, rather
   than promoting the isolated PreparationProbe into production.
4. Close the actual caller footprint, including collision dynamic block-entity
   reads, exact FULL,true ticket effects and any non-self dependency cycles.
   With D3 consent bind the private local getter capability to the actual
   supplier's constructor/reuse boundary. Run the whole original supplier once;
   keep original postLoad, subtype overrides, sends, catches, random draws and
   prefixes. No new public ready status is needed for these private operations.
5. Bind the separate original holder publication handler with fresh claim and
   revalidation. Extend successful, failed, stale, shutdown, replacement and
   reentrant cleanup coverage through the final handler. Preserve the repaired
   terminal ordering and actual claim-release fence.
6. Only then run the existing fresh/reload/account-free client ladder on the
   final server-only artifact, with authoritative FULL and publication counters,
   actual pairing/session ordering and zero forbidden-access/retention evidence.
   That subsequent implementation acceptance, not this review, can assess FULL
   readiness; later ticking/send readiness remains separately scoped.

Tradeoffs: D1 fact participation serializes contributing mutations even across
dimensions while a finite FULL turn holds those facts; the existing C/S/world
turns can already serialize many FULL suppliers. Wide preflight convergence and
alias projections consume metadata. D2 retains bounded immutable pending
profiles/save snapshots and permits wasted early work only if approved; slow
providers/storage delay unclaimed FULL. D3 retains the actual chunk only for its
existing work/claim lifetime; it must not introduce global early visibility or
extra enduring chunk references. Backpressure must precede admission. No Android
suitability, throughput gain or performance acceptability follows from these
architecture/semantic witnesses.

Final verification: `gradlew.bat clean build compileClientGameTestJava --no-daemon`,
**253 tests, zero failures/errors/skips**, BUILD SUCCESSFUL. The 32 additions are
10 D1, 13 D2, 9 D3. Production JAR SHA-256 remains
`347E1EB30F078CBFF4CBB969244AED6E0324A5C0486DA61B894566D46210F796`.
`git diff --check` passes; only this decision, three linked architecture/handoff
records and four test files change. Build logs, pinned disassembly, baseline
counts and final evidence manifest are ignored under
`run/full-d1-d3-decisions/`. Historical unsuccessful fixture-build attempts are
not acceptance evidence. No new live-server claim is made.

The delivery report supplies the final commit hash; this document does not
embed its own self-referential hash. The cleanup checkpoint and all previously
successful commits remain ancestors. Production FULL stays **NOT ENABLED**;
server-only, workers=1, parallel=OFF, R-CHUNK-01/Stage 3=PARTIAL,
Stage 4=NOT STARTED. Callback contract closure and integrated readiness remain
**INCOMPLETE / NO** for the explicit unapproved D2/D3 behavior changes and the
named D3 non-self/dynamic-shape binding obligations, not an invented global
impossibility or a missing helper.
