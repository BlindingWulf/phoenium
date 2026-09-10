# Stage 7 FastNoise fixed-boundary investigation

Updated: 2026-09-10

## Result

FastNoise remains **PARTIAL / provider parity attribution BLOCKED**. The improved comparator still finds vanilla/control block-state nondeterminism before FastNoise is introduced, so Stage 7 does not assign either a parity PASS or a provider FAIL.

No FastNoise candidate run was performed after the control gate failed. No Phoenium production code changed.

## Why the Stage 6 terrain comparison was insufficient

The Stage 6 `native-generation.json` plan generated the target area under `tick freeze`, then created a redstone fixture, explicitly stepped 20 ticks, and only later saved the world snapshot. Its comparator hashed every block and biome section of the 64 core FULL chunks. That mixed world-generation output with later live-world state and also compared feature-stage blocks that are nondeterministic in the retained controls.

The historical controls demonstrate the problem independently of FastNoise:

- blocks: only `49 / 64` core chunks matched between W1 and W4 controls;
- biomes: `64 / 64` matched;
- heightmaps: `61 / 64` matched;
- structures: `64 / 64` matched.

## Stage 7 fixed boundary

The new plan is `scripts/stage7-plans/fastnoise-parity.json` and the fail-closed comparator is `scripts/compare_stage7_fastnoise_parity.py`.

The plan preserves seed `444444` and the same core chunks `x=6..13`, `z=-4..3`, but removes the unrelated live fixture and all explicit tick steps. It freezes the server clock, force-loads exactly the 8x8 target core, polls all four target corners as loaded, performs no fixture block writes, flushes/saves while still frozen, and requires every one of the 64 requested native chunk records to be `minecraft:full` with saved sections/light.

An attempted `gamerule randomTickSpeed 0` command was rejected by the Minecraft 26.2 command surface before target generation. The failed disposable run was not used as evidence. The redundant command was removed because `tick freeze` already prevents ordinary world ticks throughout the measured interval.

## Fresh control evidence

Accepted fresh controls:

- `run/stage7/fastnoise-fixed-control-a2-w1/`
- `run/stage7/fastnoise-fixed-control-b-w1/`
- comparison: `run/stage7/fastnoise-fixed-controls-comparison.json`
- detailed difference inventory: `run/stage7/fastnoise-fixed-control-diff-details.json`

Both server runs passed their console lifecycle and exact 64-FULL-record persistence checks using the current production JAR SHA-256 `D095C007C1B81698440F3BF0145E38A23DC6078F7033D713324F61F15F2C3CC9`.

Even at this stricter boundary, the two no-FastNoise controls compare as:

- block output: `58 / 64` chunks identical;
- biome output: `64 / 64` identical;
- heightmaps: `61 / 64` identical;
- structures: `64 / 64` identical.

There are only 47 individual block differences across the six differing chunks:

- diorite -> granite: 24;
- andesite -> granite: 9;
- diorite -> andesite: 4;
- copper ore -> deepslate copper ore: 3;
- short grass -> air: 3;
- air -> short grass: 2;
- granite -> andesite: 2.

The only changed heightmap field is `WORLD_SURFACE`, in chunks `(10,2)`, `(13,1)`, and `(13,2)`. Those chunks include short-grass placement differences, so the heightmap variation is consistent with changed final surface feature state.

The largest block cluster is underground stone-variant feature output in chunk `(9,-3)`. Other differences are similarly granite/andesite/diorite, copper/deepslate-copper, or surface grass. Biome storage and retained structure starts remain deterministic across all 64 requested chunks.

## Interpretation

FastNoise documents vanilla world-generation parity as its intent and also warns about vanilla worldgen nondeterminism. The current evidence demonstrates that problem in the exact comparison surface Stage 6 used: FULL chunk block storage is not deterministic enough here to act as a strict provider oracle, even with the clock frozen and no post-generation tick step.

This does **not** show a FastNoise defect. It also does not establish FastNoise block/noise/surface parity. The control gate fails before provider attribution is permitted.

The next technically valid comparator would need an earlier generation-stage observation, before nondeterministic placed-feature output is merged into the FULL chunk, or a provider/upstream harness that directly compares deterministic biome/noise/surface calculations. That requires a new instrumentation boundary rather than another retry of the same FULL-chunk experiment.

Per the Stage 7 anti-loop rule, this comparator campaign stops here. FastNoise remains a candidate with scoped runtime evidence and no strict terrain-parity claim.

Stage 7 remains `IN PROGRESS`.
