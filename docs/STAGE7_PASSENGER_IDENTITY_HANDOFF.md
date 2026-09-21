# Stage 7 natural-spawn passenger identity handoff

Status: **PASSENGER TRACKING IDENTITY HANDOFF READY — RETURN TO LUNA** after the
clean candidate build and packaging audit recorded in the adjacent run manifest.
This is deterministic repair readiness, not a real-client acceptance result.

Parent: `8b094b7df947f5de273d6ff5de7abdb6178356de`.
Parent candidate SHA-256:
`0886D2C296AD6E202B6EF0C4707EFB3287D7758F2FF1F82DB4A9A10EFDEE8C7D`.
The original candidate and
`Phoenium/run/stage7-real-client-gates/same-chunk-cross-boundary-20260920-luna-max-214507`
were only read. The main checkout's unrelated changes were not used.

## Exact Minecraft 26.2 order

The preserved crash at `2026-09-20_21.51.42` fails in
`FullEntityFacts.trackingPassengers`, reached through root tracking during
`NaturalSpawner.spawnCategoryForPosition`. It does not identify the mob types.
No causal inference is made from the earlier movement warning.

The official-named 26.2 binary and native execution witnesses establish:

1. `ServerLevel` does not declare `addFreshEntityWithPassengers`; it inherits
   `ServerLevelAccessor`'s default implementation. That method calls
   `root.getSelfAndPassengers().forEach(this::addFreshEntity)`.
2. `Entity.getSelfAndPassengers` concatenates `Stream.of(this)` with the direct
   passenger list's `flatMap(Entity::getSelfAndPassengers)`. Traversal is ordered
   depth-first preorder: root, first child, that child's descendants, next sibling.
   Nested passenger graphs are supported.
3. `ServerLevel.addFreshEntity` synchronously calls private `addEntity`, which
   checks removal and calls `PersistentEntitySectionManager.addNewEntity`.
4. The manager first admits the UUID, adds to the persistent section, installs
   the level callback, and invokes `onCreated`. If accessible, `startTracking`
   calls `EntityLookup.add` and then `LevelCallback.onTrackingStart`. If ticking,
   `startTicking` follows. Only then does insertion return to the stream loop.
5. The entire root tracking-start body therefore completes before the first new
   passenger enters storage. Each accessible passenger's own tracking-start
   similarly completes before the next node's insertion.

For a wholly fresh attached graph, the passenger edges already exist before root
insertion. At root tracking, the root has storage and visible-lookup membership;
none of its new passengers has storage, lookup membership, or its own tracking
callback. Previously none had a Phoenium fact/route or object ownership binding
either. Visibility is conditional in the native manager; prepublication does not
manufacture callbacks for hidden members.

## Cause and historical prerequisite

`trackingPassengers` correctly required current facts but implicitly depended on
every attached passenger having already passed a publication edge. Vanilla's
parent-first callback ordering does not provide that guarantee.

The prior A3 prerequisite in `STAGE7_A3_ARCHITECTURE_DECISION.md` wraps saved-reader
streams from `processPendingLoads` and `addLegacyChunkEntities`. `loadedBatch`
publishes temporary exact scalar facts and routes without a visible candidate,
UUID lookup entry, or object owner. Natural spawning uses neither saved-reader
entry point and bypassed that prerequisite. The repair reuses its fact custody
and cleanup but adds purpose-specific fresh graph admission and real ownership
publication. Saved-reader behavior remains unchanged.

## Lifecycle contract

Only the pinned `NaturalSpawner.spawnCategoryForPosition` call to
`ServerLevel.addFreshEntityWithPassengers` is wrapped. No-passenger insertion
continues directly. For a passenger-bearing call:

1. Capture each exact native object once in deterministic preorder, including
   its parent edge, ordered direct children, native incarnation, UUID, runtime ID,
   world instance, and chunk position. Reject cycles, duplicate nodes, foreign
   worlds, removed nodes, incorrect reverse edges, and existing identity collisions.
2. Validate every graph position against the existing active owner closure.
   No owner is inferred from the parent's position, no closure is expanded, and
   no owner wait or natural-spawn replay is introduced.
3. Publish scalar facts/routes through the existing input batch, and admit each
   exact ownership incarnation through `ObjectOwnershipRegistry`. Retain its
   owner, routing key, object epoch, topology generation, and regional owner epoch.
   Phase is `IDENTITY_PUBLISHED_NOT_TRACKED`. This does not insert an entity,
   publish an interaction-index target, start tracking, or run mob/projectile policy.
4. Execute the original native insertion call once. At native tracking HEAD,
   validate the exact graph and enter `TRACKING_STARTED`; existing addressability
   and tracking logic then run. Only native callback RETURN enters
   `TRACKING_PUBLISHED`. Duplicate starts within the admission fail closed.
5. Parent passenger projections and retained relation validation check the exact
   graph and ownership receipts while admission is pending. An unrelated parent
   cannot borrow a pending passenger's identity. Validate again after the original
   call returns, then release admission custody.

