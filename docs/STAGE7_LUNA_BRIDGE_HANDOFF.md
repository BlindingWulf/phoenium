# PHOENIUM Stage 7 - Luna bridge handoff

Status: `STAGE 7 IN PROGRESS`; Stage 6 remains `AUDIT PARTIAL`; Stage 8 and later remain `NOT STARTED`.

This document records the bounded Stage 7 continuation performed on 2026-09-09 in the user-selected Luna MAX session. It is the durable handoff for the ScalableLux persisted-light repair and the next provider queue. It does not promote the overall compatibility campaign to a release decision.

## Boundary and preserved identity

- Repository: `C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium`
- Branch: `stage7/scalablelux-persisted-light-repair`
- Repair commit: `28a7aa87e3e03185045fc2a77f0e32d0a3df475e` (`stage7: bridge ScalableLux persisted light reload`)
- Source baseline used by the installed campaigns: `7d2029b23c4cb1772039229a9852c0b7762b6679`
- Final Phoenium artifact: `build/libs/phoenium-0.2.0-stage2.jar`
- Final Phoenium artifact SHA-256: `69DDF892341268CD7D956F9AFAF2495923BFA85A0B9B3D3405CC6B688EAF0EB5`
- Locked ScalableLux artifact: `run/stage6/frozen-audit-045834b/research/artifacts/scalablelux/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar`
- Locked ScalableLux SHA-256: `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`
- ScalableLux reviewed source identity: repository `RelativityMC/ScalableLux`, branch `ver/26.2.0`, reviewed source commit `77c290c4c84be1c87d006bc8221e5361d98e0c9a`
- Stage 5 frozen production remains commit `01909eafad8ae95b83f04c7d9bd52ce0facd3cb1`, SHA-256 `59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5`.

The source tree is clean after the repair commit. Preserved historical evidence was not overwritten: the original failure remains under `run/stage7/scalablelux-prepatch-current-w1/`, and the older Stage 6 reproduction remains under `run/stage6/frozen-audit-045834b/runs/scalablelux-w1/`.

## ScalableLux finding

The locked ScalableLux jar replaces the vanilla light engine with the exact provider class `ca.spottedleaf.starlight.common.light.vanillainterface.ThreadedLevelLightEngineVanillaInterface` and implements `ca.spottedleaf.starlight.common.light.StarLightLightingProvider`. Its persisted `SerializableChunkData` mixin delegates saved light arrays to `SaveUtil` and then restores provider-owned block/sky nibble arrays into the returned chunk. Its overrides of vanilla `retainData` and `queueSectionData` intentionally return without inserting a vanilla light task.

Phoenium's strict persisted-read protocol correctly requires an observable physical ingress for ordinary vanilla calls. On the unmodified pairing, the ScalableLux no-op overrides therefore produced a terminal `physical light call returned without witnessed insertion` barrier while the server was preparing levels. The original run stalled at `Preparing spawn area: 11%`; it had no `Done` receipt and no Phoenium live-start receipt. The captured dump shows the server waiting in `MinecraftServer.waitForTasks` / `prepareLevels` while ScalableLux worker threads are idle in their semaphores. This attribution is based on the locked bytecode and the trace, not on a startup guess.

## Contained repair

Commit `28a7aa87e3e03185045fc2a77f0e32d0a3df475e` changes only the light-ingress seam and its adversarial coverage:

1. `ScalableLuxLightBridge` recognizes only the exact locked provider engine plus its optional provider interface. A generic engine or unrelated subclass still follows the strict vanilla path.
2. For that exact provider, Phoenium runs the original `retainData` or `queueSectionData` call first. Only a normal return may then record `delegated=true`; the bridge never manufactures a vanilla mailbox insertion and never acknowledges a call before the provider has returned.
3. After `SerializableChunkData.read` returns, the bridge verifies provider ownership and provider-owned block/sky nibble arrays on the returned `ProtoChunk`. A recognized provider with missing state fails closed with a visible `ChunkProtocolException`.
4. The light occurrence snapshot and diagnostic trace distinguish `inserted=true` (actual vanilla mailbox insertion) from `inserted=false:delegated=true` (the exact provider owns this persisted-light occurrence). Duplicate, late, rejected, generic and non-provider paths remain guarded.

This is a compatibility adapter for persisted-light ownership, not a relaxation of the general ingress protocol and not a second ownership registry.

## Verification evidence

The trace fixture is `run/stage7/scalablelux-trace-w1/plan.json` (plan SHA-256 `D4902A6422040849697F13251EE362F7740F1405A3CF87E428D9FFA40B2AC326`). It freezes ticks, forceloads the exact W1 area, creates a cross-owner redstone lamp, saves natively, restarts from the saved world, asserts `LAMP_PERSISTED`, removes the source, steps ticks, and asserts `LAMP_OFF`.

