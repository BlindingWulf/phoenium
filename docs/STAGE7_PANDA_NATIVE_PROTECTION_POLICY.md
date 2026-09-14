# Stage 7 Panda-derived native protection policy

Updated: 2026-09-14

## Result

The projectile cap and per-player/dimension ender-pearl cap retain their
accepted production boundaries. A3 now implements bounded-delay smoothing of
selected inactive mob decisions, with every native vehicle tick and passenger
operation preserved. Exact acceptance evidence and the final artifact are in
[STAGE7_A3_ACCEPTANCE_REPORT.md](STAGE7_A3_ACCEPTANCE_REPORT.md).

No Panda implementation code or runtime JAR is introduced. Formal Stage 8 and
Stage 9 remain **NOT STARTED**; the next bounded Stage 7 card is REAL_CLIENT_GATES.

## Source behavior retained only as intent

The supplied Panda source is an older behavior reference. Its useful intent is narrow:

- bound large projectile populations;
- bound one player's outstanding ender pearls;
- reduce dense mob/vehicle tick pressure while guaranteeing continued service.

The native policy deliberately does not preserve the source's unsafe mechanics: dimensionless lag buckets, wall-clock polling as correctness, arbitrary iteration-order eviction, repeated removal of the first projectile, `remove()` followed by redundant `discard()`, unbounded static area caches, unpruned region maps, or selective private-field timer mutation as an assumed substitute for a complete entity tick.

## Global invariants

1. All three protections are opt-in. The default profile is behavior-preserving and cannot reduce vanilla simulation.
2. Policy identity never grants mutation authority. `RegionDirectory`, current object ownership, owner epochs and active leases remain authoritative.
3. Every entity mutation must execute under the entity's current owner after normal Phoenium revalidation. No policy ledger may retain a stale right to remove or skip an entity.
4. No owner waits synchronously for another owner. Cross-owner effects, if later implemented, must be one-way immutable work that is re-resolved at delivery.
5. World identity is part of every scope. Nothing in the Overworld consumes a Nether budget or vice versa.
6. Cleanup uses object identity plus incarnation where lifecycle reuse is possible. Stale cleanup may not erase a later binding.
7. Deterministic order is admission sequence, then stable object identity. Collection iteration order is never a policy decision.
8. Stop/world-close cleanup must leave no retained policy work or membership after the corresponding runtime lifetime ends.

## Projectile cap

### Identity

The native area is the **current Phoenium `RegionId`**, which already contains `WorldId`. The cap key is:

`(RegionId, projectile type)`

Using the current ownership region avoids creating Panda's second static pseudo-region grid and keeps any eventual removal owner-local. A topology transition changes which area owns the projectile; policy state must be rebuilt or transferred only after the normal Phoenium ownership transition is valid.

### Exact policy

- The feature is disabled unless an operator explicitly enables it and supplies a positive cap.
- The cap applies independently per projectile type inside one current ownership region.
- When membership exceeds the configured cap, keep the newest `cap` live members and select exactly the oldest excess members for cleanup.
- Oldest means lowest admission sequence; ties use stable object identity/incarnation.
- Cleanup is one discard-style lifecycle action under the projectile's current owner. Do not perform a second removal call and do not manufacture hit/death effects.
- Removal, unload, failed admission and ownership transition must retire the exact old membership before a later incarnation can occupy the same logical identity.

### Production boundary

The runtime ledger is now wired to the accepted non-player relocation lifecycle.
`Entity.setPosRaw` publishes the routing observation, the owner-free boundary
commits the relocation, and derived projectile membership transfers only after
the new owner is authoritative. Cleanup is re-resolved through the current owner
and exact object incarnation, so stale discard work cannot remove a later
binding. Real-client W1/W4 evidence is retained at
`build/projectile-cap-live/stack16-w1/evidence-w1/PROJECTILE-CAP-LIVE.json` and
`build/projectile-cap-live/stack16-w4/evidence-w4/PROJECTILE-CAP-LIVE.json`.

## Per-player ender-pearl cap

### Identity

The cap key is:

`(WorldId, player UUID)`

It is intentionally independent of the pearl's current region. A pearl crossing region ownership must remain in the same player's same-dimension budget. Pearls owned by different players or in different dimensions never share a budget.

### Exact policy

- The feature is disabled unless an operator explicitly enables it and supplies a positive cap.
- On overflow, keep the newest `cap` outstanding pearls and select exactly the oldest excess pearls for cleanup.
- A player disconnect does not silently clear still-live pearl membership; reconnect with the same UUID observes the same loaded-world membership.
- Pearl removal or world unload retires exact membership. Restart reconstruction must derive membership from accepted loaded entities rather than trusting a stale persisted policy counter.
- Non-player/unowned pearls are outside the per-player policy unless a later requirement explicitly includes them.

### Ownership representation

The global per-player requirement can be represented without a second ownership system, but not with the current indexes alone. A future implementation needs a **derived membership ledger** containing only immutable association facts such as player UUID, world, pearl object identity/incarnation and admission sequence. It must never store mutation authority or a permanent owner.

Any selected excess pearl is then submitted through current object-owner resolution and revalidated at execution. If the pearl has moved or disappeared, the stale removal is rejected rather than executed under old authority. This is one-way owner work; no owner-to-owner blocking wait is permitted.

That derived ledger and its restart/lifecycle reconstruction are now production
machinery accepted by the PANDA_A2_LIVE real-client W1/W4 receipts. Evidence is
retained at `build/panda-a2-live/final-6194-w1/evidence-w1/PANDA-A2-LIVE.json`
and `build/panda-a2-live/final-6194-w4/evidence-w4/PANDA-A2-LIVE.json`.

