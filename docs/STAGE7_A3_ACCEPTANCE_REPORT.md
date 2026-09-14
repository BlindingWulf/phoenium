# Stage 7 A3 acceptance report

Date: 2026-09-14. **A3 ACCEPTED — RETURN TO LUNA**.
Next work card: **REAL_CLIENT_GATES**. Formal Stage 8 and Stage 9: **NOT STARTED**.

## Accepted artifact and scope

- Branch: `stage7/scalablelux-persisted-light-repair`.
- Starting accepted HEAD: `2109c011fec6b2e12aaa3cc1a49436ceea3835b5`.
- Production: `build/libs/phoenium-0.2.0-stage2.jar`, **1,477,787 bytes**.
- SHA-256: `4857C0400CFE4D3015EC9628EEA0530F5150E8D7CC4B488BF694EB808A2D7E82`.
- The coherent local acceptance commit contains this report, implementation,
  deterministic/native tests, runners and handoff. Resolve its exact HEAD with
  `git log -1 --format=%H`; the post-commit `build/a3-live/closure-manifest.json`
  records the same HEAD, artifact and source/evidence hashes. No remote push.

The user explicitly approved bounded-delay mob decision smoothing while preserving
all vehicle ticks. Excluding a hard ceiling on arbitrary vehicle-density cost is
an intentional scope clarification, not an incomplete implementation. The user
also explicitly approved the two baseline-reproduced startup/passenger repairs.
The architecture trace, approval boundary and repair details are preserved in
[STAGE7_A3_ARCHITECTURE_DECISION.md](STAGE7_A3_ARCHITECTURE_DECISION.md).

## Exact mechanism and guarantee

The only delayed work is the inactive `WrappedGoal.canUse` invocation within
`GoalSelector.tick`, for exact `NearestAttackableTargetGoal`, `RandomStrollGoal`
and `WaterAvoidingRandomStrollGoal` classes. The owner context encloses both full
selector calls from `Mob.serverAiStep` without cancelling either selector. Exact
vanilla zombie, skeleton, creeper, cow, pig, sheep and chicken classes qualify.
Unknown dispatches/subclasses, other goals and mounted/carrier mobs are exempt.

The scheduler derives `(current RegionId including WorldId, hostile/passive)`
membership from the existing addressability, committed-relocation and removal
lifecycle. It stores identity/incarnation and scalar bookkeeping, not entity
aliases or authority. Admission chooses the smallest of four cohorts. Removal
and relocation rebalance by at most one member, maintaining cohort sizes within
one in a settled scope. It performs no population scan during gameplay and does
not use UUID phases. The first qualifying call caches one result for both
selectors for that mob tick.

With the native full-selector opportunity every two ticks, four cohorts give
service within eight ticks and **at most six additional entity ticks** from the
first deferred eligible call. The pending deadline survives repeated observation,
cohort rebalance, region relocation and world-time changes; wrapping native
entity tick arithmetic is tested. An overdue next eligible call runs immediately.
A3 does not manufacture opportunities for unloaded/inactive mobs or bypass native
goal flags. The bound does not guarantee a random goal succeeds or finds a path.

Exact native and object-registry incarnations fence stale removals and moves.
Unloaded/removed entities retire membership; restart reconstructs it from native
loaded entities. Stop clears members and cohort indexes. Per-key metadata updates
never execute gameplay or hold two regional keys simultaneously. No new global
gameplay lock, global vehicle/entity scan, owner-to-owner wait or ownership model
is introduced. Diagnostics snapshots are explicitly outside the gameplay path.

Defaults are inert: `phoenium.protection.mobDecisions.enabled=false`, and the
mixin plugin omits both A3 mixins. Explicitly enable with
`-Dphoenium.protection.mobDecisions.enabled=true`. The positive threshold property
`phoenium.protection.mobDecisions.threshold` defaults to **64**; at/below threshold
native acquisition continues. The live fixtures use **4** to exercise activation
with bounded populations. Configuration is read at startup; no new vehicle option.

## Preserved semantics and vehicle limitation

Running-goal cleanup, continuation and ticking remain native. Movement, brains,
sensing, effects, growth, breeding, despawn, damage/portal/temptation cooldowns,
navigation/pathfinding and ordinary entity ticks continue. No navigation method
is delayed. Selected acquisition timing and random-number consumption can change;
A3 does not promise identical wandering/target histories.

