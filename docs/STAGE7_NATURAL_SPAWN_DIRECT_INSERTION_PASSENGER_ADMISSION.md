# NATURAL_SPAWN_DIRECT_INSERTION_PASSENGER_ADMISSION

Status: implementation and deterministic verification complete; committed candidate identity and
packaging receipt are recorded under `run/stage7-natural-spawn-direct-passenger/`. This is repair
readiness for Luna, not installed-server or real-client acceptance.

Baseline: `f7e3661e17052ac81a1b369d1941a417bf7ccfdf` on
`stage7/passenger-identity-handoff`, parent
`8b094b7df947f5de273d6ff5de7abdb6178356de`. The retained failed artifact is unchanged at
1,637,232 bytes and SHA-256
`85A2F69DFB1D08532C75C4BBE627D18BC86C06288A5CA01829FE04AB1C98B715`.

## Root cause and exact lifetime

The first live diagnostic is exact: a new Chicken (`809`) was synchronously tracked with a Drowned
passenger (`808`) inside `Zombie.finalizeSpawn`, reached from `Drowned.finalizeSpawn`. The previous
repair began only at `NaturalSpawner`'s later `addFreshEntityWithPassengers(drowned)` call. That
call is too late because `addFreshEntity(chicken)` has already executed the Chicken's tracking
callback, and the Drowned has no pending admission, fact, route, or object ownership at that time.

Pinned Minecraft 26.2 order:

| Order | Native action | Passenger-admission state |
|---:|---|---|
| 1 | `NaturalSpawner` creates and positions the outer Drowned candidate. | An outer purpose-scoped lifetime is open; it has published nothing. |
| 2 | `NaturalSpawner` calls `Mob.finalizeSpawn` once. | The exact candidate is marked as currently finalizing. |
| 3 | `Zombie.finalizeSpawn` creates/finalizes a Chicken and calls `drowned.startRiding(chicken)`. | The relationship exists, but neither new object has been inserted. |
| 4 | The pinned direct hook surrounds `ServerLevelAccessor.addFreshEntity(chicken)`. | The exact Chicken/Drowned graph is validated and identity/fact/route custody is published before the call. |
| 5 | `addFreshEntity(chicken)` stores the Chicken and synchronously runs its tracking callback. | Chicken enters the real tracking phases; the callback sees the Drowned's identity-only facts while the Drowned remains uninserted. |
| 6 | The direct call returns, then `Zombie.finalizeSpawn` and `Drowned.finalizeSpawn` return. | Custody remains live; it is deliberately not retired at the nested direct-call return. |
| 7 | Native cluster/group counts are incremented, then `addFreshEntityWithPassengers(drowned)` runs. | The same admission is reused; no second ownership registration occurs. |
| 8 | The Drowned's real storage/tracking callback executes once. | Drowned advances through actual tracking phases; no early visibility/ticking/index insertion was manufactured. |
| 9 | The bulk call returns, or throws/rejects after a native prefix. | The admission settles: inserted members remain native-owned; only unused exact provisional publications retire. |
| 10 | Any earlier return/throw, world close, or service shutdown exits the outer scope. | Orphaned admissions retire with exact-incarnation cleanup; late scope close is idempotent and cannot resurrect state. |

`Drowned.finalizeSpawn` has the analogous Zombie Nautilus vehicle insertion. A bounded scan of the
six top-level classes in `net.minecraft.world.entity.monster.zombie` found direct finalization
insertions only in `Zombie`, `Drowned`, and `Husk`. `Husk.finalizeSpawn` also has a later Parched
helper bulk insertion. The same admission may therefore extend with newly attached exact members,
and the rare native vehicle replacement reuses the candidate's existing publication rather than
registering it twice. These exact three finalization methods are covered. Structure/worldgen calls
whose accessor is not a `ServerLevel`, non-natural invocations with no active outer scope, direct
non-passenger insertion, conversions, commands, saved loads, portals, projectiles, and arbitrary
`ServerLevel.addFreshEntity` callers are explicitly outside this hook.

Both natural-spawn executors are covered: the pinned native
`NaturalSpawner.spawnCategoryForPosition` method and Phoenium's retained
`NaturalSpawnContinuation` implementation. The latter opens the same scope around its existing
finalize/count/bulk sequence; its query/poll fence, program counter, RNG calls, mob-cap counters,
callback order, and return behavior are otherwise unchanged.

## Contract

- `FreshPassengerAdmission.NaturalSpawnScope` is a thread-local stack only for the synchronous
  native attempt. Nested same-world attempts share one state/generation and exact member map.
- A direct hook can publish only when its inserted root contains the exact candidate currently in
  `finalizeSpawn`. It delegates unchanged otherwise.
- Publication uses the existing loaded-input facts/routes and `ObjectOwnershipRegistry` admission.
  It does not add native storage, visible lookup/index membership, ticking, interaction policy, or
  tracking state.
- Validation retains native object pointer/incarnation, UUID collision key, runtime ID, world,
  position/route, object ownership/epoch, topology generation, parent edges, and ordered children.
- The later bulk call looks up the exact candidate object in the outer scope. A replacement object,
  even with the same UUID, cannot consume the old admission.
- Native insertion calls, results, callbacks, exceptions, and random draws are not replayed or
  suppressed. Cleanup aggregates secondary failures without replacing the original failure.
- World close and finishing-service shutdown close the existing admission; a later scope callback
  observes the closed state and cannot republish it.

The first-failure diagnostic now includes `entryPath`, admission identity, outer generation,
nested depth, publication phases, and current scope exit state. Aggregate per-service counters
record outer scopes, direct preparations, accepted/rejected native returns, settlement, and
successful later upgrades. The first successful upgrade emits one bounded
`PHOENIUM_PASSENGER_IDENTITY_DIRECT_PATH ... result=UPGRADED_AND_SETTLED` line per finishing-service
lifetime; it retains no world object.

## Regression evidence

The retained `f7e3661` live run is the red witness, and
`nativeOrderingWithoutAdmissionReproducesMissingPassengerFacts` deterministically reproduces its
missing-fact condition. The repaired regression enters through the production outer-finalization
and direct-insertion adapter, executes the real `PersistentEntitySectionManager` with synchronous
callbacks, observes the uninserted passenger from root tracking, then performs the delayed native
bulk insertion. It is controlled native/fixture evidence, not installed-runtime or unmodified
natural-gameplay proof.

Coverage includes direct root tracking, delayed outer insertion, the original bulk path,
nested/reentrant scope reuse, exact vehicle replacement, the Husk helper extension, same-UUID
replacement isolation, non-passenger delegation, false return, partial insertion, thrown failure,
detachment/foreign-world/wrong-route/stale topology and ownership rejection, removal, world close,
shutdown, late callbacks, first-failure diagnostics, and bounded success telemetry. Native bytecode
tests pin the three direct call sites, attachment-before-insertion order, Husk's helper order,
NaturalSpawner finalize/bulk/callback order, concrete descriptors, and production mixin entries.

Final deterministic counts, commit, candidate hash, packaging inventory, and Luna commands are in
the adjacent ignored run receipt so the source commit does not self-reference its own hash.