An exact native pointer plus native incarnation identifies the entity. The UUID
is a collision/registry key only. Replaced pointers, changed native incarnations,
detached edges, world changes, removed nodes, ownership incarnation/epoch changes,
and topology changes remain failures. Prepublication is never full tracking.

The original inserted storage prefix survives partial native failure, including
hidden members whose tracking is legitimately delayed by visibility. Unconsumed
nodes lose temporary routes, recover prior facts, and retire only their admitted
ownership incarnation. Cleanup can retire the same pending incarnation after its
route/epoch changes, but cannot retire a replacement incarnation using the same
UUID. Removal cancels pending graph use; `finally` settles unused members. Native
world close and finishing-service shutdown explicitly retire outstanding graph
admissions. Late lifecycle hooks consult existing services without resurrecting a
stopped service. Normally inserted entities remain under existing storage/removal
and server-shutdown ownership cleanup.

## Bounded diagnostic

`PHOENIUM_PASSENGER_IDENTITY_FIRST_FAILURE` is emitted at most once per finishing
service lifetime. It includes root/passenger type and exact object identity,
native incarnation/runtime ID, nesting depth, world, pending-admission presence,
publication phase, admitted and current ownership records (route, incarnation,
object epoch, owner and topology generation), and the exact lookup/validation
reason. Missing data is explicitly absent/unavailable. Successful admission emits
no diagnostic. Exceptions are not suppressed and unresolved passengers are not
filtered out.

## Verification

Final focused run: **189 tests, 24 suites, zero failures/errors/skips**.
Final broader deterministic run: **1,118 tests, 127 suites, zero failures/errors/skips**.
Evidence is under this checkout's
`run/stage7-passenger-identity/deterministic/{focused-final-xml,broad-final-xml}`.
The first broad attempt lacked ignored C2ME binary fixtures (24 failures); the
same fixtures were copied from the parent candidate checkout and the suite passed.
Historical fixture directory names containing `stage8` are inputs to existing
deterministic tests; no Stage 8 run was performed.

The new 22-test class covers native stream order, a real native section manager
with controlled callbacks, single and nested graphs, parent lookup before child
storage, one tracking-start each, no early lookup/index publication, exact native
incarnation and object-epoch preservation through the existing index lifecycle,
stale/replaced/detached/foreign/unrelated nodes, malformed graphs, ownership and
topology changes, partial insertion, removal, world close, shutdown, and bounded
diagnostics. Bytecode tests pin the inherited loop, recursive stream, storage /
tracking order, and the exact new injection target.

Focused regressions include regional tracking, ownership/index lifecycle,
passenger facts, natural spawning/structure continuation, managed-block poll-turn
fence, default-world authority, movement, block packet admission, and target-free
UseItem. The broader run is the complete Gradle deterministic test task.

The build manifest records the final commit/parent, clean source status, explicit
candidate path/size/SHA-256, changed files, diff check, packaging audit, and evidence
paths. Build from committed source with `gradlew clean jar`; production packaging
must contain no test, client harness, or probe classes/resources. Nothing is pushed.

## Files changed

- `src/main/java/dev/phoenium/chunk/FreshPassengerAdmission.java`
- `src/main/java/dev/phoenium/chunk/FullEntityFacts.java`
- `src/main/java/dev/phoenium/chunk/FullFinishingServices.java`
- `src/main/java/dev/phoenium/live/PhoeniumLiveRuntime.java`
- `src/main/java/dev/phoenium/mixin/EntityLifecycleMixin.java`
- `src/main/java/dev/phoenium/mixin/RegionalNaturalSpawnerMixin.java`
- `src/main/java/dev/phoenium/mixin/ServerLevelEntityLifecycleMixin.java`
- `src/main/java/dev/phoenium/ownership/ObjectOwnershipRegistry.java`
- `src/test/java/dev/phoenium/chunk/FreshPassengerAdmissionTest.java`
- `src/test/java/dev/phoenium/mixin/RegionalNaturalSpawnerDescriptorTest.java`
- `src/test/java/dev/phoenium/mixin/ServerLevelEntityLifecycleTargetTest.java`
- `docs/STAGE7_PASSENGER_IDENTITY_HANDOFF.md`

## Luna retest and limitations

The exact crashed mob type remains unknown. Native bytecode and deterministic
execution establish the ordering defect; real-client natural-spawn acceptance
remains Luna's next gate. Other fresh-graph insertion call sites are outside this
narrow repair. No Bedrock client, Via, voice, Stage 8, or Stage 9 is run by Astra.
Pearl/portal testing remains paused. Accepted provider binaries remain unchanged.

Use a fresh isolated server with the new explicit candidate and unchanged accepted
providers. Initially no pearls or portals:

1. Join, verify chunks render, move across chunk boundaries, break/place blocks,
   interact with mobs, drop/pick up items, and chat.
2. Stay connected through repeated natural spawning.
3. Disconnect and reconnect. Repeat movement and block interaction, then stay
   connected through natural spawning again.

PASS requires no passenger identity/route exception or first-failure diagnostic,
no server crash, zero controlled block-packet rejections, healthy movement,
chunks/mobs/items, successful reconnect, and no ownership violation, barrier,
fallback, or unleased regression. Do not resume pearl/portal tests before this gate.
