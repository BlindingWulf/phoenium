# Stage 0 audit: source truth lock

## Outcome

**Checkpoint achieved: PHOENIUM_P0_SOURCE_TRUTH_LOCKED**

Stage 0 is complete as a documentation-only forensic audit. Phoenium implementation has not started. No supplied reference directory was modified, no runtime source was copied, no Phoenium build was created, and no benchmark or gameplay result is claimed.

## Scope and hierarchy

The audit compared the supplied read-only references with current upstream sources and inspected the official 26.2 artifact and Fabric metadata. Current Folia is primary for regional semantics; archived Ruthenium is secondary evidence; current PandaAntiLag is behavioral reference only.

| Reference | Local integrity result | Upstream result | Conclusion |
| --- | --- | --- | --- |
| Folia supplied tree | 50 files byte-identical and 3 batch files newline-only different | ver/26.2.x at 14b7fee5c866fca9a40ede4a58998fb928140f65 | Current supplied Folia matches the audited upstream source. |
| Ruthenium supplied tree | 5,081 files byte-identical and 5 batch files newline-only different | main at aebbbad87498c0718b1287a226789212e6103bcf | Current archived upstream snapshot, suitable for defect audit only. |
| PandaAntiLag supplied tree | 27 files byte-identical and one batch file newline-only different | Matches pre-26.2 b74156f661250812ccb108ee6354a2eac3e2c7fa | Supplied copy is older than current main b8de06e7e04159b6753c3d94d7d8362a68e80002. |

The original reference inventory contained 5,167 files and 76,130,499 bytes. A final hash comparison is recorded before this stage closes.

## Current toolchain conclusion

Minecraft 26.2 requires Java 25. The selected initial toolchain is Java 25, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20, and Gradle 9.5.1. Fabric 26.2 uses official Mojang mappings and the modern net.fabricmc.fabric-loom plugin id. See [BUILD_ENVIRONMENT.md](BUILD_ENVIRONMENT.md).

## Ruthenium findings

The audit inspected 77 main Java files (14,629 lines), 13 test classes, and 29 test methods. Its tests are predominantly in-process/reflection tests, including a test-source ServerWorld stub; they do not demonstrate a dedicated Fabric server under real parallel execution. They were inspected, not run.

| Severity | Finding | Evidence-backed implication |
| --- | --- | --- |
| Blocker | Queued region task execution does not acquire the scheduler's ticking lease. | A topology operation can treat a region as READY while its mutable task is running. |
| Blocker | Scheduler failure / halted / no-active-region paths fall back to vanilla ticking by default. Strict assertions and fallback logging default off. | Directly violates Phoenium's no-silent-fallback requirement. |
| High | Per-world reader/writer lock surrounds every regional tick. | It is a broad shared gameplay coordination mechanism and must not be ported. |
| High | Queue and block-event transfers take peer locks without a total order. | Reciprocal or future direct calls can deadlock. |
| High | Raw pending chunk tasks can run on shutdown thread. | Work lacks region, epoch, ordering, and ownership proof. |
| High | Teleport/player transfer moves or captures live state without a full source/destination protocol; named production call sites are absent. | Requires a new transfer state machine, including passenger groups and connection routing. |
| High | Redstone/comparator/piston and neighbor fallbacks use live foreign reads, fake values, or cancellation. | No fake state or silent cancellation is acceptable. |
| High | Chunk fallback can return EmptyChunk. | It fabricates a semantic boundary instead of routing/proving access. |
| Medium | Entity scanning and tracking retain global-world patterns. | Move to owner-local registries before parallel tick integration. |
| Medium | Some claimed safety coverage is placeholder-only. | Empty ComparatorBlockMixin and placeholder ImposterProtoChunkMixin do not supply a safety guarantee. |
| Medium | Blanket synchronized mixins protect vanilla collections without ownership proof. | Defer all such integration until an adapter contract exists. |
| Medium | Documentation/todo claims conflict with unfinished transfers, networking, migration, redstone, and integration testing. | Claims are not treated as evidence. |

The local Ruthenium historical log also contains repeated fallback messages for dimensions. This is historical reference evidence, not a Phoenium test result.

## Folia and PandaAntiLag findings

Current Folia establishes the usable regional model: buffer closure, transient/ready/ticking/dead region states, non-expanding ticking regions, explicit task leases, owner context, region data migration, and distinct global services. It also has post-Ruthenium fixes for packet ownership, teleport acceptance, passenger reconciliation, direct clock reads, redstone, and game events. See [STAGE0_FOLIA_DELTA.md](STAGE0_FOLIA_DELTA.md).

PandaAntiLag current main is 26.2-ready but its strategy is not a concurrency design: pseudo-region cache keys omit dimension, scans are global, counters/maps are not regional ownership structures, and skipped entity ticking uses selective compensation. Its projectile limiter also has an inclusive removal-loop risk. Phoenium will treat it as later behavioral intent only.

## Stage 0 acceptance

| Gate | Status | Evidence |
| --- | --- | --- |
| Current Fabric 26.2 build environment identified | PASS | [BUILD_ENVIRONMENT.md](BUILD_ENVIRONMENT.md) |
| Source hierarchy and local/upstream divergence documented | PASS | [SOURCE_PROVENANCE.md](SOURCE_PROVENANCE.md) |
| License strategy determined before reuse | PASS | GPL-3.0-only selection; no code copied |
| Ruthenium unsafe patterns classified | PASS | [STAGE0_SYNCHRONIZATION.md](STAGE0_SYNCHRONIZATION.md) and [STAGE0_COMPONENT_MAP.md](STAGE0_COMPONENT_MAP.md) |
| Current Folia delta identified | PASS | [STAGE0_FOLIA_DELTA.md](STAGE0_FOLIA_DELTA.md) |
| Architecture and test strategy locked | PASS | [ARCHITECTURE.md](ARCHITECTURE.md), [THREADING_INVARIANTS.md](THREADING_INVARIANTS.md), [TEST_MATRIX.md](TEST_MATRIX.md) |
| Runtime build / unit / integration / dedicated boot | NOT RUN | Intentionally out of scope: Stage 0 contains no build or runtime code. |

## Stage 0 report

| Required report item | Status |
| --- | --- |
| Build | Not run; no Phoenium build exists. |
| Unit tests | Not run; no Phoenium tests exist. Reference tests inspected only. |
| Integration tests | Not run. |
| Dedicated-server boot | Not run. |
| Violations/fallbacks | No Phoenium runtime. Ruthenium fallback paths are documented as prohibited regressions. |
| Known risks | Current Minecraft integration points, C2ME contract, all tick categories, saving, and mod compatibility remain unimplemented. |
| Compatibility tested | None; matrix is planning-only. |
| Changed files | Documentation and repository metadata only; no reference files. |
| Architecture | Locked in Architecture and Threading Invariants. |
| Blockers | No blocker to Stage 1 core-only work. Do not start server tick/mixin integration until Stage 2 gate. |
| Next stage | Stage 1: isolated core model and deterministic tests. |
| HANDOFF | Complete and required before any implementation. |

