# Stage 7 A3 semantic boundary decision

Date: 2026-09-13. Baseline: `2109c01` on
`stage7/scalablelux-persisted-light-repair`. Formal Stage 8: **NOT STARTED**.

## Approved implementation scope — 2026-09-14

The user explicitly approved bounded-delay mob decision smoothing with every
native vehicle tick, collision and passenger operation preserved. Arbitrary
vehicle-density cost is intentionally not hard-bounded by A3. This supersedes
the scope question in the historical investigation below; no new vehicle policy
is required or authorized.

Choose the narrowest candidate: wrap only the inactive `WrappedGoal.canUse`
invocation inside `GoalSelector.tick`, and only for the exact three reviewed
goal classes. A context around `Mob.serverAiStep`'s full-selector calls identifies
the owner mob; it does not cancel either selector. All path-navigation methods
remain untouched, preserving synchronous callers and Lithium navigation hooks.
Unknown mob dispatch classes, unknown goal subclasses, and mounted/carrier mobs
are exempt. Start with explicit vanilla zombie, skeleton, creeper, cow, pig,
sheep and chicken dispatches; expand only with separate evidence.

Use four balanced regional/category cohorts, assigning to the smallest cohort
and rebalancing a removal by moving one member from the largest cohort if needed.
Each native full-selector opportunity samples the shared world two-tick slot.
The first eligible acquisition call in a mob tick determines its gate; both
selectors share that result. A maximum of six additional entity ticks from the
first deferred call overrides the cohort. Keep that first-deferred tick through
relocation/rebalancing; repeated observations cannot reset it. Entity tick-count
differences survive world-time changes and integer wrap. Native opportunities
that do not occur (inactive AI/chunks, goal flags) cannot be manufactured; when
an overdue eligible call next occurs it must pass immediately. No successful
random decision or path existence is guaranteed.

Bookkeeping consists only of exact native-incarnation memberships, registry
incarnation provenance, regional cohort indexes and bounded counters. Per-key
metadata updates never execute gameplay and never hold two region keys together.
No global gameplay lock, owner wait, extra global entity scan or deferred entity
callback is added. Density is refreshed on addressability, committed relocation,
and owner-revalidated native acquisition; topology reassignment is reconciled
when a member next becomes eligible. Retirement and stop prune all membership.

This is the implementation decision. Final runtime disposition and exact evidence
are recorded in `STAGE7_A3_ACCEPTANCE_REPORT.md`; the investigation below is
historical and does not supersede that disposition.

## Separately approved prerequisite repairs — 2026-09-14

The user explicitly approved both repairs after the accepted `6194` artifact
reproduced the blocking cases. No provider binary was changed.

1. **World publication.** Persisted force-load tickets can initiate C2ME reads
   before `SERVER_STARTED`. Lazy producer initialization competed with the
   coordinator to build `LinkedHashMap` world/service state and complete directory
   views, causing duplicate-world or missing-dimension-route failures. The existing
   `ServerLevel` constructor-return hook now initializes chunk authority on the
   coordinator before native ticket activation. Producers can only consume a
   completed world. Services and routes precede the concurrent world-map release
   publication; different native instances of the same dimension still fail.
   No new global lock, provider wait or owner-to-owner wait is added.
2. **Saved passenger inputs.** Native `processPendingLoads` and legacy chunk
   insertion consume a finite saved-reader stream in parent-before-passenger
   order. Parent tracking projects its indirect passenger range before later
   readers enter storage, so their routing facts were absent. The wrapper
   materializes that exact original stream once and publishes temporary scalar
   identity/route facts under the existing named reference-access turn. This
   creates no UUID-directory entry, visible selector candidate or object owner.
   Native insertion order and callbacks execute unchanged. Actual storage/lookup
   insertion retains the facts; unused or rejected readers lose temporary routes
   and recover prior reference facts in `finally`, including partial failure.
   This is load-batch input custody, not a global vehicle scan or a second
   ownership system. Tracking still encloses and revalidates every passenger's
   region and exact incarnation; it never drops an unresolved collision or pair.

Focused regressions cover publication-only producer access, world-instance
rejection, nested passenger inputs, preserved native insertion prefixes,
foreign-world rejection, unused-reader cleanup, reference-fact restoration and
same-UUID isolation. Final live acceptance must retain forced tickets in both
dimensions and mounted passengers across restart, superseding the exploratory
unload-before-stop workaround. Failure evidence is retained under
`build/a3-live/baseline-restart-w1/`, `baseline-passenger-reload/` and the initial
unaccepted matrix runs.

## Readiness regression found by the required client gate — 2026-09-14

