# Stage 8 entry current work

Updated: 2026-09-14. This remains a Stage 7 continuation. Formal Stage 8 and
Stage 9 are **NOT STARTED**.

## WORK CARD

Active milestone: **REAL_CLIENT_GATES**.

**A3 ACCEPTED — RETURN TO LUNA**. The user-approved bounded-delay mob mechanism,
vehicle/passenger preservation and separately approved startup/passenger repairs
passed deterministic and native W1/W4 acceptance. Read the authoritative
[A3 acceptance report](STAGE7_A3_ACCEPTANCE_REPORT.md) for the exact boundary,
configuration, limitations and evidence; the earlier architecture investigation
is historical. There is no pending scope question.

Production `build/libs/phoenium-0.2.0-stage2.jar`: **1,477,787 bytes**, SHA-256
`4857C0400CFE4D3015EC9628EEA0530F5150E8D7CC4B488BF694EB808A2D7E82`. The coherent local commit containing this report is the A3 checkpoint;
`git log -1 --format=%H` and `build/a3-live/closure-manifest.json` identify its HEAD.
Full suite: **1,003 tests / 112 suites / zero failures, errors or skips**.

Enabled/disabled W1/W4 native roots: `build/a3-live/final-a3-w1/`,
`final-a3-w4/`, `final-a3-control-w1/`, `final-a3-control-w4/`. All first/restart
lifetimes pass, including actual unload/reload and saved mounted passengers.
The final artifact also passes real-client A2 and projectile W1/W4 regressions
under `build/a3-live/closure-client-*/`, and directly affected C2ME coinstall
first/restart W1/W4 regressions. Packaging and vehicle/navigation export audits
pass. Provider artifacts and accepted ServerCore settings remain unchanged.

A3 defaults off; explicit enable uses
`-Dphoenium.protection.mobDecisions.enabled=true`, with a positive threshold
(default 64). Four balanced regional/category cohorts delay only three reviewed
inactive goal-acquisition classes, by at most six additional eligible entity ticks.
All ordinary ticks, navigation, timers, vehicles, collisions and passengers remain
native. Arbitrary vehicle-density cost is explicitly **not hard-bounded by A3**;
a separate gameplay-changing policy would be required and is not introduced.

Exact Luna continuation:

> Continue REAL_CLIENT_GATES from docs/STAGE8_ENTRY_CURRENT_WORK.md. Preserve the
> accepted A3, PANDA_A2_LIVE, PROJECTILE_CAP and pinned C2ME settlement2 + ScalableLux
> receipts. Prepare bounded real Bedrock, legacy Java/Via and two-client voice
> facility gates as available. Do not claim gameplay/audio acceptance from
> startup-only evidence. Do not start formal Stage 8 or Stage 9.

First local command:

```powershell
Get-Content 'C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium\docs\STAGE8_ENTRY_CURRENT_WORK.md'
```

The following prior receipts preserve their original artifacts and dates; their
older A3 continuation instructions are superseded by this work card.

## Completed bounded card — PANDA_A2_LIVE

PANDA_A2_LIVE passed on 2026-09-13 with the same final production bytes in both
worker configurations. The run used an actual Loom/Fabric Minecraft client over
TCP to a separate dedicated server; the server retained the pinned C2ME
settlement2 and ScalableLux artifacts. The production JAR SHA-256 is
`619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD`.

* W1 evidence: `build/panda-a2-live/final-6194-w1/evidence-w1/PANDA-A2-LIVE.json`.
* W4 evidence: `build/panda-a2-live/final-6194-w4/evidence-w4/PANDA-A2-LIVE.json`.
* Both receipts report `acceptancePass=true`, an empty `failure`, `cap=2`,
  clean real-client termination, clean server stop, and the exact pinned C2ME
  SHA-256 `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`
  plus ScalableLux SHA-256
  `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`.
* The C2ME-only production checkpoint recorded in the prerequisite section is
  historical; the final Stage 7 runtime artifact used by these receipts is
  `build/libs/phoenium-0.2.0-stage2.jar`, 1,457,625 bytes, SHA-256
  `619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD`.
* The accepted matrix covers ordinary below-cap gameplay, exact boundary,
  oldest-overflow selection, duplicate idempotence, exactly-once overflow
  cleanup, natural removal retirement, disconnect/reconnect preservation,
  `UNLOADED_WITH_PLAYER`, cross-region identity and owner re-resolution,
  cross-dimension independent scope/overflow, and zero stale membership at
  teardown. The runtime diagnostics show zero ownership violations and no
  pending entity-relocation or pearl-cleanup residue.

## Completed bounded card — PROJECTILE_CAP

