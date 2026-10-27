# Reference harness (not portable yet)

These are the **exact desktop test tools** that produced Phoenium's RC1 / beta evidence, published as-is so they can be
read, reused and ported. They were written for one Windows machine:

- paths such as `C:\Users\...\Project PHOENIUM`, the JDK location and a local server template are **hard-coded**;
- servers were assembled from a local template (Mojang server jar, libraries, pinned mod jars). Those binaries are
  **not** in this repository and must never be committed: obtain them from official sources (`docs/STACK_LOCK.json`);
- jobs were run through a local scheduler/guard (`testsched` / `x1guard`, not published). Run them sequentially instead.

Porting this into a portable `testkit/` (bootstrap from official downloads with SHA-256 pins, config-driven paths,
Linux + Windows) is **Mythos housekeeping task #2** in `docs/MYTHOS_AUDIT_BRIEF.md`.

| Folder | What it is | Scenarios |
|---|---|---|
| `stage9-runner/` | Stage 9 load / performance runner (`run_stage9.py`, `scenarios.py`, `lib/`). Records JFR, GC log, per-tick telemetry (`telemetry-mod/`, source included) and per-second CPU; gives CORRECTNESS / PERFORMANCE / HARNESS verdicts (`lib/analyze.py`). | idle, connect, region, groups, cluster, explore, combat, mixed, mounted-portal (+ `-v2`); concurrent players (16-50 bots: Java bots via ViaProxy + mineflayer, synthetic Bedrock bots); combat; chunk load/unload; memory/GC; no-add-on performance (L5-control vs Phoenium) |
| `stage9-runner/` **LOW-RAM preset** | The phone-heap desktop envelope: same runner with `--heap 1536M`, `--heap 1024M` (Phoenium) vs L5-control at `900M`, scenarios `groups-v2` and `explore-v2`, tier 16, warmup 180 s, measure 420 s. `explore-v2` at low heap is the workload that exposed the MEM-6 out-of-memory bug (fixed in this beta). | memory pressure / GC / OOM |
| `gates/` | RC1 correctness gates (engine-mode ports of the Stage 7/8 gates) on `stage8-lib/s8lib.py`: `determinism9e8b` (160 mobs, fixed UUIDs, `phoenium test reseed`, `tick step 200`, full NBT diff across 1/2/5 workers), `transitions9e8r10` (portals, pearls, dimension hops, End), `protections9e8` (projectile/pearl caps, off-thread protection counters), `wgeq9m` (save/restart and unload/reload world equality + region scan), `memcycle9m` (memory cycle, 35 Java + 15 Bedrock bots), `a3live9e8` + `validate_a3_9e8` (A3 mob decisions), `stepbsw9m` (startup/lifecycle), `unloadctl9e16`, `stackguard9e16`, `rc2*` (packet order, teleports/deaths, container item conservation). `rc1_gate_chain_reference.sh` is the exact accepted RC1 invocation list. | deterministic ordering / race detection, portals, thread affinity, save/restart, item conservation, deaths |
| `world-checkers/` | Standard-library tools: `regions.py` (counts persisted chunks in `.mca` files), `worldcmp.py` (per-chunk hashes of block states / biomes / heightmaps to compare two worlds), `analyze.py` (TPS/MSPT/GC from `ticks.csv` + `gc.log`). | region-file validation |

Not included yet: the Node bot adapters (`java_bots.js`, `bedrock_bots.js`, `lib/common.js`, `package.json`) will be
added in a follow-up commit. Requirements when porting: Python 3.12 (+ `psutil`), Node.js 24 with `mineflayer` and
`bedrock-protocol`, ViaProxy 3.4.14 (GitHub release, verify its SHA-256), JDK 25.

All test servers must run offline and locally: `online-mode=false`, `enforce-secure-profile=false`,
`server-ip=127.0.0.1`, and all three `-Dminecraft.api.{session,services,profiles}.host=http://127.0.0.1:9` flags. Some
launchers here (`lib/server.py`, `s8lib.py`) still pass only two of the three host flags: fix that when porting, or
26.2 will contact the real Mojang hosts.
