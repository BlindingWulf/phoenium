# Phoenix spawn preparation (server mod `phoenix-spawnprep`, 2026-10-17)

The owner approved this for development on 2026-10-17. It is a standalone server mod with no dependency on Phoenium or
Fabric API, so it works on every stack Phoenix runs (L5, D43, …).

## What it does
- On a **new world** it generates the missing terrain in a square around spawn **before the server logs "Done"**. It
  runs at the end of vanilla's level preparation: no player can connect yet and the watchdog has not started.
- **Existing chunks are never loaded or rewritten.** Each chunk's status is read from storage first; finished chunks are
  skipped untouched. Half-generated chunks (from a crash) are completed, and missing ones are generated.
- **No repeat on later launches.** `<world>/phoenix-spawnprep.json` records what was prepared (dimension, centre chunk,
  radius, state). A finished area is skipped.
- **Interrupted runs resume.** If the server was killed, the marker says `RUNNING`; the next start resumes and only the
  chunks still missing are generated.
  - This includes a brand-new world killed during its first preparation. Before generating, the mod saves the new world's
    level data (the same `saveEverything` the dedicated server runs right after "Done").
  - Without that save the world had no `world_gen_settings.dat` and could not load again: "Overworld settings missing".
    The laptop found this on Phone A on 2026-10-18 (RADIUS-2 b5/b6), and jar AECD0C457460 has the bug.
- **Memory.**
  - At most `maxInFlight` chunks are requested at once.
  - Tickets are released as soon as a chunk is done, and released chunks are unloaded and saved as the run goes.
  - New requests pause while the heap is over 85 % used.
- **It never stops a server from starting.** Any error is logged as `FAILED` and startup continues.

## Settings: `config/phoenix-spawnprep.properties` (written with defaults on first start)
| key | default | range | meaning |
|---|---|---|---|
| `enabled` | true | | |
| `radiusBlocks` | **256** (Phone A study: protects the join; see below) | 0-4096 | square radius around spawn; 1280 stays available |
| `maxInFlight` | 16 | 1-256 | chunks requested at once |
| `existingWorlds` | false | | also prepare worlds that existed before the mod was added (missing chunks only) |

Raising `radiusBlocks` on a prepared world expands the area: the old chunks are skipped and only the new ring is generated.
Bad values never stop the server; they fall back or are clamped, with a `config:` warning in the log.

## Contract for the management app (Stage 10)
**Log lines** (always `[PhoenixSpawnPrep]` and then `key=value` fields; field order is fixed):
```
[PhoenixSpawnPrep] START reason=<new-world|resume|expand|center-changed|radius-changed|existing-world-opt-in> dimension=minecraft:overworld centerChunk=X,Z radiusBlocks=N chunks=TOTAL maxInFlight=M
[PhoenixSpawnPrep] SAVED new world level data before preparation seconds=S.S     (new worlds only, right after START)
[PhoenixSpawnPrep] PROGRESS done=D total=T pct=P.P generated=G existing=E failed=F cps=C.C etaS=S inFlight=I heapMB=U/M     (every 2 s)
[PhoenixSpawnPrep] DONE state=<COMPLETE|PARTIAL|CANCELLED> done=D total=T generated=G existing=E failed=F seconds=S.S
[PhoenixSpawnPrep] READY state=…            (the server finishes starting now; "Done (…)!" follows)
[PhoenixSpawnPrep] SKIP reason=<already-prepared|cancelled-by-user|existing-world|disabled> radiusBlocks=N
[PhoenixSpawnPrep] FAILED: …                (error; the server starts anyway)
```
**Status file:** `<world>/phoenix-spawnprep.json`, rewritten atomically every 5 s and at the end. Fields: `state`
(`RUNNING`, `PARTIAL`, `COMPLETE`, `CANCELLED`), `done`, `total`, `generated`, `existing`, `failed`, `radiusBlocks`,
`updatedEpochMs`, and more.

**Cancel:** create an empty file `phoenix-spawnprep.cancel` in the **server folder** (the game directory). Within one wait
cycle the run ends with `DONE state=CANCELLED`, the file is removed, and the server finishes starting. A cancelled area is
not retried on later launches unless the radius is changed. Stopping the server during preparation is also safe: the run
ends as resumable and continues on the next start.

**Suggested UI.** Show "Preparing the area around spawn: P % (about S s left)" from the PROGRESS lines, with a Cancel button
that writes the cancel file. Keep the "starting" state until "Done (…)!" as today. No long silent wait.

## Evidence (desktop, offline, through testsched)
- Unit tests: 19/19 (plan order, config, decision table, runner against a fake chunk system). RED `spawnprep-red-1`,
  GREEN `spawnprep-green-2`.
- Jar `phoenix-spawnprep-AECD0C457460.jar` (SHA-256 `AECD0C457460FFF341866FF4512CC4E7B40FABECE7249A1DEF2F0B9E5CE24A46`,
  27,778 bytes) in `.audit/phoenix-spawnprep-20261017/`.
- `spawnprep-boot-l5-1` (L5 stack: C2ME, Lithium, ScalableLux, ServerCore, Geyser, Floodgate, voice chat). A fresh world was
  prepared in 29.0 s for 1089 chunks (about 37 chunks/s), heap ≤ 640 MB of 1536, READY logged before "Done", PASS.
- Lifecycle check `spawnprep_check.py`, six phases on one world: fresh → relaunch skip → expand 512 → cancel 768 →
  cancelled stays skipped → JVM killed mid-run → resume. "Untouched" is checked byte for byte against the region file
  headers (location entry and timestamp of all 1089 chunks from phase A).
  - `spawnprep-check-D43-1` (D43 engine stack): **PASS, 7/7 phases**. All 1089 phase-A chunks were on disk after the first
    stop and stayed byte-identical through the relaunch, the expansion to 512, the cancel at 768, the skip after cancel and
    the kill-and-resume at 640.
  - `spawnprep-check-L5-1`: every behaviour check passed. Its "untouched" checks were void because the checker read
    `<world>/region`, which 26.2 no longer uses (overworld regions are in `dimensions/minecraft/overworld/region`).
  - `spawnprep-check-L5-2` (L5 stack, fixed checker): **PASS, 7/7 phases**, the same result as D43.
  - On both stacks, the resume after the kill finished with `generated=1792 existing=4769 failed=0` in about 50 s, and
    only the missing chunks were generated.

## Phone A radius study (laptop, LAPTOP-PREGEN-RADIUS-1, 2026-10-17; reviewed on main)
- **Cost.** A Chunky r256 world takes 3 min 02 s to build on the Moto G 2025 and is 18.1 MB. r1280 takes 24 min 37 s and is
  258.5 MB.
- **Stability.** D43 still16 with the screen off, 5 interleaved runs: all valid, no crash, and no low-memory kill of the
  server.
  - Watchdog margin, r256: 10.9 and 12.5 s. With `-XX:-UseDynamicNumberOfCompilerThreads`: 15.6 and 15.6 s.
  - Watchdog margin, r1280: 12.7 and 17.2 s. With the flag: 16.6 and 19.1 s.
- **The gap.** r256 is 2-3 s lower in every pairing. That is within r1280's own spread at n=2, so it is not established.
  - It is not generation: no chunk was generated during any run.
  - The join at view-distance 6 / simulation-distance 4 needs about ±7 chunks, well inside r256 (±16).
- **Decision.** 256 is the initial default. Smaller radii are untested. Settling the 2-3 s would need about 4-6
  interleaved runs per radius.
- The flag still adds +3.9 s of margin at r256 and stays experimental.