| Profile | Result | First / restart | Trace and shutdown evidence |
| --- | --- | --- | --- |
| W1 (`run/stage7/scalablelux-bridge-final2-w1/`) | `PASS_CONSOLE_SAMPLE` | 12.063 s / 7.953 s | First log has `LAMP_ON`; restart has `LAMP_PERSISTED` and `LAMP_OFF`; 75 delegated accepted occurrences on restart; no `FAILED_BARRIER`; `Done` present |
| W4 (`run/stage7/scalablelux-bridge-final2-w4/`) | `PASS_CONSOLE_SAMPLE` | 11.891 s / 7.734 s | First log has `LAMP_ON`; restart has `LAMP_PERSISTED` and `LAMP_OFF`; 75 delegated accepted occurrences on restart; no `FAILED_BARRIER`; `Done` present |

Both `report.json` files record `controlUnchanged=true`, `candidateUnchanged=true`, the exact final artifact SHA above, and source head `7d2029b23c4cb1772039229a9852c0b7762b6679`. The W1 report records 408 intercepted/dispatched/executed operations in first phase and 177 in restart; W4 records 402 and 174. The final `PHOENIUM_STAGE2 stopped` lines report zero `leases`, `duplicate`, `bypass`, `fallback`, `ownershipViolations`, `unleased`, and `barriers` for every phase. The `PHOENIUM_CHUNK_BINDING STOPPED` lines also report `barriers=0` and no retained graphs or stale reroutes.

The focused protocol tests pass: `.\gradlew.bat test --tests dev.phoenium.chunk.LightIngressAdversarialTest --tests dev.phoenium.chunk.LightIngressProtocolTest`.

The full unit suite passes: `.\gradlew.bat test` with `BUILD SUCCESSFUL in 10s`.

The campaign driver commands recorded in the reports were `scripts/run_stage6_console.py --output run/stage7/scalablelux-bridge-final2-w1 --plan run/stage7/scalablelux-trace-w1/plan.json --mod run/stage6/frozen-audit-045834b/research/artifacts/scalablelux/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar --port 25747 --production build/libs/phoenium-0.2.0-stage2.jar --production-sha256 69DDF892341268CD7D956F9AFAF2495923BFA85A0B9B3D3405CC6B688EAF0EB5` and the analogous W4 command with `--workers 4 --port 25748` and output `run/stage7/scalablelux-bridge-final2-w4`.

The stale campaign-owned server process left by the earlier diagnostic failure was stopped only after its command line and test-port ownership were confirmed. The Gradle daemon was left running; no unrelated process was stopped.

## Scope result and remaining risk

The ScalableLux persisted-light startup item is now a `SCOPED PASS`, not a full provider acceptance. The evidence covers the locked release, exact saved redstone fixture, provider-owned persisted reload, W1/W4 worker profiles, native save/restart, strict trace accounting and clean stop. It does not yet cover every dynamic-light update, all chunk status transitions, provider combinations, large-world lighting, real-client lighting visibility, or C2ME pairing. Do not remove any existing C2ME gate or call the overall ScalableLux row full production acceptance from this sample.

## Next bounded queue

The next work item is Lithium. Start from the preserved evidence rather than cycling runtime toggles:

1. Isolate the collision-query mixin conflict with Phoenium's overwritten `Level.getChunkForCollisions` path.
2. Review the selected Lithium sleeping-ticker sentinel and the `RegionalChunkGameplayService.tickTicker` null-position failure. Preserve sleeping-ticker identity, wake transitions, owner routing and original block-entity behavior; a null guard that drops a sleeping ticker is not an acceptance fix.
3. If the ownership and lifecycle contract can be stated precisely, implement one narrow adapter and verify furnace/hopper sleep and wake, removal, owner movement, save/restart, W1/W4 and the strict client comparator. If it cannot be stated precisely, record the exact evidence and move to ServerCore without weakening the gate or guessing configurations.

After Lithium, the bounded queue is ServerCore callback ownership, Clumps entity identity/publication, then VMP/Geyser/SLO/FastNoise follow-ups as their evidence warrants. C2ME runtime integration remains behind its existing safety gate: do not integrate replacement generation/FULL/save ownership or combine it with ScalableLux until its readiness, publication, save, cancellation and stop invariants are proven.

No Stage 8/9 work, Android/Phoenix work, public release, broad architecture rewrite, reset credit, or additional agent is authorized by this handoff.
