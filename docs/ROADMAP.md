# Roadmap

> **Owner roadmap, 2026-10-16 (authoritative for stage numbering from Stage 9 on).**
>
> - **Stage 9, Performance, optimization, stability and certification: CURRENT.** It includes the Android and low-end
>   device tuning that older revisions of this file called "Stage 10".
> - **Stage 10, our already developed Minecraft server management application: ALREADY DEVELOPED.** This is the
>   existing management app, not a future compatibility stage. Do not restart or redefine it.
> - **Stage 11, Broad Fabric mod compatibility, optional Bukkit/Paper plugin-bridge interoperability, and realistic SMP
>   functionality testing: PLANNED, NOT IMPLEMENTED.**
> - **After Stage 11: Minecraft 26.3 support.**
>
> Stages 0-8 below are historical records and keep their original wording. A preliminary Stage 11 risk assessment is in
> [STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md](STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md).

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
performance, optimization, stability, certification + device tuning (Stage 9, current)
        ↓
server management application (Stage 10, already developed)
        ↓
broad Fabric mod compatibility, optional plugin bridge, SMP testing (Stage 11, planned)
        ↓
Minecraft 26.3 support
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

**Completed at `PHOENIUM_P2_FABRIC_SERIAL_LIVE_PASS` (see the Stage 2 binding and
server evidence documents).** The accepted narrow binding is the one direct
`ServerLevel.tick(BooleanSupplier)` call from `MinecraftServer.tickChildren`. It is
redirected through the existing gateway/scheduler/lease/context path with a single
serial lane. This does not authorize Stage 3 work: all entity/player/chunk/packet and
parallel-region integration remains next-stage work.

Identify current 26.2 hooks from bytecode and current Fabric sources. Introduce the smallest server integration necessary to start/stop Phoenium and route one controlled tick flow in serial-first mode.

Exit criteria: dedicated Fabric server boot; server-only metadata; exactly-once tick instrumentation; hard barrier test; no client artifacts or broad world locks.

## Stage 3 — ownership boundaries

Add owner-routed entity movement, player/packet ingress, chunk work completion adapter, and the C2ME boundary contract. No direct C2ME integration starts before the contract is written and tested.

**Status: STAGE3_SERIAL_CORRECTNESS_FROZEN**, within the supported scope and explicit
deferrals in [STAGE3_CLOSURE_REPORT.md](STAGE3_CLOSURE_REPORT.md). That report supersedes
older partial contributor evidence and preserves the executable serial reference.

Exit criteria: transfer, packet, stale-epoch, chunk completion, and dedicated boot tests pass in serial-first mode.

## Stage 4 — regional world services

**Status: complete — STAGE4_PARALLEL_CORRECTNESS_FROZEN.** All17 declared gates passed.
See the [closure report and Stage5 handoff](STAGE4_CLOSURE_REPORT.md) and
[current work](STAGE4_CURRENT_WORK.md). One gameplay worker; parallel gameplay
remains OFF in this immutable reference. Stage 5 is now separately frozen below.

Add per-region scheduled ticks, block events, entity tracking, save cut, shutdown coordination, and explicitly classified global services.

Exit criteria: save/shutdown stress; no raw unowned queue execution; migration preserves all declared regional state.

## Stage 5 — bounded parallelism

**Status: PASS — STAGE5_PARALLEL_SCALING_FROZEN.**
See [STAGE5_CLOSURE_REPORT.md](STAGE5_CLOSURE_REPORT.md) for the exact artifact,
834 tests / 88 suites, strict frozen/candidate 1/2/4 controls at 5/5 and 8/8,
unrestricted clients, native FULL fresh/reload, churn/adverse/persistence, zero-residue
receipts and the 24-run Phoenium-only benchmark. Natural original gameplay overlaps
under current independent claims. Four workers provide modest distributed benefit;
concentrated work regresses. Stage 4 remains the immutable serial reference.
Stage 6 now has a closed bounded audit; see its PARTIAL status below.

Exit criteria: deterministic serial-versus-parallel harness results, zero ownership violations, and benchmark evidence for Phoenium itself.

## Stage 6 — required-stack compatibility audit

**Status: AUDIT PARTIAL, bounded pass closed 2026-09-09.** See [STAGE6_AUDIT_REPORT.md](STAGE6_AUDIT_REPORT.md), [STAGE6_AUDIT_MATRIX.md](STAGE6_AUDIT_MATRIX.md), and [STAGE7_AUDIT_BACKLOG.md](STAGE7_AUDIT_BACKLOG.md). No production acceptance or automatic Stage 7 work follows from this audit.

Audit the official required stack from [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) with no claim based on boot alone. Start with C2ME; test Krypton last and independently. Classify every finding under the documented hierarchy and retain reproduced failures, invariant evidence, configuration coverage, and interaction evidence.

Exit criteria: each target remains `NOT_TESTED`, `PARTIAL`, `BLOCKED`, or advances only on actual evidence; no fork is created preemptively.

## Stage 7 — proven adapters, patches, forks, and replacements

