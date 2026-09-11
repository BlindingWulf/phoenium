# Stage 7 current-HEAD Qwen closure evidence

Updated: 2026-09-11

## Purpose

This is the durable receipt for the fresh local-Qwen investigations used to re-check the bounded Stage 7 architecture closeout from parent HEAD `4e5592355d40cda3ca0bab3680bab353f6f5816b` on branch `stage7/scalablelux-persisted-light-repair`.

The workers were read-only investigations. The parent session owns all repository edits and the final disposition. No worker was allowed to modify production code, remove a compatibility gate, or start Stage 8.

## Local model/backend verification

The active worker wrapper is `C:\AI\Models\qwen-subagent.ps1`. Each successful investigation launched a nested Codex CLI session with these reported properties:

- Codex CLI: `0.153.4`;
- provider: `qwen-local`;
- model: `Qwen3.8-27B-NVFP4-MTP-MID-HIGH`;
- endpoint: `http://127.0.0.1:8080/v1`;
- context: `131072`;
- KV cache: `Q8_0/Q8_0`;
- investigation sandbox: `read-only`.

The local launcher receipt records the active server start as `Qwen3.8-27B-NVFP4-MTP-MID-HIGH`, context `131072`, K/V cache `Q8_0/Q8_0`, MTP enabled with draft max `2`, followed by a ready-server PID. The authenticated `/v1/models` response observed by the nested Codex sessions reported the same model ID, `owned_by=llamacpp`, `n_ctx=131072`, `n_ctx_train=262144`, and approximately 27.32 billion parameters. The llama.cpp stderr log continued to advance while these investigations ran and recorded Responses-tool handling.

Codex logs a nonfatal model-metadata warning because the llama.cpp `/v1/models` payload does not provide the `slug` field expected by Codex's model registry. The warning did not prevent the model from invoking repository shell/file tools and returning source-backed findings. No API key or token is retained in repository evidence.

## Tool-use acceptance probe

Before architecture work, a fresh worker inspected the live repository and independently reported:

- HEAD `4e5592355d40cda3ca0bab3680bab353f6f5816b` and branch `stage7/scalablelux-persisted-light-repair`;
- `docs/STAGE7_PANDA_NATIVE_PROTECTION_POLICY.md`;
- `src/test/java/dev/phoenium/stage7/NativeProtectionPolicyTest.java`;
- deterministic overflow tests including `projectileOverflowEvictsExactlyTheOldestExcessDeterministically`, `equalSequenceEvictionUsesStableIdentityInsteadOfIterationOrder`, `pearlOverflowKeepsNewestEntriesAndMovementDoesNotChangePlayerWorldScope`, and `cleanupIsIncarnationSafe`;
- bounded-stagger tests including `staggeringExemptsPlayersControlledVehiclesAndCountsBelowThreshold`, `staggerStrideIsBoundedAndEveryStableEntityGetsOneRunPerWindow`, and `staggerPhaseDistributionDoesNotStarveAGroupOfEntities`.

That probe used the nested Codex repository tools rather than answering from prompt context alone.

## A1 — cross-owner non-player movement

Fresh source inspection reconfirmed the live path:

`Entity.setPosRaw` -> `EntityLifecycleMixin.phoenium$routingObserved` -> `PhoeniumLiveRuntime.onEntityRoutingObserved` -> `EntityIndexLifecycleAdapter.routingObserved`.

`ObjectOwnershipRegistry.updateRoutingKey` rejects a route that crosses the current object owner and explicitly requires `transitionOwner` after a quiescent publication boundary. `transitionOwner` exists, but no production path currently turns an observed physical non-player relocation into that quiescent owner transition. `EntityIndexLifecycleAdapter.routingObserved` therefore invalidates the derived index candidate on a foreign-owner observation rather than inventing ownership.

Disposition: the architecture in `STAGE7_ARCHITECTURE_CLOSURE.md` remains correct. A per-region projectile ledger must wait for the bounded pending-relocation fact, one-and-only-one epoch transition, stale/incarnation invalidation and exactly-once accounting publication contract. Implementing that machinery in this closeout would be a new migration subsystem, so no runtime patch was accepted.

## A2 — per-player/per-dimension ender pearls

The worker inspected the pinned Minecraft 26.2 source cache as well as Phoenium lifecycle code. Vanilla `Projectile` retains an owner reference, `ThrownEnderpearl` registers/deregisters itself with `ServerPlayer`, and player persistence carries live ender pearls with dimension identity. This gives a concrete reconstruction source for a future derived ledger keyed by `(WorldId, player UUID)` with pearl object identity, incarnation and deterministic admission sequence.

The ledger must remain non-authoritative: it cannot own position, leases or mutation rights. Removal must still be delivered to the pearl's current Phoenium owner and revalidated there. Disconnect must not delete live pearls.

