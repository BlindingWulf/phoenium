# Roadmap

The compatibility work below is deliberately post-core. It must not destabilize the locked Phoenium architecture through premature parallel fork development. The intended order is:

```text
Phoenium core correctness
        ↓
serial live integration
        ↓
ownership boundaries
        ↓
regional services
        ↓
bounded parallelism
        ↓
required-stack compatibility audit
        ↓
adapters / patches / forks as proven necessary
        ↓
feature-complete compatibility
        ↓
fork-native optimization
        ↓
Android tuning
```

## Stage 0 — source truth and architecture lock

**Status: complete**

Delivered:

- current-source comparison for Folia, Ruthenium, PandaAntiLag, Fabric, and Minecraft 26.2;
- license decision before reuse;
- component, synchronization, and current-Folia-delta audits;
- serial-first architecture, hard failure barrier, and no-fake-fallback policy;
- compatibility, test, benchmark, and handoff plans.

Exit checkpoint: **PHOENIUM_P0_SOURCE_TRUTH_LOCKED**

The compatibility and fork-policy amendment is a documentation commit on top of that historical checkpoint. It does not move, replace, or invalidate the checkpoint.

## Stage 1 — isolated core model

**Status: complete — `PHOENIUM_P1_SERIAL_REGION_CORE_PASS`**

Create a standard Fabric 26.2 server-only project skeleton and a pure-Java deterministic core. Do not add Minecraft mixins or route live ticks yet.

Required work:

1. Pin the Stage 0 toolchain and add the planned GPL-3.0-only LICENSE before any source-derived work.
2. Implement independent domain models for RegionId, topology generation, owner epoch, lifecycle transitions, typed task envelopes, owner gateway, worker ownership context, and failure barrier.
3. Implement deterministic serial-first scheduling with exactly one worker.
4. Implement topology plans and transition mailboxes without peer locks.
5. Implement the Stage 1 tests C-01 through C-08 and applicable regressions.
6. Add build/unit report evidence to HANDOFF.

Exit criteria: clean build; deterministic core tests; no code derived from upstream without recorded provenance; zero unleased-task and fallback paths.

Evidence is recorded in [STAGE1_CORE.md](STAGE1_CORE.md),
[STAGE1_BUILD_COMPATIBILITY.md](STAGE1_BUILD_COMPATIBILITY.md), and [HANDOFF.md](HANDOFF.md).

## Stage 2 — Fabric lifecycle and serial live integration

Identify current 26.2 hooks from bytecode and current Fabric sources. Introduce the smallest server integration necessary to start/stop Phoenium and route one controlled tick flow in serial-first mode.

Exit criteria: dedicated Fabric server boot; server-only metadata; exactly-once tick instrumentation; hard barrier test; no client artifacts or broad world locks.

## Stage 3 — ownership boundaries

Add owner-routed entity movement, player/packet ingress, chunk work completion adapter, and the C2ME boundary contract. No direct C2ME integration starts before the contract is written and tested.

Exit criteria: transfer, packet, stale-epoch, chunk completion, and dedicated boot tests pass in serial-first mode.

## Stage 4 — regional world services

Add per-region scheduled ticks, block events, entity tracking, save cut, shutdown coordination, and explicitly classified global services.

Exit criteria: save/shutdown stress; no raw unowned queue execution; migration preserves all declared regional state.

## Stage 5 — bounded parallelism

Enable more than one worker only after serial oracle comparisons pass.

Exit criteria: deterministic serial-versus-parallel harness results, zero ownership violations, and benchmark evidence for Phoenium itself.

## Stage 6 — required-stack compatibility audit

Audit the official required stack from [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) with no claim based on boot alone. Start with C2ME; test Krypton last and independently. Classify every finding under the documented hierarchy and retain reproduced failures, invariant evidence, configuration coverage, and interaction evidence.

Exit criteria: each target remains `NOT_TESTED`, `PARTIAL`, `BLOCKED`, or advances only on actual evidence; no fork is created preemptively.

## Stage 7 — proven adapters, patches, forks, and replacements

After an incompatibility is reproduced and the adapter evaluation is documented, implement the minimum correct answer: adapter, narrow upstream-compatible patch, maintained fork, or justified native replacement. Panda Anti-Lag behavior is considered here as a native Phoenium adaptation, not a final external runtime dependency.

Exit criteria: each chosen path preserves the threading invariants; maintained forks have the required provenance, license, build, test, and divergence records; Phase A correctness/feature parity passes before Phase B integration work.

## Stage 8 — feature-complete required-stack compatibility

Prove the required stack in serial and parallel modes with major configuration modes, features, cross-stack interactions, ownership checks, save/restart, shutdown, and applicable stress tests. `PARTIAL` is not a production gate result.

Exit criteria: every official required target has a full `PASS_*` status backed by recorded evidence, or release is blocked.

## Stage 9 — fork-native optimization

Only after fork Phase A correctness and Phase B direct Phoenium integration pass, optimize allocations, scans, queues, caches, executors, concurrency budgets, and data layout. Benchmark every meaningful change separately from correctness work.

Exit criteria: feature parity and ownership proofs remain passing; benchmarks document each accepted optimization.

## Stage 10 — Android tuning

Tune the proven Phoenium and required-stack deployment for the resource-constrained Android target. Avoid permanent expensive compatibility serialization in high-frequency paths.

Exit criteria: Android-specific resource and stress evidence without weakened ownership invariants or required-feature loss.

## Secondary / future compatibility

VMP, Carpet, Servux, WorldEdit, LuckPerms, and BlueMap are not required production dependencies and do not block the official-stack stages. They may be evaluated after the required deployment stack under the same policy.

## Release gate

Release consideration requires actual build, unit, integration, dedicated-boot, ownership/fallback, compatibility, benchmark, known-limitation, git-revision, and reviewed HANDOFF evidence. The official required stack must meet feature-complete `PASS_*` acceptance; a successful launch alone is insufficient.