After an incompatibility is reproduced and the adapter evaluation is documented, implement the minimum correct answer: adapter, narrow upstream-compatible patch, maintained fork, or justified native replacement. Panda Anti-Lag behavior is considered here as a native Phoenium adaptation, not a final external runtime dependency.

**Current status: full roadmap exit UNMET.** The bounded Stage 7 repair/architecture campaign closed on 2026-09-11; see [STAGE7_ARCHITECTURE_CLOSURE.md](STAGE7_ARCHITECTURE_CLOSURE.md). That checkpoint preserves scoped accepted repairs and pins the remaining Panda, C2ME and VMP gates, but does not promote PARTIAL/BLOCKED rows into Stage 7 exit acceptance.

Exit criteria: each chosen path preserves the threading invariants; maintained forks have the required provenance, license, build, test, and divergence records; Phase A correctness/feature parity passes before Phase B integration work.

## Stage 8 — feature-complete required-stack compatibility

Prove the required stack in serial and parallel modes with major configuration modes, features, cross-stack interactions, ownership checks, save/restart, shutdown, and applicable stress tests. `PARTIAL` is not a production gate result.

Entry prerequisite for Bedrock acceptance: first research and establish a legitimate repeatable Windows or Android Bedrock client setup with ownership/authentication and version requirements, launch/input automation, diagnostics/logging, and a reproducible Geyser/Floodgate join/gameplay/disconnect/restart procedure. Actual-client observations must remain distinct from protocol-only tests; do not assume a Java-style `runClient` equivalent or treat a bot/handshake/server startup as gameplay proof.

Exit criteria: every official required target has a full `PASS_*` status backed by recorded evidence, or release is blocked.

## Stage 9 — performance, optimization, stability and certification (CURRENT)

**Status: in progress.**

The current evidence status is in `.audit/stage9-20261009/final/STAGE9_STATUS_20261016.md`.

**Goal.** Phoenium becomes an extremely efficient Minecraft server optimization mod across:
- low-powered Android phones;
- affordable and older PCs;
- modern gaming desktops;
- larger dedicated servers.

**What that means in practice:**
- lower MSPT, stable TPS and lower latency;
- less memory, faster chunk loading and fewer stalls;
- multithreading that is efficient:
  - parallel processing where it helps;
  - serial execution when coordination or worker contention would make it slower;
- strong correctness guarantees: world integrity, entity behaviour, chunk correctness, persistence, and normal
  gameplay.

The original "fork-native optimization" rule still holds. Only after correctness, optimize:
- allocations, scans and queues;
- caches and executors;
- concurrency budgets and data layout.

Benchmark every meaningful change separately from correctness work. Android and low-end tuning is part of this stage:
- Avoid permanent, expensive compatibility serialization in high-frequency paths.
- Keep the phone's screen-on condition primary and screen-off as a separate worst case.

**Comparison rules.** Comparisons must be fair, measurable and repeatable, and must include real user experience
(joins, stalls, bot actions), not TPS alone. Never trade compatibility or correctness for a benchmark gain.

**Certification.** A candidate is promoted (experimental → beta → stable) only through the Stage 9 gates:
- the correctness gate set;
- device benchmarks under fixed conditions;
- the promotion policy (`.audit/stage9-20261009/candidates/PROMOTION_POLICY.json`).

Real failures are kept as results, and gates are never loosened to produce a pass.

**Stage 11 constraints on Stage 9 optimization.** Every future optimization must account for these, so that it does not
make Stage 11 harder (risk register: [STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md](STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md)
§3):
- **event-thread requirements:** which thread mod, Fabric API and future plugin callbacks run on;
- **cross-region entity ownership:** entities touched from another tile or region;
- **callback ordering:** deferred effects and early return values;
- **packet correctness:** thread, order, and no duplicate sends;
- **persistence:** save timing, dirtiness, and hard-kill safety.

**Exit criteria:**
- feature parity and ownership/correctness proofs keep passing;
- benchmarks document each accepted optimization;
- the device matrix (Android phone, low-end PC, desktop, dedicated server) shows no unexplained regression against
  the same stack without Phoenium (L5).

## Stage 10 — Minecraft Server Management Application (ALREADY DEVELOPED)

**Status: developed.**

Stage 10 is the server management application already built: the Phoenix app and its backend/frontend. It gives an
accessible way to configure, manage and operate Minecraft servers, including:
- server controls;
- settings;
- networking features;
- integrations.

It is **not** a future compatibility stage, and it must not be restarted or redefined.

Rules for Phoenium releases:
- Preserve the app's integration with Phoenium.
- Stay compatible with the app without needing unnecessary changes to its backend or frontend.
- Phone benchmarks use only the disposable test app `dev.phoenix.server.uicheck`, never production installs or worlds.

Older revisions of this file named this stage "Android tuning". That work now belongs to Stage 9.

## Stage 11 — broad Fabric mod compatibility, optional Bukkit/Paper plugin-bridge interoperability, and realistic SMP functionality testing (PLANNED, NOT YET IMPLEMENTED)

