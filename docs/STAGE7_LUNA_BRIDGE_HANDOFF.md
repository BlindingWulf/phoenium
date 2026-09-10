# PHOENIUM Stage 7 - Luna bridge handoff

Status: `STAGE 7 IN PROGRESS`; Stage 6 remains `AUDIT PARTIAL`; Stage 8 and later remain `NOT STARTED`.

This document records the bounded Stage 7 continuation performed on 2026-09-09/10 in the user-selected Luna MAX session. It is the durable handoff for the ScalableLux persisted-light repair, the Lithium chunk/ticker adapter, the ServerCore entity-callback adapter, and the next provider queue. It does not promote the overall compatibility campaign to a release decision.

## Boundary and preserved identity

- Repository: `C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium`
- Branch: `stage7/scalablelux-persisted-light-repair`
- ScalableLux repair commit: `28a7aa87e3e03185045fc2a77f0e32d0a3df475e` (`stage7: bridge ScalableLux persisted light reload`)
- Lithium repair commit: `c6dd6a29b56e3572b892ff009b5d6f2009e89a18` (`stage7: bridge Lithium chunk and ticker paths`)
- ServerCore repair commit: `72626a94239b86fce1b99a443d7fc02d67f66021` (`stage7: bridge ServerCore entity activation callback`)
- Source baseline used by the ScalableLux campaigns: `7d2029b23c4cb1772039229a9852c0b7762b6679`
- Source head recorded by the final Lithium campaigns: `bdc71b1eb3381e619b3ea083265dbf8bccf93956` (the artifact contains the committed `c6dd6a2` source)
- Final Phoenium artifact: `build/libs/phoenium-0.2.0-stage2.jar`
- Final Phoenium artifact SHA-256: `4FA66A418EC3F32B3616FBB8AD4823D596DB0488F1377F726EC4C10744B55499`
- Locked ScalableLux artifact: `run/stage6/frozen-audit-045834b/research/artifacts/scalablelux/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar`
- Locked ScalableLux SHA-256: `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`
- ScalableLux reviewed source identity: repository `RelativityMC/ScalableLux`, branch `ver/26.2.0`, reviewed source commit `77c290c4c84be1c87d006bc8221e5361d98e0c9a`
- Locked Lithium artifact: `run/stage6/frozen-audit-045834b/research/artifacts/lithium/lithium-fabric-0.25.3+mc26.2.jar`
- Locked Lithium SHA-256: `FDDE92E238E8075F89AD7F701F2A3D5854AF88BA9A67657184A4407B104AC563`
- Locked ServerCore artifact: `run/stage6/frozen-audit-045834b/research/artifacts/servercore/servercore-fabric-1.5.19+26.2.jar`
- Locked ServerCore SHA-256: `4F7C9FB3B35B364D8F6500D04F0D5481656F617A83BC69742B47F39ADFF59666`
- ServerCore preservation plan SHA-256: `4D3210489D329F6DB7B58DC2CEEBD04D1F1CCD62EDD8E3B1E9018245499C3B98`
- Stage 5 frozen production remains commit `01909eafad8ae95b83f04c7d9bd52ce0facd3cb1`, SHA-256 `59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5`.

The source tree is clean after the recorded Stage 7 repair commits. Preserved historical evidence was not overwritten: the original ScalableLux failure remains under `run/stage7/scalablelux-prepatch-current-w1/`, the older Stage 6 ScalableLux reproduction remains under `run/stage6/frozen-audit-045834b/runs/scalablelux-w1/`, the original Lithium client failure remains under `run/stage6/frozen-audit-045834b/runs/lithium-client-w4/`, and the original ServerCore callback-drift runs remain under `run/stage6/frozen-audit-045834b/runs/servercore-w1/` and `servercore-w4/`.

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

## Lithium finding and contained repair

