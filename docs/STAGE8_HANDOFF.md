# Stage 8 handoff — entry prerequisites only

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

## Historical C2ME handoff — 2026-09-13

**C2ME_UPSTREAM_SETTLEMENT RESOLVED — C2ME_LIFETIME ACCEPTED — RETURN TO LUNA**

Final acceptance: 2026-09-13. The native settlement patch and explicitly approved
Phoenium DISK lifecycle and SEND_READY dependency repairs passed the complete
literal W1/W4 matrix, including the full selected overlapping Stage 7 stack.
All 12 actual-production first/restart pairs pass, with all 24 server processes
exited. Production C2ME exclusions were removed only after candidate acceptance;
the final matrix uses the actual production JAR directly.

* Phoenium implementation commit: `916904794590433ccac1777c24ffa023b7af4338`.
* Branch: `stage7/scalablelux-persisted-light-repair`.
* Production `build/libs/phoenium-0.2.0-stage2.jar`: **1,433,247 bytes**,
  SHA-256 `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`.
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

The active WORK CARD is **A3**. Read
[STAGE8_ENTRY_CURRENT_WORK.md](STAGE8_ENTRY_CURRENT_WORK.md) for the current
bounded continuation. PANDA_A2_LIVE and PROJECTILE_CAP are accepted at the real
client/server boundary on the exact pinned provider composition. Preserve those
receipts and the A3 simulation-preservation boundary; formal combined-stack
Stage 8 acceptance has not started.

Exact continuation instruction:

> Continue A3 from docs/STAGE8_ENTRY_CURRENT_WORK.md. PANDA_A2_LIVE and
> PROJECTILE_CAP are accepted on the pinned C2ME settlement2 + ScalableLux stack;
> preserve those receipts, do not reopen C2ME, and do not begin formal Stage 8
> acceptance.

```powershell
Get-Content docs/STAGE8_ENTRY_CURRENT_WORK.md
```

## Historical bounded Stage 7 receipts — 6194 artifact

The final runtime artifact is `build/libs/phoenium-0.2.0-stage2.jar`,
1,457,625 bytes, SHA-256
`619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD`.
PANDA_A2_LIVE passed at W1/W4 in
`build/panda-a2-live/final-6194-w1/evidence-w1/` and
`build/panda-a2-live/final-6194-w4/evidence-w4/`; PROJECTILE_CAP passed at W1/W4
in `build/projectile-cap-live/stack16-w1/evidence-w1/` and
`build/projectile-cap-live/stack16-w4/evidence-w4/`. All four receipts pin C2ME
settlement2 (`8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`)
and ScalableLux (`8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`)
and report clean client/server termination. The next bounded card is A3;
formal combined-stack Stage 8 acceptance remains unstarted.

## Historical handoff archive — superseded instructions

The prior handoff is retained below as evidence. Its blocked status, old artifact
hashes and commands apply only to those earlier checkpoints.

# Stage 8 handoff — entry prerequisites only

Updated: 2026-09-13.

This is an engineering handoff, not a Stage 8 acceptance result. Formal combined-stack acceptance has not started.

## Current C2ME continuation — Astra disposition, 2026-09-13

**C2ME_LIFETIME BLOCKED — EXACT ARCHITECTURE BLOCKER.** Read [STAGE7_C2ME_LIFETIME_DECISION.md](STAGE7_C2ME_LIFETIME_DECISION.md) and the current work card before continuing. The native MARK_BROKEN upgrade path releases action/busy state but leaves requested DISK/dependent futures pending, including later requests; native unload finally substitutes a generic error. This is reproduced by a deterministic native-provider test and consistent with watchdog exit 1 in exact W1/W4 installed failure trials. A provider-owned failed-action/status-future settlement contract is required. Phoenium's existing custody/dispatcher primitives cannot safely own those shared requesters.

