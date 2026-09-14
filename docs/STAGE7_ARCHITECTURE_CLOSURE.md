# Stage 7 bounded architecture/evidence closure

Updated: 2026-09-11

## A3 continuation amendment — 2026-09-14

A3 bounded-delay mob decisions and vehicle/passenger preservation are now
**RUNTIME ACCEPTED** on W1/W4. The user explicitly excludes a hard ceiling on
arbitrary vehicle-density cost, which would require a separate gameplay-changing
policy. The approved startup/passenger prerequisites and the observed readiness
regression are repaired and covered by the final deterministic/native receipts.
See [STAGE7_A3_ACCEPTANCE_REPORT.md](STAGE7_A3_ACCEPTANCE_REPORT.md) for the exact
artifact and evidence. The next bounded Stage 7 card is **REAL_CLIENT_GATES**;
formal Stage 8 and Stage 9 remain **NOT STARTED**. This supersedes older claims
below that A3 is unwired. The remaining historical architecture record is retained.

## Subsequent Stage 7 continuation amendment — 2026-09-13

The bounded continuation after this 2026-09-11 architecture closeout accepted
the derived per-player pearl ledger through PANDA_A2_LIVE and the derived
per-region/type projectile ledger through PROJECTILE_CAP. Both passed real
Loom/Fabric client-to-dedicated-server W1/W4 receipts on the pinned C2ME
settlement2 + ScalableLux composition; the final production artifact is
`build/libs/phoenium-0.2.0-stage2.jar`, SHA-256
`619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD`.
The historical closure below remains unchanged as the architecture review at
its original date. A3 staggering is still unwired and unaccepted, and formal
combined-stack Stage 8 acceptance remains **NOT STARTED**. See
`STAGE8_ENTRY_CURRENT_WORK.md` for the authoritative active card and receipts.

## Scope and outcome

This record closes the bounded Stage 7 repair/architecture campaign that followed the Stage 6 audit. It does **not** declare the full Stage 7 roadmap exit criteria satisfied.

The checkpoint preserves every previously accepted scoped repair, records the exact remaining architecture gates for Panda-derived native protections, C2ME and VMP, and leaves external-client/audio-dependent acceptance for a later feature-completeness campaign.

Repository baseline for this closure:

- branch: `stage7/scalablelux-persisted-light-repair`
- pre-closure HEAD: `12706fcadde7910344df4a16036ccbe8ee4bdf23` (`stage7: specify native protection policy`)
- Stage 6: `AUDIT PARTIAL`
- bounded Stage 7 repair/architecture campaign: `CLOSED`
- full Stage 7 roadmap exit: `UNMET`
- Stage 8+: `NOT STARTED`

No production/runtime implementation was added by this architecture closeout. The remaining blocked rows stay blocked rather than being converted into compatibility claims.

## Local Qwen worker acceptance

The local Qwen worker was re-verified on 2026-09-11 as a real Codex CLI backend/subagent, not a plain llama.cpp chat call. The durable current-HEAD routing/tool-use receipt and the fresh A1/A2/A3/C2ME/VMP investigations are recorded in `STAGE7_QWEN_CLOSURE_EVIDENCE.md`.

- active wrapper: `C:\AI\Models\qwen-subagent.ps1`
- Codex CLI: `codex-cli 0.153.4`
- provider: `qwen-local`
- model: `Qwen3.8-27B-NVFP4-MTP-MID-HIGH`
- endpoint: `http://127.0.0.1:8080/v1`
- context: `131072`
- KV cache: `Q8_0/Q8_0`
- investigation sandbox: `read-only`

The fresh acceptance probe and bounded workers used Codex repository tools against parent HEAD `4e5592355d40cda3ca0bab3680bab353f6f5816b`. They independently found the Panda policy/test sources and executable eviction/starvation tests, reconfirmed the cross-owner invalidation blocker in `EntityIndexLifecycleAdapter.routingObserved`, verified the locked C2ME binary/safety gate, and tightened the VMP handoff after discovering additional sibling optimized-tracking state that must participate in any future adapter.

The llama.cpp `/v1/models` response does not expose the `slug` field Codex's model registry expects, so Codex logs a nonfatal fallback-metadata warning. That warning did not prevent tool-driven repository inspection and is not treated as a compatibility or routing failure. No API key/token material is retained in this record.

## A1 — non-player cross-owner movement is the projectile-cap prerequisite

The live observation path is:

`Entity.setPosRaw` -> `EntityLifecycleMixin.phoenium$routingObserved` -> `PhoeniumLiveRuntime.onEntityRoutingObserved` -> `EntityIndexLifecycleAdapter.routingObserved`.

Current production behavior is deliberately fail-closed:

- same-owner movement may update the stored `RoutingKey` through `ObjectOwnershipRegistry.updateRoutingKey` while preserving the current object epoch;
- a cross-owner observation invalidates the derived interaction/index candidate and returns;
- the observed destination is not committed as a new owned route;
- `ObjectOwnershipRegistry.transitionOwner` exists, but there is no production caller that commits a physically relocated non-player entity at a quiescent owner boundary;
- `TopologyCoordinator.objects.reconcileTopology()` can only reconcile the routing key already retained by the ownership registry, so it cannot recover a destination that was observed physically but never committed into that registry.

That means a live per-region projectile ledger cannot yet retire/admit a fast projectile exactly once at a region boundary. Enabling the cap now could leak membership or double-count it.

The required future relocation contract is:

1. retain a bounded pending fact `(objectId, destinationRoutingKey, observedObjectEpoch, incarnation)` when a cross-owner route is observed;
2. at a quiescent boundary after directory publication and before transition completion, validate the fact against the current object/incarnation and atomically commit destination key, owner, directory generation/owner epoch, and exactly one object-epoch increment;
3. never block one owner waiting for another; active leases use the coordinator's existing defer/retry semantics;
4. after commit, republish derived indexes and emit exactly one ordered relocation/accounting fact `(oldRegion, newRegion, objectId, incarnation)` so projectile membership retires and admits once;
5. removal, epoch change, incarnation replacement or a newer route observation invalidates an older pending fact.

Future acceptance must cover stranding, one-and-only-one epoch bump, stale queued work rejection/reroute, A->B->A accounting, lease-held deferral without owner waits, incarnation safety, and publication-window proof that dual authority never exists.

Disposition: **documented prerequisite; projectile cap runtime remains disabled**.

## A2 — per-player/per-dimension ender-pearl ledger is derived state only

The intended pearl scope is `(WorldId, player UUID)` and is independent of the pearl's current region. The required membership table must remain a derived association ledger, not a second ownership system.

Allowed ledger facts are:

- `(WorldId, shooter/player UUID)` scope;
- pearl object/entity UUID;
- object incarnation;
- deterministic admission sequence.

The ledger must not store permanent owner authority, leases, authoritative position or independent mutation authority.

Lifecycle rules pinned by the closeout:

- admission follows the existing object/entity addressable lifecycle and `ObjectOwnershipRegistry.admit`;
- restart reconstructs the association from loaded live pearl entities during `PhoeniumLiveRuntime.refreshEntityBindings(level)`, rather than persisting policy counters as authority;
- shooter association is read from the pearl/projectile itself at admission; the exact Minecraft 26.2 owner-UUID accessor/persistence form still needs a version-pinned focused test before production wiring;
- `Entity.setRemoved` -> `PhoeniumLiveRuntime.onEntityRemoved` retires the exact `(objectId, incarnation)` membership;
- player disconnect must not delete still-live pearls; reconnect with the same UUID sees the same scope;
- movement between regions does not change the pearl's player/dimension scope.

Cleanup uses the already specified deterministic oldest-excess order: admission sequence, object ID, then incarnation. Delivery must be one typed owner-routed operation through `ObjectOwnerGateway`, re-resolving current owner at submission and again before execution. Cleanup performs one discard-style lifecycle action with no synthetic hit/death effects and no duplicate removal.

One policy choice remains intentionally open for implementation tests: if ownership changes while cleanup is in flight, preserving the cleanup obligation probably requires rerouting the same object-identity operation to the current owner, while stale authority itself must still be rejected. That `REROUTE` versus terminal stale `REJECT` obligation behavior must be pinned before runtime wiring.

Future acceptance must include ledger idempotence, scope isolation, exact-incarnation retirement, disconnect/reconnect preservation, deterministic restart reconstruction, proof that the ledger cannot mutate native entities, topology-change reroute behavior, and removed-before-delivery terminal settlement exactly once.

Disposition: **DESIGN PASS / RUNTIME PARTIAL; pearl-cap runtime remains disabled**.

## A3 — no safe Minecraft 26.2 staggering seam is accepted yet

Minecraft 26.2 bytecode review shows the existing Phoenium `RegionalChunkGameplayService.entityTicks` boundary wraps the whole native entity tick. Skipping that boundary would change much more than expensive AI work.

Relevant native structure:

