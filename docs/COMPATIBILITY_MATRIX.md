# Compatibility matrix

Current evidence is in the single [Stage 6 audit matrix](STAGE6_AUDIT_MATRIX.md), with **AUDIT PARTIAL** scope and downstream obligations. The material below remains the historical Stage 0 policy/plan; its `NOT_TESTED` labels are not the current audit dispositions. A successful launch, accepted JAR, absent Mixin conflict, or one apparent feature invocation is not compatibility proof.

## Status values and production gate

| Status | Meaning |
| --- | --- |
| `PASS_NATIVE` | Upstream works unchanged and all intended features work correctly under Phoenium. |
| `PASS_ADAPTER` | Upstream remains unchanged; an isolated Phoenium adapter/integration layer preserves all intended features. |
| `PASS_CONFIG` | A safe configuration arrangement preserves all intended functionality. Disabling an important feature is not this status. |
| `PASS_UPSTREAM_PATCH` | A required source change is submitted upstream or maintained as a very small upstream-compatible patch. |
| `PASS_FORK` | A dedicated Phoenium-compatible fork is maintained because source changes inside the other project are required for correct, performant behavior. |
| `PASS_NATIVE_REPLACEMENT` | Phoenium intentionally supplies equivalent or better native functionality under the replacement policy below. |
| `PARTIAL` | The mod runs but an intended feature is unavailable, degraded, semantically different, unsafe, or unverified. |
| `BLOCKED` | No currently known solution preserves both correctness and required functionality. |
| `NOT_TESTED` | No evidence exists yet. |

For an official required-stack mod, production acceptance requires a full `PASS_*` status. `PARTIAL` is not production acceptance.

The solution hierarchy is mandatory:

```text
PASS_NATIVE
    ↓
PASS_ADAPTER
    ↓
PASS_CONFIG
    ↓
PASS_UPSTREAM_PATCH
    ↓
PASS_FORK
    ↓
PASS_NATIVE_REPLACEMENT where justified
    ↓
BLOCKED if no correct solution exists
```

## Core environment

| Component | Required posture | Stage 0 status | Future gate |
| --- | --- | --- | --- |
| Minecraft 26.2 | Exact runtime target | Toolchain identified | Dedicated boot on the exact locked artifact |
| Fabric Loader 0.19.5 | Required | Version selected | Dedicated boot |
| Fabric API 0.159.0+26.2 | Required | Version selected | API and dedicated boot |
| Java 25 | Required | Host version inspected | CI and dedicated boot |
| Server-only metadata | Required | Design locked | Metadata inspection and launch |

## Official required deployment stack

These are required compatibility targets, not optional examples. Panda Anti-Lag is an external runtime dependency to be replaced after its relevant behavior is correctly adapted natively; it is not part of the final required runtime stack. Krypton is intentionally tested later and in isolation.

| Group | Target | Stage 0 status | Anticipated integration audit area |
| --- | --- | --- | --- |
| Core / performance | C2ME | `NOT_TESTED` | Highest priority: generation, asynchronous chunk loading and I/O, holder lifecycle, generation/cancellation, owner-routed completion, lease/topology transitions, non-blocking workers, duplicate queues, and executor CPU budgets. Adapter first; a fork is allowed only if correct high-performance integration requires substantial C2ME changes. |
| Core / performance | Lithium | `NOT_TESTED` | Audit optimization modules for main-thread assumptions, global mutable caches, owner-sensitive entity/chunk collections, and world-spanning work. Preserve upstream architecture where possible. |
| Core / performance | FerriteCore | `NOT_TESTED` | Expected low risk, but verify memory-layout behavior and ownership assumptions rather than assume. |
| Core / performance | ServerCore | `NOT_TESTED` | Audit mobcaps, spawning, entity activation, dynamic simulation/view/tick distances, chunk ticking, statistics, global counters, and duplicate Phoenium accounting. Prefer owner-local Phoenium data to duplicate scans/counters. |
| Core / performance | Clumps | `NOT_TESTED` | Audit XP-orb merging at ownership boundaries; evaluate adapter, small fork, or future native implementation. |
| Core / performance | Alternate Current | `NOT_TESTED` | First-class difficult redstone integration: preserve complete cross-region network semantics. No fake AIR/BEDROCK, dropped work, foreign mutation, or behavior change merely to avoid a fork. A Phoenium-aware fork is allowed and may be likely if source-level integration is necessary. |
| Core / performance | ScalableLux | `NOT_TESTED` | Audit async lighting, completion routing, chunk unload, ownership changes while pending, executor budgets, and C2ME interaction. Adapter first; fork allowed if needed. |
| Core / performance | Fast Noise | `NOT_TESTED` | Test world-generation behavior with C2ME and Phoenium chunk ownership. |
| Core / performance | Structure Layout Optimizer | `NOT_TESTED` | Test world-generation behavior with C2ME and Phoenium chunk ownership. |
| Core / performance | Chunky | `NOT_TESTED` | Test high-volume pregeneration across the Phoenium/C2ME chunk boundary. |
| Protocol / cross-version / Bedrock | ViaFabric / ViaVersion | `NOT_TESTED` | Route state-changing translated work to the current player owner; test login, movement, teleport, portals, interaction, inventory, vehicles/passengers, disconnect/reconnect, and region crossings. |
| Protocol / cross-version / Bedrock | ViaBackwards | `NOT_TESTED` | Test with the ViaFabric/ViaVersion translated player-owner lifecycle and all applicable protocol features. |
| Protocol / cross-version / Bedrock | ViaRewind | `NOT_TESTED` | Test with the ViaFabric/ViaVersion translated player-owner lifecycle and all applicable protocol features. |
| Protocol / cross-version / Bedrock | Geyser-Fabric | `NOT_TESTED` | Test real Bedrock clients: movement, teleports, entity/inventory/world interactions, portals, death/respawn, and vehicles/passengers. Prefer changing the Fabric integration layer over forking Geyser core. |
| Protocol / cross-version / Bedrock | Floodgate | `NOT_TESTED` | Audit authentication, player lifecycle, and callbacks that touch region-owned state. |
| Gameplay | Simple Voice Chat | `NOT_TESTED` | Keep audio processing independent of simulation workers; location/player-state access uses safe snapshots or owner routing. Reuse safe Folia-aware behavior if present rather than fork unnecessarily. |
| Diagnostics | spark | `NOT_TESTED` | Keep workers, regions, queues, and profiler sections observable without making diagnostics simulation authority. |
| Later isolated A/B test | Krypton | `NOT_TESTED` | Test last and independently; audit connection and packet scheduling boundaries. |