The withdrawn v3 candidate did prove a bounded action-token cancellation handshake at W1/W4, with one matching CANCELLED and 119 APPLIED, clean exits and zero final retained/safety counters. It did not pass the complete lifetime gate. The existing generation-only c2meControls counter was not a disk cancellation measure. All experimental production files were archived before restoration; both C2ME breaks remain in the actual production JAR. No Panda or broader Stage 7/8 work was started.

Current HEAD: `31fea7d45b6f161fa3897d894cb016d89e1dcd79`; branch: `stage7/scalablelux-persisted-light-repair`. The checkout remains dirty with Luna's preserved observer/runner/plans, updated C2ME documentation, the test-only successful-cancel identity receipt and new `C2meNativeFailureSettlementTest`. No Stage 7 completion commit or push was made. Older clean-checkpoint claims below are historical only.

Final production build reproduces **1,403,887 bytes**, SHA-256 `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`; source diff under src/main is empty, breaks are intact, and no observer/experimental classes are packaged. Full regression: **930 tests / 104 XML suites / 0 failures / 0 errors / 0 skipped**, exit 0; build exit 0; diff check passes. No project server remains running.

Evidence: `run/stage8/entry-20260913/astra-c2me-lifecycle/`; all provider/candidate hashes, source manifests and W1/W4 results are indexed by the decision. C2ME is SHA-256 `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`; ScalableLux is `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8` and supplies Starlight. The changed-byte persistence W4 trial remains unresolved on a missing marker; full native replacement/unload, stop and co-install acceptance was not completed after proving the blocker.

Luna's first command from the repository is `.\gradlew.bat test --rerun-tasks --no-daemon --tests "dev.phoenium.chunk.C2meNativeFailureSettlementTest"`. It reproduces the unsupported behavior, not a gate pass. Keep C2ME_LIFETIME blocked pending the native failure-settlement contract; then repeat the complete lifetime matrix before any breaks removal. Do not switch the card to PANDA_A2_LIVE.

## Historical Luna continuation note — 2026-09-13

The exact locked C2ME + ScalableLux provider composition was exercised at literal W1 and W4 using disposable no-break Phoenium bytes. The redstone save/normal-stop/existing-world-restart fixtures reached the real ScalableLux-backed `ReadFromDiskAsync` path and settled with zero retained state. Fresh held-read cancellation witnesses then cancelled 49 real C2ME action tokens at each worker profile, but Phoenium observed `c2meControls=0` because the installed C2ME action-token cancellation does not dispose the Rx `Completable` that the current bridge observes. Provider-failure witnesses propagated one bounded drain failure and retired residue, but ended with `scheduler=FAILED` and an intentional shutdown exception. The exact evidence and ASTRA-HARD escalation packet are recorded in `docs/STAGE7_C2ME_ESCALATION.md`; production `breaks` remain unchanged and no final Stage 7 commit was made.

## Historical checkout and artifact

- Repository: `C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium`
- Branch: `stage7/scalablelux-persisted-light-repair`
- Revision: `169003a` (`docs: record stage8 entry handoff`)
- Source/test revision: `3355f3e7761d9ab18b29a188f2602f67131b4c3a` (`stage8-entry: bind c2me read and panda pearl lifecycle`)
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`
- Production JAR SHA-256: `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`
- Source/test changes are committed locally and this documentation checkpoint is committed; no remote push was performed.
- Generated candidate worlds, logs, reports and JARs remain under ignored `run/` and `build/` paths.

The recovery copy made before continuation is retained at `run/stage8/entry-20260911/recovery-before-continuation-20260912/`.

## Historical dirty-state disclosure

The source checkout and documentation checkpoint are clean at revision `169003a`; the final documentation commit records the source/test revision separately above.

No reset, clean, stash, branch switch, delete, release, or push was performed. Other Phoenium worktrees were inspected and left untouched.

## Locked stack and safety posture

- Minecraft `26.2`.
- Java `25`.
- Fabric Loader `0.19.5`.
- Fabric API `0.159.0+26.2`.
- Fabric Loom `1.17.20`.
- Locked C2ME outer artifact: `build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar`.
- Locked C2ME SHA-256: `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
- Default gameplay worker profile remains W1 / parallel OFF for the entry smoke.
- Production `fabric.mod.json` still declares `breaks.c2me="*"` and `breaks.c2me-rewrites-chunk-system="*"`.
- The C2ME gate must remain until real provider-wide W1/W4 lifetime and co-install evidence satisfies the documented predicates.
- No ScalableLux/Starlight provider is installed in the disposable C2ME candidate used by the final smoke.