The locked Lithium jar overwrites `Level.getChunkForCollisions` and moves the blocking chunk path into its private `ServerChunkCache.getChunkBlocking` helper. That is why the ordinary Phoenium injections conflict with default Lithium even though the configured `world.chunk_access=false` control boots. The same locked bytecode keeps the vanilla `LevelChunk$RebindableTickingBlockEntityWrapper` in the world ticker list but swaps its delegate to `net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity$1`. That exact sentinel reports `getPos() == null` while sleeping; the prior client failure at `RegionalChunkGameplayService.tickTicker:446` was therefore a lifecycle identity mismatch, not an arbitrary missing-position record.

Commit `c6dd6a29b56e3572b892ff009b5d6f2009e89a18` keeps the repair narrow:

1. `PhoeniumMixinConfigPlugin` selects the ordinary Phoenium `Level`/`ServerChunkCache` hooks when Lithium is absent or its `mixin.world.chunk_access` rule is explicitly disabled. It selects the exact Lithium adapters only when that overwritten path is enabled, avoiding an injection into a method Lithium did not create.
2. `LithiumServerChunkCacheMixin` targets the locked private helper and preserves Phoenium's managed-wait, future-join and initialized-result ownership checks. `LithiumFullFinishingLevelQueriesMixin` applies the collision and dynamic-shape authority at the calls that remain in Lithium's overwritten `Level` method.
3. `LocalRebindableBlockEntityTickerMixin` exposes only the current delegate of the existing vanilla wrapper; it retains no parallel lifecycle state. `LithiumSleepingTickerBridge` bypasses only the exact wrapper-to-Lithium-sentinel identity. A non-wrapper null position still fails closed, and a wake delegate returns to the normal position/eligibility/tick sequence.

This is a provider-specific compatibility adapter, not a null-position bypass, a dropped sleeping ticker, a second ticker registry, or a weakening of owner routing and original block-entity behavior.

## ServerCore finding and contained repair

The locked ServerCore mixin adds `servercore$activateEntities(BooleanSupplier, CallbackInfo)` immediately before the native `EntityTickList.forEach` call in `ServerLevel.tick`. Its native body reads `MinecraftServer.getTickCount()`, runs only when `tickCount % 20 == 0`, and calls `ActivationRange.activateEntities(ServerLevel, int)`. The preservation profile keeps activation range, dynamic distance, breeding caps, feature switches and all five optional optimization switches disabled; that configuration does not remove the callback, so the callback boundary itself still has to be accounted for.

Commit `72626a94239b86fce1b99a443d7fc02d67f66021` accepts only that locked shape:

1. `LiveTickPhaseBoundary.requireInstalledLevelCallbacks` recognizes the exact ServerCore handler name suffix and descriptor, requires its call to remain immediately before `EntityTickList.forEach`, and verifies the merged twelve-instruction body, its single `% 20` branch, its `ActivationRange.activateEntities` target, its ordinary instance execution state and its single fall-through label.
2. Because Mixin inserts the callback receiver and `CallbackInfo` arguments between the original entity consumer and `forEach`, the adapter anchors the copied entity phase at the original `InvokeDynamic` consumer rather than treating those preparation instructions as native entity-list work. The callback is copied once into `phoenium$runEntityTicks`, where the owner-free final coordinator invokes the original body before regional entity admission; it is never replayed once per region.
3. The focused boundary test proves the reviewed callback is retained and rejects a moved callback, a changed cadence constant, and an otherwise unreviewed shape. The native ServerCore body is not replaced, deleted or executed through a second registry.

This is a callback-boundary adapter, not acceptance of ServerCore's policy modules. If activation range or other behavior-changing policies are enabled later, their global entity scan and activation-state writes require a separate semantic review against ownership, movement, removal, save/restart and AI requirements.

## Verification evidence

The trace fixture is `run/stage7/scalablelux-trace-w1/plan.json` (plan SHA-256 `D4902A6422040849697F13251EE362F7740F1405A3CF87E428D9FFA40B2AC326`). It freezes ticks, forceloads the exact W1 area, creates a cross-owner redstone lamp, saves natively, restarts from the saved world, asserts `LAMP_PERSISTED`, removes the source, steps ticks, and asserts `LAMP_OFF`.