Every vehicle tick, rail/movement operation, collision-query result, collision
pair, push, passenger pickup and passenger lifecycle remains native. There is no
vehicle throttler, pair dropping, query cap or delayed pickup. The six loaded
vehicle/navigation exports compare identically between A3 enabled and disabled
once only Mixin-generated identifiers are normalized. Lithium's pushable-entity
minecart and active/inactive navigation hooks remain present. Both pinned native
cart implementations are additionally covered by the bytecode boundary tests.

**Pathological vehicle-density cost is not hard-bounded by A3 because doing so
would require a separate gameplay-changing policy.** No such policy is introduced.
Mob smoothing likewise cannot make unrestricted population cost constant. The
six-tick limit bounds added decision delay, not CPU milliseconds. Representative
farm and hostile workloads pass; this is not certification of every technical
farm, modded AI subclass, or experimental vehicle scenario.

## Deterministic and packaging evidence

`.\gradlew.bat test jar a3Harness --no-daemon` completed successfully:
**1,003 tests / 112 XML suites / zero failures, errors or skips**. Evidence:
`build/a3-live/closure-build.log` and `build/a3-live/closure-junit/`.

Coverage includes 21 scheduler tests (4,096 adversarial same-hash identities,
balanced regional/dimensional cohorts, threshold/defaults, concurrent regions,
removal, relocation/deadlines, reincarnation, time changes and integer wrap),
13 pinned native-boundary tests (actual selector cleanup and temptation cooldown,
brains/timers/navigation seams and vehicle/passenger coupling), two world
publication tests, six additional saved-input lifecycle tests, and the new
post-await C2ME-slot replacement regression. Existing A1/A2/projectile and C2ME
coverage ran in the full suite.

`closure-packaging-audit.json` is PASS: server-only metadata, pinned dependencies,
no client mixins and no test/harness classes or references in production. The
artifact diff against accepted `6194` is retained in `closure-artifact-diff.json`.
Only the separate test harness carries native observation hooks. `git diff
--check` passed. Source/evidence hashes and process audit are in the manifest.

## Native W1/W4 acceptance

The exact same production bytes run with A3 enabled and disabled on the accepted
provider composition. Each campaign includes a first lifetime and normal
save/stop/restart with forced tickets in both dimensions and a mounted passenger
retained across restart. All eight native server processes exit normally.

| Workers | Selected calls enabled / control | Reduction | Peak per two ticks enabled / control | Maximum observed acquisition gap | Hostiles acquiring targets |
|---|---:|---:|---:|---:|---:|
| W1 | 1,620 / 6,312 | 74.33% | 75 / 235 | 8 ticks | 15 |
| W4 | 1,635 / 6,343 | 74.22% | 75 / 235 | 8 ticks | 19 |

Counts cover the comparable 80-tick concentrated acquisition window. These are
work-count comparisons, not CPU timing or Stage 9 benchmarks. Bounded safety
runs may overlap other verification; no wall-time performance claim is made.

Positive native assertions in both enabled runs include:

- Below-threshold mobs and independent regional/dimensional scopes retain native
  acquisition; 32 concentrated hostiles activate balanced cohorts.
- All 80 ordinary mob AI/navigation/despawn checks continue in the measured
  windows. Explicit navigation obtains a path and moves a calf more than one
  block; its age advances 80 and its effect duration decreases 80.
- A 24-cow farm-like population continues breeding and creates exactly one tagged
  newborn, with native parent cooldown and love reset. Hostiles acquire targets.
- Committed cross-region relocation preserves UUID and native incarnation while
  changing scheduling scope; duplicate observations do not duplicate membership.
- The separate two-cow pen actually unloads, reaches zero native and scheduler
  presence, then reloads the same UUIDs with fresh native incarnations. A bounded
  read-only absence poll observes completion; elapsed wall time is not the proof.
- Seventeen minecarts execute every one of 32 ticks (**544 vehicle ticks**),
  including a 16-cart overlap. Native pig pickup occurs by tick 2. Moving-cart,
  rider positions/velocities and dense-cart positions match disabled controls
  within 1e-6; the moving cart travels more than five blocks after pickup.
- All **82 persisted entities**, passenger edges, growth/effect clocks and loaded
  scheduling membership survive restart. Post-restart hostiles receive all 16
  requested ticks and decision service. Final entity/policy cleanup reaches zero.
- Every final health receipt has zero duplicate, bypass, fallback, ownership,
  unleased and barrier errors; pending relocations and lifetime resources retire.

Evidence roots are `build/a3-live/final-a3-w1/`, `final-a3-w4/`,
`final-a3-control-w1/` and `final-a3-control-w4/`. Each contains installed inventory,
exact plan, first/restart logs, reports and native snapshots. Enabled roots also
contain `validation.json`, `comparison.json` and `vehicle-export-audit.json`.