## Stage 7 gates and proof

### Preserved scoped passes

The previously accepted scoped repairs for Chunky, ScalableLux persisted-light handling, Lithium, ServerCore, Clumps and Geyser/Floodgate remain preserved. Their broader provider compatibility remains partial where the matrix says so.

A1 non-player relocation is implemented and verified. Physical cross-owner observations are retained as one latest fact, committed at an owner-free boundary through `ObjectOwnershipRegistry.transitionOwner()`, revalidated by epoch/incarnation/topology, republished to the derived index, and emitted once as a relocation fact.

### C2ME entry engineering

The committed continuation contains the locked-provider status-slot, generation/status, FULL/SEND_READY, autosave-serial, ReadFromDisk, async parse, parsed-read ingress, deferred POI, cancellation/lease-release, and empty-provider-queue work.

The local deterministic C2ME tests and exact locked provider binary tests pass. A final-byte disposable no-break candidate reached real `ReadFromDiskAsync` and async parse callbacks on C2ME worker threads, then restarted from its existing disk world and exited cleanly.

Fresh candidate evidence is at `run/stage8/entry-20260912/a2-final-current-w1-v2/`.

The candidate SHA-256 is `4F25BAE543C5D8D79FFAEEE20D50643BB136D79E6A62F1DFE1D995E558367590`; its metadata removed only `breaks` and was never used as the production JAR.

The candidate first phase stopped with `405/405/405` intercepted/dispatched/executed and zero leases, duplicates, bypasses, fallbacks, ownership violations, unleased executions and barriers.

The candidate restart phase stopped with `276/276/276` and the same zero safety counters; `STAGE8_A2_FINAL_V2_RESTART_LOADED` was observed.

The restart also produced the expected fail-closed `C2ME async private DISK read requires exact ScalableLux persisted-light ownership` fallback because the exact external provider is absent. This is a blocker, not an acceptance.

### Panda A2

`PearlMembershipLedger` is derived state keyed by `(WorldId, player UUID)` with exact pearl UUID/incarnation and deterministic admission order. It has no region, owner, lease, position or mutation authority.

The default is inert unless both `phoenium.protection.pearlCap.enabled` and a positive `phoenium.protection.pearlCap` are present.

Overflow picks oldest excess exactly once; scope, identity, restart reconstruction and close behavior are deterministic.

Cleanup is an identity-only `pearl-discard` operation through `ObjectOwnerGateway` with current-owner re-resolution and execution-time exact-incarnation checks. The native action is one `discard()`.

26.2 owner/persistence shape is pinned in `PearlOwnerAccessTest`: `ThrownEnderpearl.getOwner()`, protected projectile save hooks, `ServerPlayer.saveEnderPearls`, `loadAndSpawnEnderPearls`, `loadAndSpawnEnderPearl`, and the `ender_pearls` / `ender_pearl_dimension` tags.

`UNLOADED_WITH_PLAYER` preserves one marked derived membership for same-UUID reconnect and rebinds its admission sequence to the newly admitted incarnation. Terminal removal retires the exact identity.

The owner-routed cleanup regression moves a queued operation from A to B and observes one B execution with no active leases remaining.

Fresh full-suite result: `929` tests / `103` XML suites / `0` failures / `0` errors / `0` skipped.

A2 is engineering-ready and default-inert, but live overflow/removal/reconnect behavior is not accepted because no real client pearl workload was run.

### Explicitly unaccepted protections