PROJECTILE_CAP passed on 2026-09-13 at the same real client/server boundary and
with the same production, C2ME and ScalableLux bytes as PANDA_A2_LIVE.

* W1 evidence: `build/projectile-cap-live/stack16-w1/evidence-w1/PROJECTILE-CAP-LIVE.json`.
* W4 evidence: `build/projectile-cap-live/stack16-w4/evidence-w4/PROJECTILE-CAP-LIVE.json`.
* Both receipts report `acceptancePass=true`, an empty `failure`, `cap=2`,
  clean client/server termination, `projectileMemberships=0`,
  `pendingProjectileCleanups=0` at final cleanup, and the exact production SHA
  `619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD`.
* The workload proves below-cap retention, exact-boundary retention, ordinary
  client movement, native raw cross-region relocation with owner convergence,
  duplicate re-observation idempotence, deterministic oldest overflow with one
  discard, natural removal retirement, independent `(RegionId, projectile type)`
  scopes, cross-dimension overflow, reconnect, and zero stale membership at
  teardown. W4 ran with `parallel=ON` and both runs ended with the expected
  fail-closed stale movement replay dropped during native dimension transition
  (`packets.move.stale=1`), without a server crash or ownership violation.
* The focused Java regression set and `CoreAcceptanceTest` pass after the live
  runs. The live fixture uses only real client item use for projectile creation;
  its test-only capture command freezes an entity that the client actually
  spawned and does not synthesize projectiles.

PROJECTILE_CAP is a bounded Stage 7 result. A3 staggering remains unaccepted,
and no formal combined-stack Stage 8 matrix has started.

## Accepted C2ME prerequisite

**C2ME_UPSTREAM_SETTLEMENT RESOLVED — C2ME_LIFETIME ACCEPTED — RETURN TO LUNA**

Final acceptance: 2026-09-13. The native settlement patch and explicitly approved
Phoenium DISK lifecycle and SEND_READY dependency repairs passed the complete
literal W1/W4 matrix, including the full selected overlapping Stage 7 stack.
All 12 actual-production first/restart pairs pass, with all 24 server processes
exited. Production C2ME exclusions were removed only after candidate acceptance;
the final matrix uses the actual production JAR directly.

* Phoenium implementation commit: `916904794590433ccac1777c24ffa023b7af4338`.
* Branch: `stage7/scalablelux-persisted-light-repair`.
* C2ME-only prerequisite production checkpoint
  `build/libs/phoenium-0.2.0-stage2.jar`: **1,433,247 bytes**, SHA-256
  `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`.
* Required patched C2ME: **settlement2**, SHA-256 `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`.
* Full Phoenium regression: **953 tests / 108 XML suites / 0 failures / 0 errors /
  0 skipped**. Native full suite: **19 tests / 4 suites**, all pass; all **11**
  settlement contract tests also pass against the packaged patched provider.

The [final settlement report](STAGE7_C2ME_SETTLEMENT_REPORT.md) records the exact
composition, per-run positive and terminal counts, regression/build commands,
source provenance, process audit and hypothesis history. The
[provider patch manifest](STAGE7_C2ME_PROVIDER_PATCH_MANIFEST.md) pins native
commits, build inputs and output bytes. This supersedes the original blocked
disposition and old continuation instructions preserved below. The unchanged
characterization test still demonstrates the defect in the original C2ME JAR;
its pass does not accept that original JAR. The production binding requires the
distinct settlement2 base version. No remote push was made.

## Remaining scope and preserved prerequisites

A1 non-player cross-owner relocation remains implemented and verified through
the owner-free transition boundary. A2's live pearl workload and the separate
projectile runtime cap are accepted. A3 is now the active bounded card; its
simulation-preserving native seam remains to be designed and accepted.
Real Bedrock gameplay, legacy Java/Via gameplay and two-client voice
microphone/proximity/reconnect coverage remain external-facility gates.
VMP remains optional and blocked/partial; SLO strict seed parity remains excluded;
FastNoise attribution remains bounded by its documented nondeterministic control.
ServerCore acceptance remains its preservation profile. Prior scoped Chunky,
ScalableLux, Lithium, Clumps and Geyser/Floodgate results remain preserved.

## Historical work-card archive — not current instructions

The entire prior entry record follows unchanged to retain its diagnostic and
prerequisite history. Its active-card labels, old HEAD/hash, BLOCKED disposition,
retained breaks and continuation commands describe earlier checkpoints only.
The WORK CARD and accepted disposition above are authoritative.

# Stage 8 entry current work

Updated: 2026-09-13

Status: **C2ME_LIFETIME BLOCKED — EXACT ARCHITECTURE BLOCKER. Formal combined-stack Stage 8 acceptance has NOT started.**