## Approved prerequisites and required regressions

The accepted `6194` jar reproduced concurrent lazy world registration during
persisted forced-chunk startup and parent tracking before saved passenger input
identity was published. After explicit approval, world services/routes are now
published on the coordinator before ticket activation; producers consume only
completed worlds. The exact native saved-reader batch publishes temporary input
facts before unchanged native parent-first insertion. Rejected/unused inputs
restore prior facts; no visible candidate or owner is manufactured.

The required intermediate projectile W1 client gate also exposed a stale C2ME
readiness observation at dimension transfer. The coordinator now refreshes
original futures before gameplay, preserving the completed prefix and native
producer authority. It never waits under an owner or manufactures successful
chunk results. The final real-client regressions all pass on the artifact above:

| Gate | W1 evidence | W4 evidence | Result |
|---|---|---|---|
| PANDA_A2_LIVE | `build/a3-live/closure-client-a2-w1/PANDA-A2-LIVE.json` | `build/a3-live/closure-client-a2-w4/PANDA-A2-LIVE.json` | Both `acceptancePass=true`, clean client/server exits |
| PROJECTILE_CAP | `build/a3-live/closure-client-projectile-w1/PROJECTILE-CAP-LIVE.json` | `build/a3-live/closure-client-projectile-w4/PROJECTILE-CAP-LIVE.json` | Both `acceptancePass=true`, real TCP movement and dimension transfer |
| C2ME coinstall first/restart | `a3-closure-coinstall-w1/` | `a3-closure-coinstall-w4/` | All four server processes pass and exit normally |

C2ME roots are under
`run/stage8/entry-20260913/astra-c2me-provider-settlement/`; that historical folder
name does not start formal Stage 8. Each worker's coinstall first/restart receipts
have 2,193 / 1,225 applied DISK actions; restart's native observer confirms 1,225
DISK and 95 FULL requests completed successfully. Retained C2ME state, send pins,
input custody and lifetime indexes are zero. This directly affected regression
preserves the existing broader settlement2 acceptance rather than reopening it.

Pinned provider SHA-256 values remain unchanged:

- C2ME settlement2: `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`.
- ScalableLux: `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`.
- Lithium: `FDDE92E238E8075F89AD7F701F2A3D5854AF88BA9A67657184A4407B104AC563`.
- ServerCore: `4F7C9FB3B35B364D8F6500D04F0D5481656F617A83BC69742B47F39ADFF59666`.

Accepted ServerCore activation/skip/breeding/lobotomize settings remain unchanged.
The inventory pins every installed artifact; no provider JAR was edited.

Failures are retained, not counted as passes: baseline startup/passenger
reproductions; intermediate `client-projectile-w1/` readiness failure; and
`closure-w1/`, whose fixed unload sleep ended before native unload. The final
runner polls the read-only absence condition with a 90-second bound and retains
all original assertions. No production edit was needed for that fixture repair.

## Reproduction and Luna continuation

From the repository, use fresh output directories:

```powershell
.\gradlew.bat test jar a3Harness --no-daemon
python scripts/run_a3_live.py --output build/a3-live/recheck-w1 --workers 1 --port 25649
python scripts/run_a3_live.py --output build/a3-live/recheck-control-w1 --workers 1 --enabled false --port 25649
python scripts/validate_a3_live.py --enabled build/a3-live/recheck-w1 --control build/a3-live/recheck-control-w1
python scripts/audit_a3_vehicle_exports.py --enabled-run build/a3-live/recheck-w1 --control-run build/a3-live/recheck-control-w1
```

Repeat with workers 4 and fresh W4 paths. Client regressions accept the explicit
`phoenium.panda.expectedProductionSha256` JVM property; its historical default
remains the accepted 6194 pin. Every final receipt checks the actual new jar hash.

Luna's first command:

```powershell
Get-Content 'C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium\docs\STAGE8_ENTRY_CURRENT_WORK.md'
```

Continue **REAL_CLIENT_GATES**: prepare and execute bounded real Bedrock,
legacy-Java/Via and two-client voice facility gates as available, preserving
accepted A3/A2/projectile/C2ME receipts. Do not infer gameplay or microphone
acceptance from startup/protocol-only evidence. Do not reopen accepted mechanics
without a concrete regression. Formal combined-stack Stage 8 acceptance and
Stage 9 benchmarking remain unstarted.
