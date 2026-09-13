# Stage 8 handoff — entry prerequisites only

Updated: 2026-09-12.

This is an engineering handoff, not a Stage 8 acceptance result. Formal combined-stack acceptance has not started.

## Checkout and artifact

- Repository: `C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium`
- Branch: `stage7/scalablelux-persisted-light-repair`
- Revision: `169003a` (`docs: record stage8 entry handoff`)
- Source/test revision: `3355f3e7761d9ab18b29a188f2602f67131b4c3a` (`stage8-entry: bind c2me read and panda pearl lifecycle`)
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`
- Production JAR SHA-256: `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`
- Source/test changes are committed locally and this documentation checkpoint is committed; no remote push was performed.
- Generated candidate worlds, logs, reports and JARs remain under ignored `run/` and `build/` paths.

The recovery copy made before continuation is retained at `run/stage8/entry-20260911/recovery-before-continuation-20260912/`.

## Dirty-state disclosure

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