## Mob and vehicle staggering

### Accepted A3 boundary — 2026-09-14

The user explicitly approved bounded-delay mob smoothing and excluded a hard
ceiling on arbitrary vehicle-density cost. This intentionally supersedes the
original density-ceiling wording. The architecture trace is preserved in
[STAGE7_A3_ARCHITECTURE_DECISION.md](STAGE7_A3_ARCHITECTURE_DECISION.md).

`MobDecisionMixin` wraps both full selector calls in `Mob.serverAiStep` with an
owner-mob context. `MobDecisionGoalSelectorMixin` consults the scheduler only at
the inactive `WrappedGoal.canUse` call inside `GoalSelector.tick`, and only for
exact `NearestAttackableTargetGoal`, `RandomStrollGoal` and
`WaterAvoidingRandomStrollGoal` classes. The supported exact vanilla mob dispatches
are zombie, skeleton, creeper, cow, pig, sheep and chicken. Unknown subclasses,
other goals, and mobs riding or carrying passengers retain native acquisition.

Running-goal cleanup, continuation checks and ticks remain native. Brains,
sensing, movement, navigation/pathfinding, temptation and other cooldowns,
breeding, growth, effects, damage, despawn and every ordinary entity tick continue.
No navigation recalculation is deferred by A3. Delaying selected acquisition can
change its timing and random-number consumption; the preservation claim concerns
the explicit per-tick boundary, not identical target/wandering histories.

### Configuration, regional accounting and deadlines

- Default: `phoenium.protection.mobDecisions.enabled=false`; both A3 mixins are
  omitted entirely by the mixin plugin.
- Enable explicitly with `-Dphoenium.protection.mobDecisions.enabled=true`.
- Positive `-Dphoenium.protection.mobDecisions.threshold=64` is the default density
  threshold. A population at or below it is exempt. Acceptance uses 4 to activate
  bounded fixtures. Settings are read at runtime construction/startup.
- Scope: current Phoenium `RegionId` (including dimension), plus hostile/passive
  category. This is derived accounting, never ownership authority.
- Four cohorts are balanced by assigning each member to the smallest cohort;
  removal/relocation rebalances by at most one move. Stable cohort sizes differ
  by at most one. UUID hashing does not choose the service phase.
- Both selectors share one gate decision per mob tick. With native full-selector
  opportunities every two ticks, cohort service recurs within eight ticks and
  adds at most six ticks from the first deferred eligible call.
- The first-deferred entity tick survives relocation, rebalance, repeated
  observations and world-time changes. An overdue call passes immediately. The
  guarantee applies to native eligible opportunities; A3 does not tick unloaded
  or inactive mobs or bypass goal flags to manufacture an opportunity.
- Exact native and registry incarnations prevent stale retirement/relocation from
  changing replacement membership. Unload/removal retires it; loaded entities
  reconstruct it after restart. Stop clears every membership and cohort index.
- Metadata uses bounded per-member state and per-region cohort indexes. There is
  no new global gameplay lock, global entity scan, retained gameplay callback or
  owner-to-owner wait. Cumulative scalar diagnostics may remain after stop.

### Vehicles, provider compatibility and limits

Every vehicle tick, movement/rail operation, collision-query result, collision
pair, push and passenger pickup/lifecycle remains native. A3 introduces no vehicle
scheduler or cap. Lithium's accepted minecart and navigation hooks remain in
place; the enabled/disabled loaded instruction comparison and live motion/pickup
comparisons are recorded in the acceptance report. The approved saved-passenger
repair publishes only the exact native load batch's input facts before parent
tracking; it adds no global vehicle scan or second ownership model.

Arbitrary vehicle-density cost is **not hard-bounded by A3**, because doing so
would require a separate gameplay-changing admission or interaction policy. No
such policy is introduced. Mob smoothing also does not promise a fixed CPU ceiling
for unrestricted population. Six ticks is a deferral bound, not a time budget.

The accepted ServerCore activation/skip/breeding/lobotomize configuration remains
unchanged. Representative navigation, timers, hostile acquisition and breeding
workloads pass; this does not certify every modded AI subclass or technical farm.

## Executable adversarial requirements

`NativeProtectionPolicyTest` proves the policy surface without changing the runtime:

- protections are inert by default;
- projectile budgets isolate dimension, region and projectile type;
- overflow selects exactly the oldest excess members deterministically;
- equal-sequence ties do not depend on collection order;
- pearl budgets isolate player and dimension while remaining region-independent;
- exact-incarnation cleanup cannot delete a later binding;
- players, controlled vehicles and below-threshold populations are never staggered;
- stride is explicitly bounded;
- every stable entity receives exactly one slot per stride window, including an adversarial group.

These specification tests remain useful adversarial coverage; the live
projectile and pearl acceptance is recorded separately in the receipts above.
The legacy UUID-phase model is historical specification coverage only. Production
A3 uses `MobDecisionSchedulerTest`, native-boundary tests and the W1/W4 runtime
receipts; no UUID-phase model is wired into gameplay.

## Stage 7 decision

The bounded production decisions are:

- projectile cap: **RUNTIME PASS** for `(RegionId, projectile type)` scope, exact
  relocation/incarnation handling and owner-routed cleanup;
- per-player pearl cap: **RUNTIME PASS** for `(WorldId, player UUID)` scope,
  reconnect preservation and deterministic exact-once cleanup;
- A3: **RUNTIME PASS** for selected bounded-delay mob decisions and preserved
  vehicle/passenger semantics, with the explicitly approved arbitrary-density
  limitation above.

No Phoenium ownership/lifecycle invariant is weakened to make the protection item appear complete.