| Profile | Result | First / restart | Trace and shutdown evidence |
| --- | --- | --- | --- |
| W1 (`run/stage7/scalablelux-bridge-final2-w1/`) | `PASS_CONSOLE_SAMPLE` | 12.063 s / 7.953 s | First log has `LAMP_ON`; restart has `LAMP_PERSISTED` and `LAMP_OFF`; 75 delegated accepted occurrences on restart; no `FAILED_BARRIER`; `Done` present |
| W4 (`run/stage7/scalablelux-bridge-final2-w4/`) | `PASS_CONSOLE_SAMPLE` | 11.891 s / 7.734 s | First log has `LAMP_ON`; restart has `LAMP_PERSISTED` and `LAMP_OFF`; 75 delegated accepted occurrences on restart; no `FAILED_BARRIER`; `Done` present |

Both `report.json` files record `controlUnchanged=true`, `candidateUnchanged=true`, the ScalableLux candidate SHA `69DDF892341268CD7D956F9AFAF2495923BFA85A0B9B3D3405CC6B688EAF0EB5`, and source head `7d2029b23c4cb1772039229a9852c0b7762b6679`. The W1 report records 408 intercepted/dispatched/executed operations in first phase and 177 in restart; W4 records 402 and 174. The final `PHOENIUM_STAGE2 stopped` lines report zero `leases`, `duplicate`, `bypass`, `fallback`, `ownershipViolations`, `unleased`, and `barriers` for every phase. The `PHOENIUM_CHUNK_BINDING STOPPED` lines also report `barriers=0` and no retained graphs or stale reroutes.

The focused protocol tests pass: `.\gradlew.bat test --tests dev.phoenium.chunk.LightIngressAdversarialTest --tests dev.phoenium.chunk.LightIngressProtocolTest`.

The full unit suite passes: `.\gradlew.bat test` with `BUILD SUCCESSFUL in 10s`.

The campaign driver commands recorded in the reports were `scripts/run_stage6_console.py --output run/stage7/scalablelux-bridge-final2-w1 --plan run/stage7/scalablelux-trace-w1/plan.json --mod run/stage6/frozen-audit-045834b/research/artifacts/scalablelux/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar --port 25747 --production build/libs/phoenium-0.2.0-stage2.jar --production-sha256 69DDF892341268CD7D956F9AFAF2495923BFA85A0B9B3D3405CC6B688EAF0EB5` and the analogous W4 command with `--workers 4 --port 25748` and output `run/stage7/scalablelux-bridge-final2-w4`.

### Lithium evidence

The locked Lithium W1/W4 console plan is `run/stage6/frozen-audit-045834b/runs/lithium-w1/plan.json` / `lithium-w4/plan.json` (shared plan SHA-256 `B440790F56BADD302362774BF8C4D5E533362E182B017F4FD279E2BDE28DD041`). With the final candidate SHA-256 `F3F63A32A265F1D2F97A7BB3D077B16F571D3F210DB1337965AD191B0CF99033`, both isolated runs are `PASS_CONSOLE_SAMPLE`:

| Profile | Result | First / restart | Evidence |
| --- | --- | --- | --- |
| W1 (`run/stage7/lithium-adapter-final-w1/`) | `PASS_CONSOLE_SAMPLE` | 13.891 s / 9.578 s | `Done`, `LAMP_ON`, `LAMP_PERSISTED`, `LAMP_OFF`; zero duplicate, bypass, fallback, ownership-violation, unleased and barrier counts |
| W4 (`run/stage7/lithium-adapter-final-w4/`) | `PASS_CONSOLE_SAMPLE` | 13.875 s / 9.562 s | Same markers and zero shutdown health counts; both reports record `controlUnchanged=true` and `candidateUnchanged=true` |

