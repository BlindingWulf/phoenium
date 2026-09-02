# Handoff

## Resume point

Stage 0 is complete. Resume with **Stage 1 isolated core model** in [ROADMAP.md](ROADMAP.md). Do not begin a live Minecraft tick hook, mixin, C2ME adapter, or parallel worker mode before the Stage 1 and Stage 2 gates that apply to it.

## Locked decisions

- Treat current Folia 26.2 semantics as primary; archived Ruthenium is a defect and regression reference.
- Use Minecraft 26.2, Java 25, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20, Gradle 9.5.1, official Mojang mappings, and a server-only mod id phoenium.
- Use a serial-first Phoenium scheduler. Parallel workers come only after deterministic core and live serial integration evidence.
- Model owner epoch, topology generation, and region lease separately.
- Never use a broad per-world gameplay lock, peer-region lock ordering, fake chunks/block values, unowned shutdown task execution, or a silent vanilla fallback.
- Choose GPL-3.0-only before any source-derived implementation. No third-party implementation entered this repository in Stage 0.

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
| C2ME | Required target, current contract not audited at integration level. | Isolated completion-routing adapter and compatibility proof. |
| All tick categories | No Phoenium category is implemented. | Stage progression through entity, chunks, scheduled ticks, block events, redstone, trackers, save. |
| Parallelism | Not enabled or measured. | Deterministic core then serial live oracle before multiple workers. |
| Target mods | No compatibility runs completed. | Incremental matrix execution after core gates. |
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

The Stage 0 checkpoint commit and tag are created after the documentation validation immediately following this handoff record. The exact resulting HEAD and clean-status evidence are reported with the checkpoint delivery so this file does not contain a self-referential stale hash.