Baseline branch: `stage7/scalablelux-persisted-light-repair`.

## WORK CARD

Active milestone: C2ME_LIFETIME
Exact gate: exact locked C2ME + ScalableLux/Starlight composition passes ReadFromDisk lifetime, cancellation, unload/replacement, failure, stop settlement, save/restart, co-install, and literal W1/W4 proofs
First missing proof: native terminal-failure settlement of requested DISK/dependent status futures. The locked C2ME MARK_BROKEN path releases action/busy state but leaves those futures pending; later requests remain pending and unload substitutes a generic failure. Native characterization and exact W1/W4 failure trials reproduce this blocker. Full unload/replacement, stop and co-install acceptance remain unproved.
Files allowed: C2ME/ScalableLux disposable server evidence, existing C2ME adapters/observers, focused tests, and this current-work/handoff record; production `breaks` stays intact until acceptance
Success criteria: exact provider hashes/configs; positive ReadFromDisk and persisted-light execution; cancellation before/after admission; unload/replacement; bounded failure and stop settlement; zero safety violations/residue; final production bytes verified before gate removal
Stop/escalation: classify ownership/lease/cancellation/unload/stop authority as ASTRA-HARD; preserve an escalation packet if unresolved; do not remove C2ME `breaks`, enable A3, start combined Stage 8, or claim reduced-stack acceptance

## Astra final disposition — 2026-09-13

The authoritative decision is [STAGE7_C2ME_LIFETIME_DECISION.md](STAGE7_C2ME_LIFETIME_DECISION.md); the escalation remains **BLOCKED**. This disposition supersedes the provisional design and earlier continuation instructions below. The operation identity is the exact native action token/context/backing holder/DISK slot plus Phoenium lineage. Token cancellation is a request; acknowledgement requires the cancelled token and a CancellationException through the provider's error channel. Rx disposal is not authority. The existing `c2meControls` counter covers generation graphs, not disk-operation cancellation.

Candidate v3 proved the bounded cancellation witness at W1 and W4: one matching successful token cancellation and one CANCELLED terminal, 119 APPLIED, 120/119 read registration/application counts, exit 0 and zero final retained/safety counters. But both exact failure trials propagated one FAILED operation and ended through the watchdog (exit 1). The independent `C2meNativeFailureSettlementTest` proves that native MARK_BROKEN settles busy/action state while DISK/FULL requesters remain pending, including a later request; removing all tickets substitutes UNLOADED_EXCEPTION. Thus logging the original failure and retiring Phoenium custody cannot establish provider-wide failure/stop settlement.

The smallest required change belongs to the provider's transaction/future authority: atomically record the exact failed action/status generation, fail its affected requester slots with the original cause outside holder locks, define subsequent requests on the broken holder, and preserve native dependency/ticket/unload semantics. Phoenium cannot safely implement that contract using its own work/custody/dispatcher primitives alone. See the decision for exact bytecode and executable evidence.

All experimental production changes were withdrawn after archiving exact v3 source, manifest and candidate bytes in `run/stage8/entry-20260913/astra-c2me-lifecycle/`. `git diff -- src/main` is empty. Luna's initial dirty observer/runner/plans are preserved; the successful-cancel identity receipt remains test-only. The native failure characterization test is retained. HEAD remains `31fea7d45b6f161fa3897d894cb016d89e1dcd79` on the original branch; no completion commit or push was made.

Final full regression passed: **930 tests, 104 XML suites, 0 failures, 0 errors, 0 skipped; exit 0**. The final production build passed and reproduced **1,403,887 bytes**, SHA-256 `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`. Both production C2ME breaks remain, with no observer or experimental classes in the JAR. No project server remains running.

The modified-byte persistence W4 trial missed its required reload marker and is not a pass. Full replacement/unload, stop and co-install matrices were not completed after the native blocker was established. Historical receipts remain historical; none clear the gate for the withdrawn candidate. Keep this card at C2ME_LIFETIME BLOCKED, not PANDA_A2_LIVE.

