# Roadmap

## Stage 0 — source truth and architecture lock

**Status: complete**

Delivered:

- current-source comparison for Folia, Ruthenium, PandaAntiLag, Fabric, and Minecraft 26.2;
- license decision before reuse;
- component, synchronization, and current-Folia-delta audits;
- serial-first architecture, hard failure barrier, and no-fake-fallback policy;
- compatibility, test, benchmark, and handoff plans.

Exit checkpoint: **PHOENIUM_P0_SOURCE_TRUTH_LOCKED**

## Stage 1 — isolated core model

Create a standard Fabric 26.2 server-only project skeleton and a pure-Java deterministic core. Do not add Minecraft mixins or route live ticks yet.

Required work:

1. Pin the Stage 0 toolchain and add the planned GPL-3.0-only LICENSE before any source-derived work.
2. Implement independent domain models for RegionId, topology generation, owner epoch, lifecycle transitions, typed task envelopes, owner gateway, worker ownership context, and failure barrier.
3. Implement deterministic serial-first scheduling with exactly one worker.
4. Implement topology plans and transition mailboxes without peer locks.
5. Implement the Stage 1 tests C-01 through C-08 and the applicable regression tests.
6. Add build/unit report evidence to HANDOFF.

Exit criteria: clean build; deterministic core tests; no code derived from upstream without recorded provenance; zero unleased-task and fallback paths.

## Stage 2 — Fabric lifecycle and serial live integration

Identify current 26.2 hooks from bytecode and current Fabric sources. Introduce the smallest server integration necessary to start/stop Phoenium and route one controlled tick flow in serial-first mode.

Exit criteria: dedicated Fabric server boot; server-only metadata; exactly-once tick instrumentation; hard barrier test; no client artifacts or broad world locks.

## Stage 3 — ownership boundaries

Add owner-routed entity movement, player/packet ingress, chunk work completion adapter, and current C2ME compatibility audit. No direct C2ME integration starts before the contract is written and tested.

Exit criteria: transfer, packet, stale-epoch, chunk completion, and dedicated boot tests pass in serial-first mode.

## Stage 4 — regional world services

Add per-region scheduled ticks, block events, entity tracking, save cut, shutdown coordination, and explicitly classified global services.

Exit criteria: save/shutdown stress; no raw unowned queue execution; migration preserves all declared regional state.

## Stage 5 — bounded parallelism and target compatibility

Enable more than one worker only after serial oracle comparisons pass. Exercise C2ME, Lithium, FerriteCore, VMP, Krypton, Carpet, Spark, Chunky, Servux, WorldEdit, LuckPerms, and BlueMap incrementally.

Exit criteria: deterministic serial-versus-parallel results for harness workloads, zero ownership violations, compatibility matrix evidence, and benchmark report.

## Stage 6 — PandaAntiLag behavioral adaptation

Implement only independently validated local region throttling behavior. Audit every entity-class compensation rule and make any project-wide limiter a global aggregation of owner-local counters.

Exit criteria: behavior tests, no dimension-key collision, no global entity scans in regional hot paths, and compatibility boot coverage.

## Release gate

Release consideration requires every required report category to be populated with actual results: build, unit tests, integration tests, dedicated boot, ownership/fallback violations, compatibility results, benchmarks, known limitations, git revision, and a reviewed HANDOFF.