## Feature-complete required-stack proof

For every required target, tests must cover startup, shutdown, major relevant configuration modes, major gameplay/runtime features, Phoenium ownership interaction, interaction with other required-stack members, serial Phoenium mode, parallel Phoenium mode, save/restart, and applicable stress behavior. The objective is:

> Every required mod works as intended, with its required upstream functionality preserved, unless Phoenium intentionally replaces that functionality with an equivalent or better native implementation.

A test does not pass because Fabric Loader accepts the JAR, no Mixin conflict occurs, the server reaches `Done`, a feature works once, ownership assertions were disabled, or important features were turned off.

## Fork and replacement policy

Required deployment mods are not abandoned merely because current upstream code assumes Minecraft's traditional single-threaded world model. Conversely, Phoenium's threading invariants are never weakened to keep upstream unchanged. For a reproduced incompatibility, attempt native compatibility, Phoenium-side adapter, feature-complete safe configuration, narrow upstream-compatible patch, Phoenium-maintained fork, then native replacement only when justified.

Do not fork preemptively. A fork begins only after the incompatibility is reproduced; a deterministic or sufficiently repeatable test exists; the violated ownership/concurrency assumption is identified; an external adapter is evaluated; and the reason source modification is preferable is documented.

`PASS_NATIVE_REPLACEMENT` is justified only when the feature belongs directly to Phoenium's ownership/scheduler architecture, the original functionality is small enough that a fork is unnecessary, or two independent systems would create redundant work or conflicting authority. It is not a shortcut for incompatible upstream functionality.

Every maintained fork must preserve upstream license and copyright requirements, upstream behavior outside intentional Phoenium integration changes, independent buildability, reproducible tests, exact provenance, and a patch-stack-oriented minimal divergence. Its record must contain:

```text
Upstream project
Upstream repository
Upstream revision/SHA
Upstream version
Upstream license

Phoenium fork repository
Phoenium fork revision

Reason fork exists
Violated Phoenium invariant
Subsystems changed
Features affected

Regression tests
Feature-completeness tests

Optimization changes
Behavioral changes, if any

Upstream contribution status
Last upstream rebase
Current divergence
```

Fork work is phased. **Phase A — correctness / feature parity** changes only what is necessary for correct ownership/threading and requires `UPSTREAM FEATURE PARITY = PASS`, `PHOENIUM OWNERSHIP = PASS`, `SERIAL MODE = PASS`, and `PARALLEL MODE = PASS`. **Phase B — native Phoenium integration** may replace generic shims with direct APIs, remove redundant queues/bookkeeping, use owner-local counters/snapshots, and route async completion directly while preserving behavior. **Phase C — optimization** begins only after A and B: allocations, data structures, scans, batching, cache locality, executors, shared concurrency budgets, later Android-specific work, and every meaningful benchmark change are isolated from correctness work.

## Future generic compatibility layer and its limits

No layer is implemented by this amendment. A future Phoenium compatibility API may provide region-local execution, entity-owner execution, global Phoenium execution, async calculation with owner-routed completion, ownership queries/assertions, immutable snapshots, typed task envelopes, capability discovery, and safe region-transition requests. It must use the locked OwnerGateway, RegionDirectory, OwnershipContext, region leases, topology generations, owner epochs, typed task envelopes, GlobalServiceContext, and failure barrier. It must not create a second ownership system.

Generic compatibility cannot turn every single-threaded algorithm into safe region-parallel work. It cannot generally repair synchronous multi-region operations; immediate reads of mutable foreign state; synchronous multi-region mutation; global mutable algorithms that assume one world thread; unsafe static/global caches; captured live Minecraft objects crossing threads; blocking cross-region `Future.get`/`join`; custom executors mutating live regional state; or required ordering that fundamentally spans ownership boundaries. These need algorithm redesign, topology convergence/temporary merge, Phoenium-aware source changes, an upstream patch, or a fork. Broad locks and hot-path global serialization are not acceptable concealment.

Unknown or legacy third-party mods may later use a conservative, measurable compatibility fallback that serializes or quiesces affected work only when exact regional semantics cannot be inferred. It is never an unsafe vanilla fallback, respects lease boundaries, reports diagnostic use, and is not the preferred model. High-frequency official-stack targets must not permanently depend on expensive global serialization; for them, prefer adapter, patch, or fork, particularly for the future resource-constrained Android target.

## Secondary / future compatibility

VMP, Carpet, Servux, WorldEdit, LuckPerms, and BlueMap are secondary/future targets only. They do not consume required-stack gates and are not production acceptance dependencies for the planned Phoenium deployment.

## Stage 0 scheduling rule

The first compatibility implementation occurs only after the core serial-first scheduler, topology model, owner gateway, and dedicated-server baseline pass their gates. Each unproven adapter feature remains disabled behind a clear startup capability result; that containment never relaxes ownership or becomes a claim that the target is compatible.