First next command for Luna, from this repository, reproduces the blocker characterization:

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --tests "dev.phoenium.chunk.C2meNativeFailureSettlementTest"
```

Do not rerun unchanged installed witnesses or restore v3 as accepted production. Resume the complete lifetime matrix only after the native failure-settlement contract is addressed.

## Recovered completed work

The approved local-Qwen route through `C:\AI\Models\qwen-subagent.ps1` was verified before this continuation; its receipts remain under `run/stage8/entry-20260911/`. No Qwen or GVS5H files were changed.

C2ME readiness status-slot identity, pre-FULL generation/status, FULL/SEND_READY lifecycle binding, and provider autosave serial admission are implemented as bounded prerequisites. Their historical receipts, locked-provider hash, and earlier suite totals remain in `docs/C2ME_*_MILESTONE.md` and the 2026-09-11 entry records.

The current committed continuation adds the locked-provider ReadFromDisk/async parse/POI/cancellation/lease-release bridge and the empty-provider-queue discriminator. The production gate is unchanged; installed provider-wide acceptance is not claimed.

A1 non-player cross-owner relocation is implemented and verified. It commits only at the owner-free boundary through `ObjectOwnershipRegistry.transitionOwner()`, emits one relocation fact, and remains the source for future per-region accounting.

VMP remains optional and blocked/partial; SLO strict seed parity remains excluded; FastNoise attribution remains bounded by its documented nondeterministic control; prior scoped Chunky, ScalableLux, Lithium, ServerCore, Clumps and Geyser/Floodgate repairs are preserved.

## Entry gates still open after this continuation

1. C2ME exact provider W1/W4 persistence is positive on the disposable no-break candidate, but cancellation, unload/replacement, provider-wide co-install, the bounded-failure terminal outcome, and final-production-byte acceptance remain required before removing `breaks`.
2. A2 is locally engineering-ready but lacks live pearl overflow/removal/reconnect workload acceptance; the separate projectile runtime cap and A3 staggering remain unaccepted.
3. Real Bedrock gameplay, legacy Java/Via gameplay, and two-client voice microphone/proximity/reconnect coverage remain external-facility gates.

VMP is optional. No Stage 8 combined-stack acceptance has started.

## Client preparation already established

Windows `Microsoft.MinecraftUWP` version `1.26.4501.0`, Java Edition, and the authorized Moto G 2025 (`[device-serial]`, Android 16) were observed. The phone lacks `com.mojang.minecraftpe`; Windows Bedrock is only a candidate facility. Evidence: `run/stage8/entry-20260911/CLIENT_ENVIRONMENT_PREP.md`.

## Historical continuation checkpoint (2026-09-12)

Checkpoint date: 2026-09-12.

Checkout: `C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium`.

Branch: `stage7/scalablelux-persisted-light-repair`.

Final checkpoint HEAD: `169003a` (`docs: record stage8 entry handoff`).

Source/test revision: `3355f3e7761d9ab18b29a188f2602f67131b4c3a` (`stage8-entry: bind c2me read and panda pearl lifecycle`).

Working-tree disclosure: the source/test and documentation checkpoints are committed; the checkout is clean and the handoff is complete.

No reset, clean, stash, branch switch, push, release, or deletion was performed.

Protected recovery copy: `run/stage8/entry-20260911/recovery-before-continuation-20260912/`.

It contains the pre-continuation status, patch, HEAD and relevant dirty source copies; it remains retained.

### Dirty work and provenance

The committed source change contains the pre-existing C2ME persisted-read/POI/lease-release/cancellation continuation plus the Panda A2 implementation added and repaired in this campaign.

C2ME production safety metadata remains in `src/main/resources/fabric.mod.json`.

The exact declarations are still:

```json
"breaks": {
  "c2me": "*",
  "c2me-rewrites-chunk-system": "*"
}
```

The test-only C2ME observer, provider-shaped Starlight stubs and read-path probes are isolated under test/harness source sets and absent from the production JAR; the A2 card remains the only active milestone.

### Fresh verification on this revision

Command: `.\gradlew.bat test --rerun-tasks --no-daemon`.

Exit code: `0`.

Fresh XML totals: `929` tests across `103` suites; `0` failures; `0` errors; `0` skipped.

Focused regression command also passed with exit code `0`:

`.\gradlew.bat test --rerun-tasks --tests "dev.phoenium.live.PearlMembershipLedgerTest" --tests "dev.phoenium.live.PearlOwnerAccessTest" --tests "dev.phoenium.PearlCleanupRoutingTest" --tests "dev.phoenium.live.EntityRelocationBoundaryTest" --tests "dev.phoenium.chunk.C2meReadRuntimeTest" --tests "dev.phoenium.chunk.C2meReadProviderBinaryTest" --tests "dev.phoenium.service.RegionalServiceDispatcherTest"`.

`git diff --check` is clean; Git reports only its existing LF-to-CRLF working-copy notices for edited text files.

### Production artifact identity

Build command: `.\gradlew.bat jar --rerun-tasks --no-daemon`.

Build result: exit code `0`.

Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`.

Production JAR SHA-256: `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`.

Production JAR size: `1,403,887` bytes.

The JAR contains `PearlMembershipLedger`, `PearlOwnerAccess`, `C2meReadAccess`, `PrivateC2meReadInvocation`, and both `C2meReadFromDisk` mixins.

