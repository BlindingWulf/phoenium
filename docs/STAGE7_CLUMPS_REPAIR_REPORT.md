# Stage 7 Clumps Merge/Removal Lifecycle Repair

Date: 2026-09-10

Status: **SCOPED PASS** for the locked Clumps 26.2.1 heterogeneous XP-orb merge, pickup/Mending, explicit removal, save/restart, and W1/W4 lifecycle path. This is not a claim about every future Clumps release or a mass-farm performance result.

## Locked inputs

- Phoenium implementation commit: `b394978e349d3ca05c49bcdb789d24ecf8193aa6`
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`
- Production SHA-256: `0B80154F4AA99E0D833C5DC119A39A2087AF6CDA3B2AC286DAD0477E3F5867C4`
- Clumps: `Clumps-fabric-26.2-26.2.1.jar`
- Clumps SHA-256: `DC9544190ABFB65A3C9DDC5DAD1523C9DFC5A732344F528D908B950894398E8C`
- Fabric API: `fabric-api-0.159.0+26.2.jar`
- Fabric API SHA-256: `3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE`
- Console plan: `scripts/stage6-plans/xp.json`
- Plan SHA-256: `94FC988DE72F27F7D03901B02528AB1449AFF20CC4D680750FFFBBB1101D0C75`

## Historical failure and exact contract

The Stage 6 W1 and W4 Clumps samples failed on the first heterogeneous XP-orb merge with `tracking alias lacks current owner-published identity and route` from `FullEntityFacts.trackingCandidate`. The corresponding Fabric + Clumps control intentionally produced one orb worth 10 XP with `clumpedMap {3:1,7:1}`; vanilla orb-count parity is therefore not the compatibility contract.

Inspection of the locked Clumps bytecode showed that its merge keeps the receiving `ExperienceOrb`, folds the other orb's value/count map into it, and calls `discard()` on the other orb during the same entity tick. Minecraft's `EntityTickList` copy-on-write removal keeps the old iteration snapshot alive, while native `ServerLevel` checks `entity.isRemoved()` before doing any further per-entity work. Phoenium had entered `FullEntityFacts.trackingCandidate` before that native removed-entity guard. Removal callbacks had already retired the discarded orb's route, so the old tick-list snapshot looked like a stale live alias even though native Minecraft would skip it.

The repair restores that native consumer boundary in `RegionalChunkGameplayService.entityTicks`: aliases already marked removed are skipped before owner-published tracking facts are required. Live aliases still go through the same strict current-owner identity/route validation. No new ownership registry, scheduler, lock, fallback, or Clumps-specific identity exception was introduced.

## Focused regression

`RegionalEntityTickLifecycleTest` uses a real `EntityTickList` snapshot. The first entity removes a second orb while iteration is active; the second alias therefore remains in the old snapshot but is already marked discarded. The test requires that only the live first entity reaches the regional tracking path. `FullEntityFactsTest` remains green, preserving rejection of invalid live aliases.

Focused result: PASS.

## Installed W1/W4 and persistence evidence

Post-commit evidence is in:

- `run/stage7/clumps-postcommit-w1/`
- `run/stage7/clumps-postcommit-w4/`

Both reports identify source head `b394978e349d3ca05c49bcdb789d24ecf8193aa6` and production SHA `0B80154F4AA99E0D833C5DC119A39A2087AF6CDA3B2AC286DAD0477E3F5867C4`. W1 and W4 each pass the fresh phase and restart phase with exit code 0.

The fresh and restarted worlds retain exactly one live XP orb with total XP 10 and Clumps' heterogeneous value map `clumpedMap {3:1,7:1}`. Shutdown health is clean: duplicate, bypass, fallback, ownership-violation, unleased, and barrier counts are all zero. W4 exercises the same path with Phoenium regional parallelism enabled.

## Real-client pickup, Mending, and removal

`run/stage7/clumps-client-w1/` uses the existing account-free real TCP client harness with a hash-locked Clumps/Fabric API selection. A Clumps-only test fixture runs before the ordinary Stage 4 trace is armed, so the normal Stage 4 trace contract is unchanged when the fixture is disabled.

Observed provider receipts:

- Mending fixture starts with a diamond pickaxe at damage 100 and zero player XP.
- Values 3 and 7 merge to one live orb with total XP 10.
- The connected player picks up that merged orb; the client receives the native take/remove entity packets.
- Mending reduces pickaxe damage from 100 to 80; zero live XP orbs remain after pickup.
- A second 3+7 merge again produces one XP-10 orb, then explicit removal leaves zero live XP orbs.

The surrounding Stage 4 first trace reports PASS, the server exits 0, and the restart evidence records the expected unpack delay 186 plus before/deadline/after native tick receipts at game times 1143/1144/1145. Both first and restart shutdowns have zero duplicate, bypass, fallback, ownership-violation, unleased, and barrier counts.

## Regression and deterministic suite

Because this repair changes the shared entity-tick consumer boundary, the ServerCore preservation-profile W4 installed sample was rerun at `run/stage7/servercore-post-clumps-w4/`; both fresh and restart phases PASS against the repaired production JAR.

The complete deterministic suite after the repair is **845 tests across 90 suites**, with zero failures, errors, or skips.

## Scope boundary

This closes the reproduced Clumps entity merge/removal ownership failure for the locked 26.2.1 build and exercises the intended heterogeneous merge semantics, persistence, pickup, Mending, explicit removal, W1, W4, and an overlapping ServerCore lifecycle path. Large XP farms, every possible orb topology, future Clumps versions, and performance benefit remain outside this scoped acceptance.