The default Lithium real-client run is `run/stage7/lithium-adapter-client-default-w4/`. Its first and post-restart server logs both report `STAGE4_TRACE_RESULT PASS`; `evidence/STAGE4-CLIENT-trace.json` and `evidence/STAGE4-CLIENT-before-restart-trace.json` are `PASS`. The saved, reloaded and restored furnace evidence is under `evidence/gameplay/client-saved.json`, `client-reloaded.json` and `client-restored-after.json`; the restored phase completed without the former null-position crash and reports zero duplicate, bypass, fallback, ownership-violation, unleased and barrier counts. The exact default-artifact selection is retained at `run/stage7/lithium-adapter-default-selection.json`.

The preserved configuration control was rerun as `run/stage7/lithium-adapter-client-disabled-w4/` using `run/stage6/frozen-audit-045834b/logic-config-selection.json`. Its server log records Lithium disabling `ChunkHolderMixin`, `GenerationChunkHolderAccessor`, `LevelMixin` and `ServerChunkCacheMixin` for `mixin.world.chunk_access`; the ordinary Phoenium hooks were selected, both client phases report `STAGE4_TRACE_RESULT PASS`, and no private-helper injection failure occurs.

The full unit suite after the Lithium repair is `843` tests across `89` suites with zero failures or skips: `./gradlew.bat test`. It includes the explicit unknown-null-position fail-closed test and the negative wrapper-identity sentinel test in `RegionalBlockEntityTickOrderTest`. The jar build and `git diff --check` also pass.

### ServerCore evidence

The locked ServerCore preservation plan is `scripts/stage6-plans/servercore-preserve.json` (plan SHA-256 `4D3210489D329F6DB7B58DC2CEEBD04D1F1CCD62EDD8E3B1E9018245499C3B98`). It writes the same behavior-preserving configuration to each isolated server: activation range, dynamic, breeding-cap and listed feature modules are disabled, as are `reduce-sync-loads`, `cache-ticking-chunks`, `optimize-command-blocks`, `fast-biome-lookups` and `cancel-duplicate-fluid-ticks`. The final candidate SHA-256 is `4FA66A418EC3F32B3616FBB8AD4823D596DB0488F1377F726EC4C10744B55499`; both reports now identify committed source head `72626a94239b86fce1b99a443d7fc02d67f66021`.

| Profile | Result | First / restart | Evidence |
| --- | --- | --- | --- |
| W1 (`run/stage7/servercore-final-w1/`) | `PASS_CONSOLE_SAMPLE` | 14.297 s / 10.250 s | Native save/stop and restart pass; zero duplicate, bypass, fallback, ownership-violation, unleased and barrier counts |
| W4 (`run/stage7/servercore-final-w4/`) | `PASS_CONSOLE_SAMPLE` | 14.390 s / 10.219 s | Same preservation plan and clean health counters with `parallel=ON`; `controlUnchanged=true`, `candidateUnchanged=true` |

The strict real-client cases are `run/stage7/servercore-adapter-client2-w1/` and `run/stage7/servercore-adapter-client2-w4/`, using the hash-locked selection in `run/stage7/servercore-adapter-client-selection.json`. Both have real TCP connection, account-free identity, native save/unpack/deadline PASS, and `STAGE4_TRACE_RESULT PASS` in the first and pre-restart traces. The strict comparator outputs `run/stage7/servercore-adapter-client-comparison-w1.json` and `run/stage7/servercore-adapter-client-comparison-w4.json`, each `PASS` with 132 ordered events, saved delay 186, original tracker-call counts `{00000009...:323, 00000005...:187, 00000001...:515}`, and digest `6AB41117A5099DD52A7E10C358A6E30887EB489B7A038C4E1F1A5078E4385A96` for both reference and candidate. The raw post-restart reload trace remains `PARTIAL` because of the existing non-contract event omissions; the native restart validator and strict comparator still pass, and this handoff does not relabel that raw trace.

