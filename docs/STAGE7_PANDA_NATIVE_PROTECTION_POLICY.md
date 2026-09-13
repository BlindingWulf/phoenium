# Stage 7 Panda-derived native protection policy

Updated: 2026-09-13

## Result

The Panda-derived protection item has an executable **DESIGN PASS**. The
per-region/type projectile cap and per-player/dimension ender-pearl cap now have
bounded production runtime acceptance at the real Minecraft client/server
boundary. Mob/vehicle staggering remains design-only and unwired.

Stage 7 now has explicit ownership-safe requirements for the three desired behaviors: per-area projectile caps, per-player ender-pearl caps, and bounded mob/vehicle staggering. The deterministic policy tests live in `src/test/java/dev/phoenium/stage7/NativeProtectionPolicyTest.java` with a test-only model in `NativeProtectionPolicyModel.java`.

No Panda implementation code is copied, no Panda runtime JAR is introduced, and no live Minecraft behavior changes in this checkpoint.

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
8. Stop/world-close cleanup must leave no retained policy work, membership or counters after the corresponding runtime lifetime ends.

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

### Identity and fairness

The density scope is the current `RegionId`. Classes are explicit: monster, peaceful mob and vehicle; players, unknown/unclassified entities and controlled vehicles remain every-tick.

For an enabled category above its configured threshold, the policy computes a positive stride bounded by an explicit maximum. Each entity gets a stable phase from UUID and must receive exactly one service slot in every stride-sized window. This provides a finite starvation bound independent of iteration order or runtime entity ID reuse.

### Preservation boundary

The scheduling rule is testable, but the current `RegionalChunkGameplayService.entityTicks` seam executes the original whole entity tick. Simply omitting that callback would slow more than AI: movement, breeding/age, despawn state, vehicle physics and entity-specific timers can all change. Panda's selective private-field compensation is version-fragile and is not accepted as a correctness proof for Minecraft 26.2.

Therefore Stage 7 does **not** wire whole-entity staggering into production yet. A later implementation must either identify a narrower supported simulation boundary or prove a complete 26.2 compensation contract with farm/AI/vehicle acceptance before enabling it.

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
They do not accept mob/vehicle staggering, which remains unwired.

## Stage 7 decision

The design requirement is closed for the two bounded live protections:

- projectile cap: **RUNTIME PASS** for `(RegionId, projectile type)` scope, exact
  relocation/incarnation handling and owner-routed cleanup;
- per-player pearl cap: **RUNTIME PASS** for `(WorldId, player UUID)` scope,
  reconnect preservation and deterministic exact-once cleanup;
- mob/vehicle staggering: **DESIGN PASS / RUNTIME UNWIRED**; implementation still
  waits for a simulation-preserving 26.2 tick boundary or proved compensation
  contract.

No Phoenium ownership/lifecycle invariant is weakened to make the protection item appear complete.