The separate per-region/dimension/type projectile cap was not implemented in this A2 milestone; its A1 dependency is ready but its live accounting gate remains open.

A3 mob/vehicle staggering remains unwired. Whole entity tick, `aiStep`, native-freeze or private timer skipping is prohibited without the approved semantic-preservation contract.

VMP optimized tracking remains optional and blocked/partial. SLO strict seed parity and FastNoise attribution retain their documented dispositions.

## External facility status

Windows Bedrock `Microsoft.MinecraftUWP` version `1.26.4501.0` is installed, and Java Edition is installed. The authorized Moto G 2025 (`[device-serial]`, Android 16) lacks `com.mojang.minecraftpe`.

There is no real Bedrock gameplay proof, no legacy Java/Via gameplay proof, and no two-client Simple Voice Chat microphone/proximity/reconnect proof. A server boot, protocol handshake, UDP bind or synthetic client would not replace those observations.

No account credentials, purchase, launcher bypass, or untrusted client was used.

## Preserved evidence and fixtures

- `docs/STAGE8_ENTRY_CURRENT_WORK.md` — canonical current-work record and six-line active card.
- `docs/STAGE7_ARCHITECTURE_CLOSURE.md` — historical architecture dispositions; not rewritten.
- `docs/STAGE7_PANDA_NATIVE_PROTECTION_POLICY.md` — policy contract and A3 boundary.
- `docs/C2ME_AUTOSAVE_ADMISSION_MILESTONE.md` — accepted autosave prerequisite.
- `docs/C2ME_FULL_SEND_READY_MILESTONE.md` — accepted bounded FULL/SEND_READY prerequisite.
- `run/stage8/entry-20260911/` — prior C2ME/A1 receipts and client preparation.
- `run/stage8/entry-20260912/` — current C2ME read and final-byte smoke evidence.
- `build/stage6-audit-20260909/research/artifacts/` — locked provider research artifacts.
- `build/test-results/test/` and `build/reports/tests/test/` — fresh deterministic suite results.

## First Stage 8 commands and checklist

Run these only when the remaining entry gates and a formal Stage 8 authorization are satisfied. They are listed here for handoff; they were not launched as a combined Stage 8 campaign.

1. Read `docs/AGENT_MINECRAFT_TESTING.md`, this handoff, the current-work card, and the applicable provider contract.
2. Confirm the checkout, branch, production JAR hash, clean intended diff and locked provider hashes.
3. Run the deterministic baseline and regression gate:

   `.\gradlew.bat test --no-daemon`

4. Run the Fabric client smoke and the documented real-packet harness only after the actual client facility is ready:

   `.\gradlew.bat compileClientGameTestJava --no-daemon`

   `.\gradlew.bat runClientGameTest --no-daemon`

   `.\gradlew.bat runStage3ClientGameTest --no-daemon`

5. Reopen C2ME with a new disposable plan and exact provider composition; run literal W1 and W4 profiles, each with save -> normal stop -> existing-disk restart, cancellation before/after admission, unload/replacement and terminal failure/stop settlement. Use `scripts/run_stage6_console.py` only for bounded candidate evidence and retain its report, installed inventory, plan, logs, thread dumps and world snapshots.
6. Do not remove production `breaks` until ReadFromDisk, async serialization, cancellation, unload/replacement, restart, stop settlement, co-install composition and W1/W4 evidence all pass under the exact provider configuration.
7. Establish a legitimate Bedrock client procedure before any Geyser/Floodgate acceptance; then separately schedule legacy Via and two-client voice acceptance.
8. Start formal Stage 8 only after the above blockers are closed; use serial and parallel modes, required provider combinations, cross-stack behavior, save/restart, shutdown and client evidence as required by the roadmap.

Immediate next action from this handoff: reopen the C2ME ReadFromDisk/cancel/unload/replacement lifetime gate with the exact ScalableLux provider composition while retaining the production safety gate.

No formal Stage 8 PASS is implied by this handoff.