Two version-pinned prerequisites remain before wiring: an exact owner-reference/accessor persistence test, and a focused cross-dimension pearl lifecycle test that proves when membership leaves one `(WorldId, player UUID)` scope and enters another. The runtime pearl cap therefore remains disabled.

## A3 — selective entity staggering

The worker inspected Minecraft 26.2 `ServerLevel`, `Entity`, `LivingEntity`, `Mob`, `AgeableMob`, boat/minecart classes and `TickRateManager` from the cached sources. The native entity loop performs despawn checks before `tickNonPassenger`; `tickNonPassenger` advances old position and `tickCount`, invokes the entity tick and then services passengers. Mob AI is fused with `noActionTime`, sensing, goals, navigation, custom AI and controls. Aging advances from `AgeableMob.aiStep`; boat and minecart physics/timers live in their class ticks. The native freeze facility is world-level rather than a selective per-entity throttle.

Disposition: no sufficiently narrow safe 26.2 staggering seam was found. Whole-body skipping, `entity.tick()` skipping, an `aiStep`/`serverAiStep` shortcut, and use of the native freeze flag all require semantic compensation beyond this bounded repair pass. The policy model stays inert and unwired.

## C2ME — locked-binary compatibility map

Locked artifact:

- `build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar`;
- version `0.4.2-alpha.0.43+26.2`;
- SHA-256 `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.

Fresh binary/source inspection retained these dispositions:

| Provider boundary | Current disposition | Missing proof before compatibility |
| --- | --- | --- |
| `ServerChunkCache.tick` holder-map callback/result sequence | PASS at transform level only | installed live-world runtime proof |
| holder-map promotion/throttle/consolidation | PARTIAL, transform evidence only | live provider observation |
| `VanillaWorldGenerationDelegate` / direct `ChunkStep.apply` | BLOCKED | generation/status admission adapter plus native-generation receipt |
| `NewChunkStatus` / `NewStatusHook` / `Deferred` readiness | BLOCKED | stable per-status-slot wrapper with incarnation validation |
| `ServerAccessible*` FULL conversion/publication/events | BLOCKED | preparation -> owner finishing -> acknowledged publication adapter and ordering tests |
| `ReadFromDisk*`, async serialization, cancel/failure/stop | BLOCKED | retention/save-completion binding and outstanding-work lifetime tests |
| full co-installation mixin composition | PARTIAL | deterministic per-target composition test |

The production `fabric.mod.json` safety gate against both `c2me` and `c2me-rewrites-chunk-system` remains required. Transform acceptance is not runtime acceptance, and disabling substantive C2ME modules is not an accepted compatibility strategy.

## VMP — fresh completeness review

Locked artifact:

- `run/stage6/frozen-audit-045834b/research/artifacts/vmp/vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar`;
- version `0.2.0+beta.7.236+26.2`;
- SHA-256 `E1BAEB5E1A0948C9FC49415B93EBAAC4007355094B432EA151B3C8CCE7FFE678`;
- `use_optimized_entity_tracking=true`;
- `optimized_entity_tracking_use_staging_area=true`.

The primary blocker remains the authority split between VMP's overwritten `ChunkMap.tick()`/`NearbyEntityTracking.tick` engine and Phoenium's cancellable `RegionalChunkMapTrackingMixin`/`RegionalTrackingService.tick` path. The retained disconnect failures remain valid evidence that a real listener survives the provider removal lifecycle.

The fresh locked-JAR review found provider state that the earlier handoff did not enumerate and that must be part of any future adapter contract:

- `MixinThreadedAnvilChunkStorageEntityTracker` maintains provider-side connection-keyed `seenBy` state and owns listener update/tick behavior;
- `MixinEntityTrackerEntry` owns always-tick/data-sync cadence fields and methods;
- `MixinServerPlayerEntity` maintains provider previous-position bookkeeping;
- the primary storage mixin also participates in `playerMap` and player-movement tracker lookup behavior;
- the sibling `optimize_nearby_player_lookups` mixins change nearest-player queries, mob targeting and spawn-density-cap consumers of nearby-player state.

`STAGE7_VMP_XHIGH_HANDOFF.md` is updated by this closeout to include these surfaces and a per-lifecycle future acceptance matrix. VMP remains `BLOCKED / PARTIAL`; no production adapter was accepted.

## Parent disposition

The fresh investigations strengthen the existing bounded closure without changing its status:

- bounded Stage 7 repair/architecture campaign: `CLOSED`;
- full Stage 7 roadmap exit: `UNMET`;
- Stage 6: `AUDIT PARTIAL`;
- Stage 8+: `NOT STARTED`.

The parent closeout changes documentation/evidence only. Production behavior, C2ME's safety gate, Panda runtime defaults and VMP runtime behavior remain unchanged.
