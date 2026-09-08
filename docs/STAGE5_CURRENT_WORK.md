# Stage 5 execution and acceptance

Status: **STAGE5_PARALLEL_SCALING_FROZEN**. Branch `stage5/bounded-parallelism`.
Final self-contained report: [STAGE5_CLOSURE_REPORT.md](STAGE5_CLOSURE_REPORT.md).
Stage 6 and compatibility work have not started.

## Frozen identities

Validated code/harness HEAD: `8cc210ff2db55835ca21170e3399dc3ac22dd643`.
Freeze tag: `STAGE5_PARALLEL_SCALING_FROZEN` (final documentation commit).
Production: `run/stage5/final-artifacts/phoenium-0.2.0-stage2.jar`.
SHA-256: `59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5`.
Manifest: `run/stage5/final-artifacts/manifest.json`, including exact freeze commit/tag,
source, probe and evidence hashes. Last production change: `5aeb2fe`.

Stage 4 source remains `1a82b701fce53395481f251347ca9a4deffc12d7` and its JAR remains
`43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E`.
Stage 3 source/JAR and both stages' original evidence/saved fixtures are preserved.

## Accepted scope and evidence

- Complete Java suite: 834 tests / 88 suites; zero failures/errors/skips.
- Comparator negatives: 17 methods; actual-JAR identity guards: 18/18 PASS.
- Final original FULL: `run/full-authoritative/live-1788908474731`; fresh/reload
  native/client/persistence PASS; natural original-body peak 4 fresh / 2 reload.
- Exact frozen + candidate 1/2/4 clients: `run/stage5/controls-client5-e` (132 events)
  and `controls-client8-c` (138); five comparisons each, native saved delay 186.
- Normal tracker/ID clients: `unrestricted-client-4-5-final` and `-4-8-final` PASS.
- `controls-native-final`: 800 ticks, 128 furnaces, 12 replacements, 24 stale queued
  native reads, exact callback/state continuity and MCA; all five comparisons PASS.
- `adverse-final-2-b`, `adverse-final-4` and original Stage 4 adverse regression PASS:
  initiating failure preserved, peer joined, save settled, 15/15 closes, no watchdog.
- `benchmark-final`: 24 runs, 30 exact comparisons PASS; distributed elapsed speedup
  0.998× (2 workers), 1.119× (4); concentrated 0.976× / 0.782×. Full variance,
  allocation/GC/memory and queue metrics are in the closure report.
- Final packaging, health and retained-state receipt: `final-verification.json`, `final-health.json`
  and `packaging-final.json`: PASS, 50 positive lifecycles with zero forbidden residue.

Workers are fixed/configurable 1–8, default 1; installed evidence uses 1/2/4.
Per-owner callback cap 1–256, default 64. New capture bound W*C<=2048, task queue<=W,
one batch<=W, claims<=W; optional completed-body trace 4096, benchmark trace 0.
Only audited original native intermediate furnace-family cooking and hopper
cooldown/disabled branches are admitted locally. Shared effects, completion/fuel,
transfers, other block entities, entity AI, tracking and native shared phases remain
ordered. Existing supported-scope exclusions and explicit topology domain remain.

## Execution policy for the next stage

Preserve the selected lead configuration; Astra owns engineering. Optional substantial
implementation worker: explicitly selected Astra Medium, isolated edits. At most one
active helper; no recursive delegation. Luna at lowest adequate effort handles bounded
read-only evidence/commands, never repairs production/harness/comparators or weakens
gates. Optional Sol High review replaces that helper. One owner controls integrated
builds and sequential live campaigns. If selection is unavailable, proceed alone.
No extra credits, usage resets, account changes, paid API calls or external publication.

Initial telemetry: Codex 31% used/69% remaining; final 57% used/43% remaining; reserve 7% used.
Per-worker attribution unavailable. Tool-selected helpers were Luna Low and Sol High;
all are finished. Native/source provenance, failed attempts, commands, all final
metrics and Stage 6 constraints are retained in the closure report. Stop here;
the freeze permits preparation for the Stage 6 audit, not compatibility claims.
