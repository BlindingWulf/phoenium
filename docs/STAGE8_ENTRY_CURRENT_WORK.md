# Stage 8 entry current work

Updated: 2026-09-12

Status: **Stage 8 prerequisite engineering in progress. Formal combined-stack Stage 8 acceptance has NOT started.**

Baseline branch: `stage7/scalablelux-persisted-light-repair`.

## WORK CARD

Active milestone: Panda A2 per-player/per-dimension ender-pearl membership ledger
Exact gate: finish the derived association, reconnect/incarnation lifecycle, and current-owner cleanup path without adding an ownership authority
First missing proof: no real pearl overflow/client workload was available; deterministic ledger, owner seam, final-byte boot, and restart fallback evidence are fresh
Files allowed: `src/main/java/dev/phoenium/live/`, `PhoeniumLiveRuntime`, `EntityLifecycleMixin`, focused tests, and this current-work/handoff record
Success criteria: version-pinned 26.2 owner/persistence seam; idempotent scope ledger; exact retirement/rebind; one owner-routed discard; default inert; full suite and final-byte smoke
Stop/escalation: commit/handoff now; do not enable A3, remove C2ME `breaks`, start combined Stage 8, or claim live pearl acceptance without a real client workload

## Recovered completed work

The approved local-Qwen route through `C:\AI\Models\qwen-subagent.ps1` was verified before this continuation; its receipts remain under `run/stage8/entry-20260911/`. No Qwen or GVS5H files were changed.

C2ME readiness status-slot identity, pre-FULL generation/status, FULL/SEND_READY lifecycle binding, and provider autosave serial admission are implemented as bounded prerequisites. Their historical receipts, locked-provider hash, and earlier suite totals remain in `docs/C2ME_*_MILESTONE.md` and the 2026-09-11 entry records.

The current committed continuation adds the locked-provider ReadFromDisk/async parse/POI/cancellation/lease-release bridge and the empty-provider-queue discriminator. The production gate is unchanged; installed provider-wide acceptance is not claimed.

A1 non-player cross-owner relocation is implemented and verified. It commits only at the owner-free boundary through `ObjectOwnershipRegistry.transitionOwner()`, emits one relocation fact, and remains the source for future per-region accounting.

VMP remains optional and blocked/partial; SLO strict seed parity remains excluded; FastNoise attribution remains bounded by its documented nondeterministic control; prior scoped Chunky, ScalableLux, Lithium, ServerCore, Clumps and Geyser/Floodgate repairs are preserved.

## Entry gates still open after this continuation

1. C2ME ReadFromDisk, async serialization, cancellation/failure/stop, unload/replacement, restart, co-install composition, and W1/W4 evidence remain required before removing `breaks`.
2. A2 is locally engineering-ready but lacks live pearl overflow/removal/reconnect workload acceptance; the separate projectile runtime cap and A3 staggering remain unaccepted.
3. Real Bedrock gameplay, legacy Java/Via gameplay, and two-client voice microphone/proximity/reconnect coverage remain external-facility gates.

VMP is optional. No Stage 8 combined-stack acceptance has started.

## Client preparation already established

Windows `Microsoft.MinecraftUWP` version `1.26.4501.0`, Java Edition, and the authorized Moto G 2025 (`[device-serial]`, Android 16) were observed. The phone lacks `com.mojang.minecraftpe`; Windows Bedrock is only a candidate facility. Evidence: `run/stage8/entry-20260911/CLIENT_ENVIRONMENT_PREP.md`.

## Current continuation checkpoint — authoritative after the local commit

Checkpoint date: 2026-09-12.

Checkout: `C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium`.

Branch: `stage7/scalablelux-persisted-light-repair`.

HEAD: `3355f3e7761d9ab18b29a188f2602f67131b4c3a`.

HEAD title: `stage8-entry: bind c2me read and panda pearl lifecycle`.

Working-tree disclosure: the source/test continuation is committed; this current-work record and `docs/STAGE8_HANDOFF.md` are the remaining documentation checkpoint before the handoff is complete.

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

Required missing C2ME evidence remains cancellation before/after admission, unload/replacement, terminal failure/stop settlement, true save/restart state coverage under the locked provider, co-install composition, and actual W1/W4 acceptance before removing `breaks`.

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

### Next three actions

1. Reopen the C2ME locked-provider lifetime gate with exact ScalableLux composition and exercise ReadFromDisk save/restart/cancel/unload/stop settlement in W1 and W4; keep `breaks` until every predicate has evidence.

2. If the C2ME gate is closed, run the real-client facility preparation and the bounded Stage 8 entry checklist; before that, do not claim Bedrock/Via/voice coverage from startup or protocol-only evidence.

3. Revisit the separate projectile-cap runtime ledger and A3 only under their explicit ownership/simulation contracts; do not add whole-tick or `aiStep` skipping.

### Handoff invariant

Formal combined-stack Stage 8 acceptance has not started.

The next owner should reread this current-work record and the Stage 7 contract before editing.

The immediate executable next action is C2ME ReadFromDisk/cancel/unload/replacement lifetime evidence under the exact locked provider, with production `breaks` retained.
