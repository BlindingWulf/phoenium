# Handoff

## Resume point

Stage 0 remains accepted at **PHOENIUM_P0_SOURCE_TRUTH_LOCKED**. Its compatibility and fork-policy amendment is documentation-only and does not alter that historical checkpoint. Resume with **Stage 1 isolated core model** in [ROADMAP.md](ROADMAP.md). Do not begin a live Minecraft tick hook, mixin, C2ME adapter, compatibility fork, or parallel worker mode before the applicable core and live-integration gates.

## Locked decisions

- Treat current Folia 26.2 semantics as primary; archived Ruthenium is a defect and regression reference.
- Use Minecraft 26.2, Java 25, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20, Gradle 9.5.1, official Mojang mappings, and a server-only mod id phoenium.
- Use a serial-first Phoenium scheduler. Parallel workers come only after deterministic core and live serial integration evidence.
- Model owner epoch, topology generation, and region lease separately.
- Never use a broad per-world gameplay lock, peer-region lock ordering, fake chunks/block values, unowned shutdown task execution, or a silent vanilla fallback.
- Choose GPL-3.0-only before any source-derived implementation. No third-party implementation entered this repository in Stage 0.
- The official required deployment stack, full-PASS production gate, compatibility hierarchy, fork prerequisites, fork phases, and generic-layer limits are locked in [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md). Every target remains `NOT_TESTED`; boot alone is never proof.

## First work session checklist

1. Read [ARCHITECTURE.md](ARCHITECTURE.md), [THREADING_INVARIANTS.md](THREADING_INVARIANTS.md), and [SOURCE_PROVENANCE.md](SOURCE_PROVENANCE.md).
2. Start from a current Fabric 26.2 template; do not resurrect Ruthenium's old Gradle, Yarn, client, or mixin setup.
3. Add the full GPL-3.0-only license before any source-derived file.
4. Create core-only tests for region lifecycle, lease pairing, stale-route handling, transition mailboxes, transfer records, save cuts, and hard failure barrier.
5. Record actual Build, Unit, Integration, Dedicated Boot, violations/fallbacks, compatibility, changed files, risks, architecture changes, blockers, git status, and commit in the next stage report.

## Known risks and blockers

| Area | Current position | Required resolution |
| --- | --- | --- |
| Live server interception | No target approved or implemented. | Bytecode review and exactly-once dedicated-server test in Stage 2. |
| Required deployment stack | No target has execution evidence; C2ME is the highest-priority external integration and Krypton is tested last in isolation. | Post-core, feature-complete required-stack audit under the compatibility hierarchy; use adapter/patch/fork only when proven necessary. |
| All tick categories | No Phoenium category is implemented. | Stage progression through entity, chunks, scheduled ticks, block events, redstone, trackers, save. |
| Parallelism | Not enabled or measured. | Deterministic core then serial live oracle before multiple workers. |
| Secondary/future mods | VMP, Carpet, Servux, WorldEdit, LuckPerms, and BlueMap are not production dependencies. | Consider only after official required-stack gates. |
| Upstream licensing | Ruthenium metadata conflicts with root GPL license. | Avoid direct reuse unless upstream clarifies; retain provenance review. |

## Stage 0 report status

| Required item | Stage 0 status |
| --- | --- |
| Build | Not run; documentation-only stage. |
| Unit tests | Not run; no Phoenium tests exist. |
| Integration | Not run. |
| Dedicated boot | Not run. |
| Violations/fallbacks | No Phoenium runtime. Ruthenium fallback is captured as a prohibited regression. |
| Compatibility tested | None. |
| Changed files | Phoenium documentation and repository metadata only. |
| Architecture | Locked by Stage 0 documents. |
| Next stage | Stage 1 isolated core model. |

## Git record

The original Stage 0 checkpoint remains **PHOENIUM_P0_SOURCE_TRUTH_LOCKED**. The compatibility/fork-policy amendment is committed on top of it as `docs: lock required compatibility and fork policy`. The amendment commit is intentionally identified by the repository's current `HEAD` at delivery rather than embedding its own hash in this tracked file, which would become stale or self-referential. Its SHA and clean-status evidence are reported with the amendment delivery.