- `ServerLevel.tick` drives `EntityTickList.forEach`;
- the native consumer checks removal/freeze/despawn/range/vehicle state and calls `tickNonPassenger`;
- `tickNonPassenger` advances old position and `tickCount`, calls virtual `entity.tick()`, then services passengers;
- `Entity.baseTick` includes portal/fluid/fire/leash lifecycle work;
- `LivingEntity.baseTick` includes damage, breathing, death and effects;
- `LivingEntity.aiStep` includes movement, physics/travel, fluids/freezing/push/collision and conditionally `serverAiStep`;
- `AgeableMob.aiStep` includes aging;
- `Mob.aiStep` includes additional mob behavior around superclass work;
- `Mob.serverAiStep` contains sensing, goals, navigation, brain/control work and `noActionTime`;
- minecart/boat physics live in their own `tick()` methods and couple to passenger servicing.

Rejected runtime seams:

- whole-entity tick skipping: changes movement, aging, despawn/timers, vehicles/passengers and lifecycle;
- whole `aiStep` skipping: also changes movement/physics and age-related behavior;
- removing entities from `EntityTickList` or using private per-entity `TickRateManager` hacks: unsupported and semantically broad.

`Mob.serverAiStep()` is the only plausible narrow future candidate identified by this review, but it is **not accepted**. It still changes sensing, goal selection, navigation, custom brain behavior, controls and `noActionTime`, so a version-pinned compensation/preservation contract and bytecode-drift guard are required first.

Future acceptance must prove farm/breeding/aging parity, bounded AI/goal/navigation/brain behavior, cart/boat/passenger coupling, starvation bounds under dense load, despawn/emptyTime/tickCount phase equivalence, and exemptions for players, controlled vehicles, unknown classes, disabled profile and below-threshold populations.

Disposition: **staggering remains inert/unwired**.

## C2ME closure map

Locked provider:

- artifact: `build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar`
- version: `0.4.2-alpha.0.43+26.2`
- SHA-256: `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`
- release/source revision mapping: unknown; the locked binary is the authority for this campaign.

Accepted subset: `LiveTickPhaseBoundary` recognizes the exact locked-provider holder-map callback/result-consumption sequence inside `ServerChunkCache.tick`. `C2meTickPhaseBoundaryTest` exercises all eight result combinations, ordering, exception propagation and the no-C2ME path. This is **transform-level acceptance only**.

Deep compatibility rows remain:

1. **Generation admission — GATED.** C2ME's `VanillaWorldGenerationDelegate` / `ChunkStep.apply` bypasses Phoenium's vanilla `GenerationChunkHolder.applyStep` interception. A direct adapter into immutable `ChunkWorkRequest` / `ChunkCompletionGateway` plus retained inputs is required.
2. **Status/future readiness — FAIL / HARD GATE.** C2ME `NewChunkHolderVanillaInterface` creates unstable wrappers. Retained evidence reached roughly 12 million UniHandle callbacks before readiness/heap failure. A stable provider status-slot wrapper with incarnation validation is required.
3. **FULL finishing/publication/events — GATED.** C2ME `ServerAccessible` / `ServerAccessibleChunkSending` owns conversion, publication and events. It must map to Phoenium preparation -> owner-local finishing -> acknowledged publication while preserving event order/frequency.
4. **Unload/save/cancel/failure/stop — GATED.** C2ME replacement disk read/write and async serialization paths must bind to `RegionalSaveCoordinator`, custody and terminal acknowledgement semantics.
5. **Holder-map promotion tick subset — ACCEPTED at transform level only.**
6. **Co-installation mixin composition — GATED.** Per-method deterministic composition audit remains required.

The production safety boundary remains in `src/main/resources/fabric.mod.json`:

```json
"breaks": {
  "c2me": "*",
  "c2me-rewrites-chunk-system": "*"
}
```

The retained W1/W4 cases prove Loader rejects root C2ME before world creation. A prior nested-only conflict approach was rejected because Loader silently omitted substantive provider modules.

Disposition: **C2ME BLOCKED with one accepted tick-phase transform subset**. Do not remove the safety gate or call startup/phase recognition full integration. Reopen in this order: stable status-slot wrappers -> gateway-bound generation/status completion -> three-phase FULL split -> save/cancel/stop binding.

## VMP optimized-tracking closure

Locked provider:

- version: `0.2.0+beta.7.236+26.2`
- artifact: `run/stage6/frozen-audit-045834b/research/artifacts/vmp/vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar`
- SHA-256: `E1BAEB5E1A0948C9FC49415B93EBAAC4007355094B432EA151B3C8CCE7FFE678`
- required provider options remain enabled: `use_optimized_entity_tracking=true` and `optimized_entity_tracking_use_staging_area=true`.

The blocker is an engine-level lifecycle conflict. VMP replaces tracker insertion/removal/reverse sets and overwrites `ChunkMap.tick()` with `NearbyEntityTracking.tick(distanceManager)`. Phoenium's `RegionalChunkMapTrackingMixin` injects cancellable HEAD behavior on the same method and runs `RegionalTrackingService.tick`, which cancels the provider engine while independently mutating real `TrackedEntity.seenBy` relationships.