**Status: planned; not started.**

**Purpose.** Phoenium should be broadly compatible with the ecosystem of Minecraft server mods, and with plugins where
feasible, so that people can run real multiplayer SMP communities without losing Phoenium's optimization benefits. The
long-term ambition: ordinary compatible mods work normally without their developers writing Phoenium-specific
integration.

### A. Native Fabric compatibility (primary ecosystem, first class)

Test broad categories:
- gameplay and utility;
- player management and administration;
- economy, shops and trading;
- kits;
- homes, teleports and warps;
- permissions and land protection;
- inventories, containers and storage;
- networking and protocol mods;
- other performance mods.

Verify Fabric API behaviour:
- event callbacks;
- lifecycle hooks;
- registries;
- networking;
- persistence;
- world-state operations.

Give special attention to mods that use entities, chunks, dimensions, inventories and scheduling. The parallel engine must
not introduce unsafe event ordering, races, duplicated transactions, missing callbacks or broken saves.

### B. Bukkit / Spigot / Paper plugins (optional bridge)

- Explore support through an optional compatibility bridge (for example a suitable Cardboard implementation, or another
  validated approach).
- Fabric does not provide the Bukkit/Paper API, so never assume Paper plugins work.
- First find out:
  - which bridges and APIs actually support the target Minecraft version;
  - their limitations;
  - which plugin capabilities can be provided safely.
- Do not embed an unverified bridge in Phoenium's core.
- The goal is practical compatibility, not a claim that every plugin works.

### C. Real SMP workloads (examples, not features Phoenium must implement)

Workloads typical of community SMPs such as DonutSMP-style servers. Phoenium cooperates with the mods and plugins that
provide these:
- **Shops and economies:** buy and sell, balances, chest and GUI shops, transaction persistence.
- **Kits:** starter kits, permissions, item distribution, cooldowns.
- **Homes and teleportation:** `/sethome`, `/home`, `/tpa`, `/back`, `/spawn`, warps, cross-dimension teleports.
- **Permissions:** ranks, permission checks, protected commands.
- **Claims and protection:** land ownership, protected containers, block rules, PvP restrictions.
- **Persistence through restarts:** balances, inventories, homes, claims, configuration.

### D. Repeatable compatibility certification

Test mods individually and in realistic combinations. Check:
- startup and shutdown;
- event ordering and callbacks;
- main-thread requirements and async execution;
- entity tracking and player interactions;
- chunk loading and tickets;
- teleports and dimension transitions;
- inventory transactions and duplication prevention;
- permissions and event cancellation;
- saving and persistence;
- mod-to-mod interactions;
- bridge limitations;
- Minecraft and Fabric version compatibility.

Name unsupported combinations clearly. Complex terrain-generation mods, conflicting mixins, unsupported Paper APIs, and
combinations with other performance or world-scheduling modes may need extra work. Never promise universal compatibility
that has not been verified.

### E. Keep performance with mods and plugins installed

Compare equivalent servers running the same workload:
- the baseline optimization stack without Phoenium;
- Phoenium with native Fabric mods;
- Phoenium with a validated plugin bridge;
- Phoenium with a realistic SMP mod/plugin collection.

Measure MSPT, TPS, memory, GC, responsiveness, joins, chunk loading and stability. Mods have their own costs; the target is
no unnecessary Phoenium-specific overhead and no compatibility regression. Where safe execution needs serial behaviour,
correctness comes first, and optimization works around it.

### F. Planning versus implementation

- Do not interrupt Stage 9 benchmarking, and do not start a speculative Stage 11 rewrite.
- Identify architectural compatibility risks now, in the scheduler, entity tracking, packet sending, chunk lifecycle and
  thread ownership, so that Stage 9 decisions do not make Stage 11 harder. See
  [STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md](STAGE11_COMPATIBILITY_RISK_ASSESSMENT.md).
- Start the full certification campaign once Stage 9 is sufficiently stable.

**Exit criteria:**
- a published, evidence-backed compatibility matrix (supported, limited, unsupported) for Fabric mods and any bridge;
- a repeatable certification harness;
- performance with realistic SMP workloads documented against the same stack without Phoenium.

## After Stage 11 — Minecraft 26.3 support

Port to 26.3 once Stage 9 is strong and Stage 11's major compatibility objectives are established. Do not rush the port
ahead of the optimization and compatibility goals. Preserve the stable 26.2 work, and plan version support so that 26.2
releases stay maintainable.

## Secondary / future compatibility

(Superseded in scope by Stage 11; kept for history.) VMP, Carpet, Servux, WorldEdit, LuckPerms, and BlueMap are not required production dependencies and do not block the official-stack stages. They may be evaluated after the required deployment stack under the same policy.

## Release gate

Release consideration requires actual build, unit, integration, dedicated-boot, ownership/fallback, compatibility, benchmark, known-limitation, git-revision, and reviewed HANDOFF evidence. The official required stack must meet feature-complete `PASS_*` acceptance; a successful launch alone is insufficient.