The JAR contains no `src/test` observer/probe classes; metadata identifies server environment, Minecraft `26.2`, Java `>=25`, and both C2ME `breaks` entries.

### Final-byte disposable transformed-path smoke

Candidate source: the exact production JAR above, repackaged only to remove `breaks` for isolated loader investigation.

Candidate path: `run/stage8/entry-20260912/candidates/a2-final-current/phoenium-0.2.0-stage2-a2-final.jar`.

Candidate SHA-256: `4F25BAE543C5D8D79FFAEEE20D50643BB136D79E6A62F1DFE1D995E558367590`.

The candidate runner recorded the pre-commit source HEAD `a8ad2d1598cdd7f6e48a711f22c5b8ce176e0b21` because the smoke ran before the local source commit; the selected production bytes and committed source are identical, and the committed revision is the authoritative provenance.
Candidate plan: `run/stage8/entry-20260912/a2-final-current-v2-plan.json`.

Evidence directory: `run/stage8/entry-20260912/a2-final-current-w1-v2/`.

The initial path-input attempt failed before server creation because an obsolete Fabric API path was supplied; its partial output directory was preserved. The corrected run changed only the API path and used a new output directory.

Corrected command used the frozen Fabric API `run/stage6/frozen-audit-045834b/research/artifacts/fabric-api/fabric-api-0.159.0+26.2.jar`, locked C2ME outer JAR, and `build/test-harness/c2me-path-observer.jar`.

The corrected run used W1, port `25620`, a disposable normal world, and the plan-driven first/restart sequence.

First phase: `PASS_CONSOLE_SAMPLE`, exit `0`; Phoenium stopped with `intercepted=405`, `dispatched=405`, `executed=405`, `leases=0`, and all duplicate/bypass/fallback/ownership-violation/unleased/barrier counters `0`.

Restart phase: `PASS_CONSOLE_SAMPLE`, exit `0`; Phoenium stopped with `intercepted=276`, `dispatched=276`, `executed=276`, `leases=0`, and the same safety counters `0`.

Restart evidence includes the existing-disk loaded-chunk marker `STAGE8_A2_FINAL_V2_RESTART_LOADED`.

The transformed observer saw the real locked provider's `ReadFromDiskAsync.upgradeToThis` path, async parse lambda, and `ChunkStep.apply` on C2ME worker threads.

Observed representative counts in `a2-final-current-w1-v2-observations.json`: `119` async ReadFromDisk upgrades, `476` async parse completions, and `1,102` ChunkStep.apply enter observations.

The restart log also reports the expected missing exact ScalableLux provider and fail-closed `C2ME async private DISK read requires exact ScalableLux persisted-light ownership` fallback. This is provider-wide incompatibility evidence, not a pass.

The candidate was never installed as a production artifact, and its removed metadata was never copied back into `build/libs`.

### C2ME prerequisite disposition

Accepted local engineering subset: readiness status-slot identity, pre-FULL generation/status bridge, FULL/SEND_READY lifecycle binding, and provider autosave serial admission.

Current source also adds the exact private async ReadFromDisk capability, parsed-read ingress, deferred POI service routing, cancellation/lease-release protections, and the empty-provider-queue discriminator.

Focused C2ME tests are green, and the final-byte candidate proves the mixins transform and reach the real provider path.

The installed/provider-wide gate is still blocked: exact ScalableLux provider composition is absent from the installed candidate, and the restart path falls back on the locked private-read contract.

Required missing C2ME evidence at that checkpoint remained cancellation before/after admission, unload/replacement, terminal failure/stop settlement, true save/restart state coverage under the locked provider, co-install composition, and actual W1/W4 acceptance before removing `breaks`.

The 2026-09-12 W1 smoke is not formal Stage 8 acceptance and does not remove any safety gate.

### A1 disposition

A1 is implemented and verified on the same source tree through `EntityIndexLifecycleAdapter`.

The observed non-player cross-owner route is invalidated at the derived interaction index and retained as one latest fact.

The owner-free entity-management boundary performs deterministic non-blocking source/destination selections.

The transition revalidates epoch, incarnation and destination topology, invokes the sole `ObjectOwnershipRegistry.transitionOwner()` authority path, republishes the derived index, and emits one relocation fact.

Players remain on the existing classified packet movement path.

`EntityRelocationBoundaryTest` remains green; A1 was not redone from the older blocked checkpoint.

### A2 implementation and acceptance boundary

`PearlMembershipLedger` is a synchronized derived association table only.

Its stored facts are `(WorldId, player UUID)`, pearl UUID/object identity, object incarnation, and deterministic admission sequence.

It stores no region, owner epoch, lease, position, callback, or mutation authority.