Retained Stage 6 evidence contains `removed tracker retains a connection outside the live session cut`; the Stage 7 fail-closed probe later showed `removed player remains in an authorized tracker projection`, proving a real `seenBy` relationship survives provider removal.

`STAGE7_VMP_XHIGH_HANDOFF.md` is the durable future architecture contract, but the fresh current-HEAD review deliberately leaves implementation acceptance blocked. It now covers staging/promotion, player indexes, area-map movement, server-entity tick, disconnect, staged/entity removal, `broadcastRemoved`, range/topology, failure and stop behavior, and also enumerates the sibling provider state that an adapter must reconcile: connection-keyed provider `seenBy`, entry sync/tick cadence, player previous-position state, primary `playerMap`/movement state, and nearby-player consumer mixins.

Future work must first design one authoritative owner-routed provider protocol that reconciles those sibling surfaces rather than treating only `NearbyEntityTracking` as the provider engine. The lifecycle matrix in the handoff remains intentionally unaccepted until contracts/tests exist for all nine rows.

Two future checks remain explicit:

- build the named strict native tracker comparator when VMP work resumes;
- reconcile the sibling optimized-tracking mixins in the same provider package, not only the primary `MixinThreadedAnvilChunkStorage` overwrite.

Acceptance order remains: sibling-state/provider-protocol reconciliation -> strict native tracker comparator -> real-client disconnect/reconnect with zero retained `seenBy` -> W1/W4 lifecycle fresh/restart -> bounded multi-player sample.

Disposition: **VMP BLOCKED / PARTIAL and optional; no production patch accepted**.

## Final campaign dispositions

| Area | Bounded campaign disposition |
| --- | --- |
| Chunky asynchronous FULL/ABSENT authority repair | scoped PASS; broader provider PARTIAL |
| ScalableLux persisted-light adapter | scoped PASS; broader provider PARTIAL |
| Lithium collision/ticker adapter | scoped PASS; broader provider PARTIAL |
| ServerCore callback preservation | scoped PASS; behavior-changing modules remain unaccepted |
| Clumps lifecycle | scoped PASS |
| Geyser/Floodgate stale reconnect lifecycle | scoped PASS_ADAPTER; actual Bedrock feature acceptance PARTIAL |
| FastNoise fixed-boundary attribution | BLOCKED/PARTIAL because the control oracle is nondeterministic |
| Structure Layout Optimizer strict seed parity | excluded from strict-parity profile pending upstream fix or explicit changed-layout policy |
| Panda native protections | DESIGN PASS / RUNTIME PARTIAL; A1/A2/A3 gates above |
| C2ME | BLOCKED; tick-phase transform subset accepted, safety gate retained |
| VMP optimized tracking | BLOCKED/PARTIAL; optional future architecture work |

The external-client/audio rows are intentionally deferred: supported Bedrock gameplay/identity/persistence, an older Java client through the Via chain, and two working Java voice clients for actual audio/proximity/reconnect behavior. Boot, listener bind or provider initialization does not substitute for those feature checks.

Before any Stage 8 Bedrock acceptance campaign, establish a legitimate repeatable Bedrock client test setup. Research must identify supported Windows or Android client options, ownership/authentication and version requirements, whether an existing local installation/device can be reused, launch/input automation, logging/diagnostics, and a reproducible Geyser/Floodgate join -> gameplay -> disconnect -> restart procedure. Actual game-client observations must be distinguished from narrower protocol tests; a synthetic bot, handshake or server startup is not Bedrock gameplay proof. Do not assume a Java-style `runClient` equivalent exists. Bedrock, legacy Via and voice/audio acceptance remain unclaimed until their corresponding facilities/tests exist.

## Closure verification

Verification was run against the exact documentation checkpoint before commit:

- focused native-protection specification: `NativeProtectionPolicyTest` — `10` tests, `0` failures, `0` errors, `0` skipped;
- full deterministic Gradle suite: `857` tests across `92` suites, `0` failures, `0` errors, `0` skipped;
- `git diff --check`: clean apart from Git's existing LF/CRLF conversion notices on edited Markdown files;
- production JAR remained unchanged by the documentation-only closeout: `build/libs/phoenium-0.2.0-stage2.jar` SHA-256 `D095C007C1B81698440F3BF0145E38A23DC6078F7033D713324F61F15F2C3CC9`.

This closes the **bounded Stage 7 repair/architecture campaign**. It does not satisfy the roadmap's full Stage 7 exit criterion that each chosen path be fully accepted, so Stage 8 remains `NOT STARTED`.