The ServerCore boundary test is included in the full `844`-test, `89`-suite run after commit `72626a9`, with zero failures, errors or skips. The jar build and `git diff --check` pass.

The stale campaign-owned server process left by the earlier diagnostic failure was stopped only after its command line and test-port ownership were confirmed. The Gradle daemon was left running; no unrelated process was stopped.

## Scope result and remaining risk

The ScalableLux persisted-light startup item is now a `SCOPED PASS`, not a full provider acceptance. The evidence covers the locked release, exact saved redstone fixture, provider-owned persisted reload, W1/W4 worker profiles, native save/restart, strict trace accounting and clean stop. It does not yet cover every dynamic-light update, all chunk status transitions, provider combinations, large-world lighting, real-client lighting visibility, or C2ME pairing. Do not remove any existing C2ME gate or call the overall ScalableLux row full production acceptance from this sample.

The Lithium collision/sleeping-ticker item is now a `SCOPED ADAPTER PASS`, not full Lithium production acceptance. The evidence covers the locked release, default and explicitly disabled `world.chunk_access` configurations, collision-query boot, the real-client furnace workload across save/restart, W1/W4 redstone save/restart, strict client traces and clean shutdown. It does not yet cover every Lithium optimization, every block-entity type, large entity populations, all owner-movement/removal permutations, or combined Lithium/FerriteCore/VMP acceptance. Keep those combinations withheld until independently exercised.

The ServerCore external-callback item is now a `SCOPED ADAPTER PASS`, not full ServerCore production acceptance. The evidence covers the locked release, exact callback/body/boundary recognition, W1/W4 preservation-profile save/restart, real-client W1/W4 strict comparison and clean shutdown. It does not accept activation-range, dynamic-distance, breeding-cap, villager, mob-spawning, pearl-loading or optional optimization behavior; those remain separately unproven and are intentionally disabled in the preserved profile.

## Next bounded queue

Clumps entity identity/publication is now a `SCOPED PASS`; see [STAGE7_CLUMPS_REPAIR_REPORT.md](STAGE7_CLUMPS_REPAIR_REPORT.md). The root cause was the ordering of Phoenium's tracking-fact admission relative to Minecraft's native removed-entity guard: Clumps legitimately discards the merged-away orb while `EntityTickList` is still iterating its old snapshot. Commit `b394978e349d3ca05c49bcdb789d24ecf8193aa6` restores native `isRemoved()` filtering before tracking facts are required without weakening live-alias validation or adding ownership state.

Post-commit W1/W4 evidence in `run/stage7/clumps-postcommit-w1/` and `run/stage7/clumps-postcommit-w4/` identifies the committed source and production SHA `0B80154F4AA99E0D833C5DC119A39A2087AF6CDA3B2AC286DAD0477E3F5867C4`. Fresh and restart phases pass with one XP-10 orb and `clumpedMap {3:1,7:1}`. The account-free real-client probe at `run/stage7/clumps-client-w1/` verifies pickup, Mending damage `100 -> 80`, explicit removal, native restart deadlines and clean shutdown. The complete suite is 845 tests / 90 suites / zero failures, errors or skips. ServerCore preservation-profile W4 also passes fresh+restart after the shared lifecycle change.

The next bounded item is VMP stop/trace integration. Classify the missing persisted terminal stop receipt as an observation/harness issue versus a real lifecycle failure before changing production. Preserve the existing PARTIAL label unless exact semantic evidence supports a narrower repair. After VMP, continue to the bounded Geyser/Floodgate lifecycle isolation, Structure Layout Optimizer policy research, and FastNoise comparator-boundary work.

C2ME runtime integration remains behind its existing safety gate: do not integrate replacement generation/FULL/save ownership or combine it with ScalableLux until its readiness, publication, save, cancellation and stop invariants are proven.

No Stage 8/9 work, Android/Phoenix work, public release, broad architecture rewrite, reset credit, or additional agent is authorized by this handoff.