The projectile W1 regression on intermediate artifact `2A381042...660F1`
encountered `live chunk readiness changed before gameplay` during dimension
transfer. Its completed receipt observed a native C2ME slot that was replaced
before the coordinator's later assertion. This is an observation, not a lease
that prevents native producer changes. The owner-free coordinator now refreshes
the receipt immediately before chunk gameplay, waiting on newly observed original
futures if necessary. It retains the completed distance-update prefix and does
not replay callbacks, cancel futures, manufacture success, or wait under an owner.
A deterministic native-shaped C2ME slot-replacement test covers the interval
after `await` returns, including rejection of owner-held refreshes. Both W1 and
W4 real-client projectile regressions passed on the repaired artifact. This
narrow repair follows the concrete-regression exception in the original mission.

## Historical investigation — 2026-09-13

The remaining sections record the evidence and open scope at the initial
architecture checkpoint. The user resolved the vehicle scope and approved the
two prerequisite repairs above on 2026-09-14.

## Provenance and disposition

The clean accepted checkout and production SHA-256 were verified before edits:
`619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD`.
No applicable `AGENTS.md` was found in the repository or its ancestor directories.
This decision precedes any A3 production edits. It is not live acceptance.

Authority for native behavior is the locally pinned, official-named Minecraft
26.2 binary supplied by Loom, Java 25. The merged compile binary at
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`
has SHA-256 `77100AC75BAE5B566EC09DE3CAC5BC6D21AEDD0557A6AA97F38984B210CDD1B2`.
Local decompiled sources in `run/full-callback-closure/source/` assist reading;
`A3NativeBoundaryTest` checks the actual classpath bytecode and executes native
goal-selector and temptation-cooldown behavior. Decompiled text alone is not the
acceptance oracle.

The existing generic staggering specification is insufficient for production.
The possible production scope is **bounded-delay selected mob decisions**.
No semantics-preserving deferred vehicle calculation was found. A fixed CPU
ceiling for arbitrary existing vehicle density requires a separate gameplay
policy; this remains an explicit scope question, not an invented permission to
drop collision responses, deny movement, destroy carts, or cap entity admission.

## Native and owner paths

`ServerLevel.tick` -> entity-list consumer -> `tickNonPassenger` -> old-position
update, `tickCount++`, virtual `Entity.tick`, recursive passenger servicing.
The native consumer also handles removal, freeze/despawn/range and vehicle state.
`RegionalChunkGameplayService.entityTicks` preserves that consumer, validates
the exact entity, vehicle and passenger facts, obtains its existing regional
closure through `executeAndDrain`, then executes the original callback. It is
not a deferral boundary. This service currently dispatches entity callbacks one
at a time; changing that scheduling is outside A3. W4 must not be described as
proof that A3 newly parallelizes entity ticks.

Addressability reaches `PhoeniumLiveRuntime.onEntityAddressable` and
`EntityIndexLifecycleAdapter.addressable`. `Entity.setPosRaw` observes movement;
cross-owner observations settle through `advancePendingRelocations` and
`ObjectOwnershipRegistry.transitionOwner` at the existing owner-free cuts.
Same-owner observations retain the object epoch. Topology reconciliation can
also change region association; any derived A3 ledger must reconcile against
current object ownership, not merely listen for physical relocation facts.
`Entity.setRemoved` calls `onEntityRemoved`; unload is a retirement edge.
Bootstrap's existing `refreshEntityBindings` reconstructs loaded addressable
entities. No new world enumeration belongs in a regional callback.

`Mob.serverAiStep` is final. It increments `noActionTime`, clears sensing caches,
alternates full selectors with `tickRunningGoals(false)` according to
`(tickCount + entityId) % 2`, ticks navigation, executes custom server AI/brains,
and ticks move/look/jump controls. Cancelling this method freezes semantic work.

`GoalSelector.tick` performs three distinct stages: running-goal validity and
stop/flag cleanup; inactive-goal acquisition/preemption; running-goal ticks.
Calling only `tickRunningGoals` omits required stop/cleanup. Deferring all
`WrappedGoal.canUse` calls is also unsafe: `TemptGoal.canUse` decrements
`calmDown`; its continuation check invokes `canUse` too. `MeleeAttackGoal.canUse`
has a game-time gate and synchronous path creation. `BreedGoal.tick` advances
love time, navigates and breeds; `Animal.aiStep` advances `inLove`, while
`AgeableMob.aiStep` advances age through `setAge`. All retain their native cadence.

`Sensing.tick` only clears the seen/unseen sets. Expensive line-of-sight work is
lazy inside `hasLineOfSight`; retaining old caches risks stale visibility and
is not selected. `NearestAttackableTargetGoal.findTarget` performs acquisition
queries after its native random gate; its generic subclasses are not presumed
equivalent. `RandomStrollGoal.canUse` makes an optional destination decision,
with a pending `forceTrigger` and native random/no-action checks.

`PathNavigation.tick` advances its tick counter, retries delayed recomputation,
follows the current path and supplies the move controller. `recomputePath` already
has `hasDelayedRecomputation` and a game-time gate of more than 20 ticks. Only
the due recalculation branch before clearing the old path is a plausible extra
deferral seam. Arbitrary `createPath` calls cannot return an asynchronous result
without changing their synchronous callers; they remain native. A recompute-only
throttle does not bound attack-goal path searches.

`AbstractMinecart.tick` advances damage/hurt state, portal and fluid work, then
delegates physics to the selected behavior. `OldMinecartBehavior.tick` evaluates
rails, movement, activation and block effects, then collision/pickup. Its query
loop calls `startRiding` or `push`. Experimental `NewMinecartBehavior` evaluates
rails per movement step; pickup and push return values influence immediate
remaining movement through `AbstractMinecart.move`. `pushOtherMinecart` reads
and writes both carts' current velocities: repeated pair visits are not a
duplicate event that can simply be discarded. Furnace, hopper, TNT and container
cart specializations add fuel, inventory, ignition or other lifecycle work.
Boats likewise couple buoyancy, input, movement, collision and passengers.

The accepted installed ServerCore `1.5.19+26.2` binary has separate activation
and inactive-selector/mob paths, including its tick-count redirection. Its
preservation configuration, rather than merely the presence of the mod, is
therefore part of the A3 contract. The installed Lithium `0.25.3+mc26.2` binary
updates navigation active/inactive registration from the current path, optimizes
minecart pushable queries using entity-section caches, and maintains movement
listeners for hopper interactions. A new recomputation or cart optimization
must preserve these callbacks and be tested on the transformed composition.
Vanilla bytecode guards do not establish provider-composition acceptance.

## Candidate contract (not yet production or live accepted)

1. **Exact seams.** Full-selector inactive acquisition calls for exact reviewed
   goal implementations only: `NearestAttackableTargetGoal`, `RandomStrollGoal`
   and `WaterAvoidingRandomStrollGoal`. Unknown subclasses are exempt. Optional
   second seam: the due branch of `PathNavigation.recomputePath`, before the
   first write clearing `path`, using native delayed-recompute state. Neither
   candidate authorizes skipping a selector, AI step or entity tick.
2. **Every tick.** Original entity lifecycle, movement/physics, passengers,
   sensing invalidation, navigation following, custom brain work and controls.
   Running goals, validity, stop and flag cleanup retain their original native
   cadence, including the half-rate selector distinction.
3. **Deferred work.** Only selected *inactive* acquisition attempts and optionally
   already-requested path recalculation. Breeding, temptation, combat execution,
   active navigation and unknown goal logic remain native. A decision can start
   later under opted-in overload; exact acquisition timing parity is not claimed.
4. **State/clocks.** `gameTime`, `tickCount`, `noActionTime`, damage/effects,
   cooldowns, portals, despawn, growth, love/breeding and goal execution clocks
   continue normally. No private timer compensation is allowed. A pending
   optional decision is policy state, never a reason to freeze semantic clocks.
5. **Fairness.** Use owner-region derived membership, balanced admission cohorts
   and a per-incarnation last-service/deadline record. UUID hashing alone can put
   every entity into one slot. Deadline enforcement must survive phase changes
   and relocations. Repeated observation must not reset a deadline.
6. **Maximum delay.** Proposed fixed maximum four native full-selector
   opportunities (normally eight ticking game ticks between service slots,
   at most six added ticks for a newly due inactive decision); optional navigation
   recomputation adds at most three ticking game ticks. Define and test service
   opportunities separately from successful random AI actions. Native freeze,
   non-ticking chunks and disabled AI are not time serviced by A3.
7. **Identity.** Density scope is `(current RegionId, mob category)`; `RegionId`
   contains `WorldId`. Membership uses UUID plus exact `FullEntityFacts` native
   incarnation. The object registry's incarnation is a different domain and must
   not be substituted. Scheduling metadata carries no mutation authority.
8. **Cleanup.** Exact native-incarnation retirement on removal/unload, empty-scope
   pruning and complete server-stop cleanup. No queued native aliases or deferred
   cross-owner callbacks are needed for an in-place decision predicate.
9. **Movement.** Transfer derived density only after current ownership commits.
   Reconcile topology reassignment against current ownership. Preserve accumulated
   deferral across relocation; never let A->B->A reset it indefinitely. No
   owner-to-owner wait or new ownership protocol.
10. **Unload/reload/restart.** Retire old membership. Reconstruct from normal loaded
    addressability with fresh native incarnation; persist native entities and AI
    state, not scheduling authority. A first eligible service must be bounded
    after re-admission. Stale retirements cannot remove replacements.
11. **Vehicles/passengers.** Preserve all vehicle simulation and pickup/collision
    work. Exempt mounted mobs and mobs carrying passengers from selected decision
    deferral until a separate mounted-AI contract is proved. No cart/boat tick
    suppression or passenger lifecycle skipping.
12. **ServerCore.** The accepted live stack has `activation-range.enabled=false`,
    `skip-non-immune=false`, breeding caps and villager lobotomization disabled.
    Preserve that configuration. A3 cannot compensate for another mod suppressing
    the outer tick; enabled ServerCore activation is outside this acceptance
    profile and cannot inherit a per-game-tick A3 service guarantee.
13. **Farms.** Leave breeding, growth, temptation, feeding, harvesting, inventory
    and vehicle mechanics intact. Delay only the selected optional decisions at
    above-threshold density. Real farm/breeding and navigation witnesses remain
    necessary: a source-level argument does not certify every technical farm.
14. **No starvation.** Per-entity deadlines override cohort phase when needed;
    exact-incarnation lifecycle and transfer rules preserve those deadlines.
    Test adversarial identity/arrival/removal/relocation schedules. A finite
    service interval does not guarantee a random goal succeeds or finds a path.

Default must be inert, with an explicit enable flag and positive threshold.
Population accounting is lifecycle-derived and region-local; no global hot-path
scan, global gameplay lock, global serialization, deletion or blocking peer wait.
This is a design for a qualified subset, not a claim that every expensive AI
operation has become budgeted.

## Vehicle anti-pathology limit and required policy distinction

An AABB/spatial index can reduce irrelevant candidates. It cannot bound the
number of actual overlapping carts in a cell. For an existing pile of N carts,
native behavior can examine N(N-1) directed pair candidates per tick, and many
of those visits change the current velocities. Caching across ticks, skipping
visits, capped query results, or pair deduplication changes those mechanics.
No such vehicle change is approved here.

More generally, a hard limit of B serviced decisions each tick plus service of
each of N entities at least once within D ticks requires N <= B*D. A finite
maximum delay cannot also maintain a fixed per-tick budget for unrestricted N.
Every normal semantic entity tick already carries population-dependent work.
This is different from bounding extra delay and reducing/smoothing selected
work, which is achievable without a hard population-independent CPU ceiling.

The correct boundary for a hard vehicle-density policy would be explicit
admission/backpressure covering spawning, unloading/reloading, persisted worlds,
cross-region entry and passengers, with an operator-visible disposition for
over-cap existing populations. That would change gameplay and needs a separate
policy. This document does not implement or silently authorize it. Profiling
may identify exact semantics-preserving optimizations, but those should be
accepted as specific optimizations, not called a hard bound on arbitrary piles.

## Verification and remaining gates

`A3NativeBoundaryTest` has 13 focused characterization tests. They cover actual
native selector execution/cleanup, temptation cooldown progression, pinned mob
and navigation seams, breeding/growth boundaries, both cart implementations,
passenger/movement coupling and an adversarial UUID-phase concentration. The
existing 10 `NativeProtectionPolicyTest` tests also pass. These do not substitute
for production scheduling tests or live A3 activation evidence.

A3 remains **UNACCEPTED**. Required future proofs include implementation-level
default/below-threshold behavior, two regions/dimensions, fairness and deadlines,
all lifecycle/incarnation transitions, native AI/navigation/timers/despawn/farms,
vehicles/passengers, and real bounded W1/W4 first/normal-stop/restart/unload runs.
Selected-work counters must show activation and comparable control reduction.
Empty safety counters alone do not pass the gate. No Stage 9 benchmarking or
formal Stage 8 acceptance is authorized by this decision.

Fresh full deterministic suite: **973 tests / 110 suites / 0 failures / 0 errors /
0 skipped**, from `.\gradlew.bat test --rerun-tasks --no-daemon`, exit 0.
Fresh `.\gradlew.bat jar --rerun-tasks --no-daemon`, exit 0, reproduced the exact
accepted 1,457,625-byte production JAR and SHA-256 above. Production sources,
configuration and mixin metadata are unchanged. No A3 implementation, runtime
acceptance, performance result or W1/W4 activation receipt exists for this
architecture checkpoint. No acceptance commit or work-card advance was made.

Local evidence root: `build/a3-architecture-20260913/`. It contains
`native-bytecode.txt`, `servercore-bytecode.txt`, `lithium-bytecode.txt`,
`full-test.log`, `jar-build.log`, the copied JUnit XML results, and
`manifest.json` with artifact and evidence hashes. The boundary characterization
can be rerun with:

```powershell
.\gradlew.bat test --no-daemon --tests dev.phoenium.stage7.A3NativeBoundaryTest --tests dev.phoenium.stage7.NativeProtectionPolicyTest
```