The default profile is inert: both `phoenium.protection.pearlCap.enabled` and a positive `phoenium.protection.pearlCap` are required before cleanup is selected.

Repeated addressability for the same exact identity is idempotent.

Overflow selects the oldest excess by admission sequence, object ID and incarnation, once per membership.

World and player scopes are independent; pearl movement between regions does not change the scope.

Terminal removal retires the exact `(objectId, incarnation)`.

`UNLOADED_WITH_PLAYER` follows the 26.2 vanilla special case: it marks one reconnectable derived membership, removes the old alias, lets the object binding retire, and rebinds the preserved admission sequence to the newly admitted incarnation on reconnect.

`ServerPlayer` persistence is pinned by reflection to `saveEnderPearls`, `loadAndSpawnEnderPearls`, `loadAndSpawnEnderPearl`, `ender_pearls`, and `ender_pearl_dimension`; `ThrownEnderpearl.getOwner()` and protected `Projectile` save hooks are also pinned.

Overflow cleanup is an identity-only `pearl-discard` `OwnedObjectOperation` through `ObjectOwnerGateway` with `AdmissionPolicy.REROUTE`.

Execution resolves current owner again, checks current epoch/incarnation, exact alias type, removed state, and ledger membership, then performs one native `discard()`.

Terminal stale/rejected delivery settles the retained cleanup obligation without touching the entity.

The owner-routed cross-owner regression moved an admitted pearl operation A -> B and observed one execution under B with no active lease left behind.

The deterministic ledger suite now covers disabled inertness, scope isolation, oldest-excess selection, idempotence, exact incarnation retirement, reconnect rebind, deterministic reconstruction, close cleanup, and disconnect preservation.

A2 is engineering-ready and locally verified, but live overflow/removal/reconnect behavior is not accepted because no real Minecraft client pearl workload was run in this entry campaign.

### A3 and other protection boundaries

A3 mob/vehicle staggering remains unwired.

Whole entity tick skipping, `aiStep` skipping, native freeze use, and private timer compensation remain rejected without a semantic-preservation contract.

The per-region/dimension/type projectile cap remains a separate downstream runtime gate; A1 is now ready, but no projectile cap implementation or live acceptance was added in this bounded A2 milestone.

No Panda runtime dependency, external Panda JAR, duplicate Clumps behavior, or Stage 9 optimization was introduced.

### External facilities

Windows Bedrock package `Microsoft.MinecraftUWP` remains installed at the observed `1.26.4501.0`; this is a facility lead, not gameplay evidence.

Java Edition is installed.

The authorized Moto G 2025 Android 16 device does not report `com.mojang.minecraftpe`.

No Microsoft credential, purchase, account change, untrusted launcher, or client bypass was used.

Real Bedrock gameplay through Geyser/Floodgate, legacy Java/Via gameplay, and two-client Simple Voice Chat microphone/proximity/reconnect coverage remain untested and belong to later acceptance.

### Running processes and storage

The only Java process observed after the build was the Gradle daemon; no campaign-owned Minecraft server remained running after the disposable smoke.

No broad process termination was used.

No storage inventory was run because the mandatory Stage 7 gates are not complete.

No cleanup, deletion, archive move, compression, deduplication, cache pruning, or `git clean` was performed.

## Current C2ME_LIFETIME evidence — 2026-09-13

The active WORK CARD remains `C2ME_LIFETIME`. The exact locked runtime inputs are now resolved and recorded:

- C2ME `0.4.2-alpha.0.43+26.2`, SHA-256 `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
- ScalableLux `0.3.0-alpha.0.3+26.2`, SHA-256 `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`; its `fabric.mod.json` provides `starlight`, and no separate Starlight JAR was installed.
- Fabric API `0.159.0+26.2`, SHA-256 `3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE`.
- Production JAR remains `build/libs/phoenium-0.2.0-stage2.jar`, 1,403,887 bytes, SHA-256 `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`, with both C2ME `breaks` declarations intact.
- Disposable no-break candidate remains `run/stage8/entry-20260912/candidates/a2-final-current/phoenium-0.2.0-stage2-a2-final.jar`, SHA-256 `4F25BAE543C5D8D79FFAEEE20D50643BB136D79E6A62F1DFE1D995E558367590`; it was never copied into `build/libs`.

The literal W1 and W4 redstone fixtures passed first-phase save and normal stop, then restarted the same existing world and reached the ScalableLux-backed `ReadFromDiskAsync` path. Both runs emitted the fixture load/reload/persist/off markers, exited `0`, reported positive C2ME ReadFromDisk registrations/applications (`119/119` on restart), and ended with zero retained graphs, work, custody, provider failures, health violations, duplicate applications, fallbacks, ownership violations, leases and barriers. Evidence is retained at `run/stage8/entry-20260913/c2me-exact-lifetime-redstone-w1/` and `run/stage8/entry-20260913/c2me-exact-lifetime-redstone-w4/`.

The real action-token cancellation witnesses also ran at literal W1 and W4. They held `SerializableChunkData.read` for 15 seconds on a C2ME worker, issued an external cancellation after 2 seconds, and recorded `C2ME_TEST_CANCEL attempted=49 cancelled=49 failures=0`. Both servers then settled and exited `0` with zero retained state and zero health counters. However, both final Phoenium binding receipts remained `c2meControls=0`; the cancellation did not invoke Phoenium's `CANCELLED` control path. Evidence is retained at `run/stage8/entry-20260913/c2me-exact-lifetime-cancel-cancellable3-w1/` and `run/stage8/entry-20260913/c2me-exact-lifetime-cancel-cancellable3-w4/`.

The bounded provider-failure witnesses reached the exact injected `ChunkIoMainThreadTaskUtils.drainQueue(...).enter` failure at W1 and W4, recorded one `c2meReadProviderFailures`, and retired all Phoenium retained state. Their terminal receipts were not ordinary clean passes: W1 ended with `c2meControls=2` and W4 with `c2meControls=0`, both reported `scheduler=FAILED`, and the intentionally injected failure was logged during shutdown. These are preserved as failure-propagation evidence, not acceptance. Evidence is retained at `run/stage8/entry-20260913/c2me-exact-lifetime-failure-stop-w1/` and `run/stage8/entry-20260913/c2me-exact-lifetime-failure-stop-w4/`.

The cancellation boundary is ASTRA-HARD under the campaign contract because resolving it requires choosing or changing cancellation/replay semantics and stop authority. Runtime inspection shows C2ME passes a `flowsched.scheduler.Cancellable` to `ReadFromDiskAsync.upgradeToThis`, while the current Phoenium bridge observes only Rx `Completable.doOnDispose`; the installed C2ME action token can be cancelled without disposing that Rx subscription. No production cancellation architecture was improvised. The focused escalation packet is `docs/STAGE7_C2ME_ESCALATION.md`.

The disposable observer rebuild and runner syntax checks pass, but the checkout is intentionally dirty with test-only observer/runner changes, focused disposable plans, and this evidence update. No final Stage 7 commit was created because the C2ME gate is not accepted. No formal combined-stack Stage 8 acceptance has started.

### Next three actions

1. Resolve the ASTRA-HARD C2ME cancellation/unload/replacement/stop-authority boundary, then rerun the provider-wide exact W1/W4 acceptance with the production gate still retained until final-byte verification.

2. If the C2ME gate is closed, run the real-client facility preparation and the bounded Stage 8 entry checklist; before that, do not claim Bedrock/Via/voice coverage from startup or protocol-only evidence.

3. Revisit the separate projectile-cap runtime ledger and A3 only under their explicit ownership/simulation contracts; do not add whole-tick or `aiStep` skipping.

### Handoff invariant

Formal combined-stack Stage 8 acceptance has not started.

The next owner should reread this current-work record and the Stage 7 contract before editing.

The immediate executable next action is C2ME ReadFromDisk/cancel/unload/replacement lifetime evidence under the exact locked provider, with production `breaks` retained.

## Historical provisional Astra lifecycle decision — 2026-09-13 (before production edits; withdrawn)

The final BLOCKED disposition above supersedes this proposed implementation. It is retained to preserve the required pre-edit architecture decision and hypothesis history.

Recovery evidence: `run/stage8/entry-20260913/astra-c2me-lifecycle/`. HEAD is `31fea7d45b6f161fa3897d894cb016d89e1dcd79`, branch `stage7/scalablelux-persisted-light-repair`; no staged changes or project server. The original tracked patch, all untracked diagnostic inputs, artifact hashes and process inventory are preserved there. No applicable AGENTS.md was found in this checkout or its ancestors.

The locked `StatusAdvancingScheduler.advanceStatus0` creates distinct outer, dependency and action tokens. It creates one context for the action in `lambda$advanceStatus0$1`; all async retries and the synchronous fallback share that context. The operation identity is **that action token + exact context + backing holder + original DISK slot**, not the worker or an Rx wrapper. The slot alone is insufficient: a cancelled action can be retried against the same still-requested status slot.

`Cancellable.cancel()` wins a CAS to CANCELLED and invokes an installed callback, if any. ReadFromDiskAsync ignores the token, never installs a callback, and never completes it. The provider scheduler only handles cancellation when both the action token is cancelled and the operation emits CancellationException. A normal Completable terminal still advances DISK even with a cancelled token. `subscribeOp` discards its Disposable and caches isolate subscriptions further. Rx disposal is therefore neither cancellation authority nor a valid way to settle the busy counter.

Planned minimal adaptation: retain one provider-specific operation record from upgrade construction through the exact outer Completable terminal. Observe the real token at admission, private-read return, owner execution and publication. A successful token cancellation is a request; acknowledge CANCELLED exactly once when the provider computation reaches a safe checkpoint and emits a provider-recognized CancellationException. Never dispose the computation to fake that acknowledgement. Bind token.complete() as the atomic commit arbitration immediately before the first owner-routed POI effect (or final publication when the queue is empty). Cancellation wins before that commit; after commit the token rejects cancellation and the operation must finish or fail. No lock spans a provider call. Private ScalableLux persisted-light state belongs to the unpublished chunk; its vanilla retain/queue calls are no-ops under the exact engine.

Replacement/unload: native ticket changes invoke outer-token cancellation through tickHandleBusy0/tryCancelAction; finished downgrade invalidates status futures and eventual holder removal closes the holder. Revalidate the exact provider holder/slot and Phoenium lifecycle/work before commit. A fresh native action gets a fresh operation even when it reuses a requested slot. A retired operation cannot recreate graph/work entries or take the empty-input branch.

Failures: private pre-commit failures retain the provider's existing bounded retry policy; failures after a live POI effect are non-replayable and must bypass retry/fallback. Cancellation controls must bypass all retry, synchronous fallback, recover-from-errors success and chunk-load-failure reporting. Actual exhausted provider failure propagates unchanged and remains distinct from shutdown controls. The existing c2meControls counter counts generation graphs only; it cannot measure read cancellation. Add separate operation terminal counters.

Stop: reject new operation admission when the runtime closes; request shutdown on uncommitted operations and prevent subsequent owner/publication effects. Preserve producer completion and provider busy-reference settlement instead of disposing the Rx chain. Committing owner work settles after its lease releases. The installed provider's own stop/drain boundary remains authoritative; no new scheduler or blocking owner wait is introduced.

One-shot rule: OPEN -> COMMITTING -> APPLIED/FAILED, or OPEN -> CANCELLED/SUPERSEDED/SHUTDOWN. Control acknowledgement and physical producer settlement are separate; the operation record persists until the latter, so a late private result cannot regain publication authority. Reuse existing holder/status observation, custody/work/light registration, regional POI dispatcher and after-release completion. The only new primitive is the provider-specific operation record and terminal arbiter, with the token's CAS determining commit versus cancellation. This is a design decision pending deterministic and installed proof, not acceptance or permission to remove breaks.

### Astra hypothesis ledger

1. Hypothesis: optional-type ModifyReturnValue coercion supplies the provider return CHECKCAST. Test: candidate-v1 W1 installed startup. Observed: JVM VerifyError at ReadFromDisk.upgradeToThis return; no world workload ran. Consequence: this injector does not supply that cast; use the already-proven WrapOperation finalizeLoading surface for the sync outer terminal, preserving the lifecycle model. Evidence: astra-c2me-lifecycle/cancel-v1-w1/first.log.

2. Hypothesis: throwing the terminal control from the provider recovery Function preserves cancellation. Test: candidate-v2 installed W1 plus actual locked Rx deterministic tests. Observed: SingleResumeNext wraps a callback throw with the upstream error into CompositeException, which is not the scheduler's CancellationException protocol; the installed command stalled and watchdog terminated the server. Both new Rx tests are RED (2/2 failures; rx-red.xml). Architectural consequence: the terminal must be returned as Single.error from each recovery node; token CAS authority alone does not preserve the provider error-channel contract. Revisited the complete cancellation chain before editing again; source retry driver may terminate with an error, but recovery Functions must return an error source. No timeouts/configuration or acceptance predicates changed.

3. Hypothesis: after preserving the exact provider error channel, physical action/busy settlement provides terminal failure/stop settlement. Test: v3 W1/W4 failure witnesses and a deterministic test executing the locked native scheduler with MARK_BROKEN. Observed: both installed failure restarts watchdog exit 1; native busy/action settles but requested DISK/FULL futures remain pending, a second request stays pending, and removing every ticket substitutes generic UNLOADED_EXCEPTION. Consequence: this is a provider requester-future API gap independent of the Phoenium arbiter. Cancellation trials pass their bounded witness, but the complete repair is withdrawn and production exclusions remain. Evidence: astra-c2me-lifecycle/failure-v3-w1/, failure-v3-w4/, native-failure-final.xml and STAGE7_C2ME_LIFETIME_DECISION.md.
