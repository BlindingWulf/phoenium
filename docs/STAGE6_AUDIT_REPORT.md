# Stage 6 bounded audit report

**AUDIT PARTIAL — finite pass closed on 2026-09-09.** All 20 installable requested candidates and the Panda behavior reference were inventoried. Live work is finished. Missing legacy Java, Bedrock and audio acceptance, unresolved VMP terminal evidence, general terrain parity, and prerequisite-blocked combinations prevent full Stage 6 acceptance. This is not a release-ready stack and does not enter Stage 7 or later work.

The authoritative [matrix and overlap decisions](STAGE6_AUDIT_MATRIX.md), [locked manifest](STAGE6_CANDIDATE_MANIFEST.json), [Stage 7 backlog](STAGE7_AUDIT_BACKLOG.md), and [evidence index](STAGE6_EVIDENCE_INDEX.json) are the deliverables. Raw logs/worlds/profiles remain locally under `build/stage6-audit-20260909/`, excluded from the commit. The index covers 61 disposable run directories, including unsuccessful commissioning runs: 36 console-screen successes, 16 unresolved driver results, 2 frozen native-workload runs, and 7 client campaigns requiring their independent comparators. These are run counts, not compatibility passes.

## Control, budget and provenance

- Started **2026-09-09 02:58:12 UTC** (September 8, 21:58:12 America/Chicago). Two-hour boundary 04:58:12 UTC; reporting boundary 04:43:12 UTC. Live work ended and final evidence review began early, at approximately **03:58 UTC**. Final verification timestamp and observed allowance are in `final-verification.json` under the evidence root.
- Main account allowance: initial used 58%, final exposed used 67% at 04:02:52 UTC (remaining 42% to 33%). The finalize trigger was used 68%. These are account-wide rounded readings, not attributable token spending. Reserve used 7% remained unchanged at the final reading. No credits, API spend, reset, goal tracker or automation was used.
- Actual lead session configuration was **gpt-6-astra / xhigh**, not the recommended Medium. The optional helper was explicitly selected **gpt-5.6-luna / medium** for two bounded release/source extraction batches, with no live-run or production editing authority. Backend model fingerprints were not independently exposed. The helper finished; the lead owned all live campaigns and classification.
- Initial checkout was clean at `01909eafad8ae95b83f04c7d9bd52ce0facd3cb1`. Work used `stage6/bounded-compatibility-audit`. `STAGE5_PARALLEL_SCALING_FROZEN` was preserved: annotated tag object `272738ccf472148914036a016bf58a62e17217bf`, peeled commit the initial checkout.
- Production SHA-256 is `59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5`; probe SHA-256 is `DC1FA87FA00CE6EBFF8E25BEF77617BF3FA856B6BD15E0589A24D8AA5C260ED6`. All new client receipts verified these hashes. Retained XML verifies **834 tests / 88 suites / 0 failures, errors or skips**. This untouched Stage 5 evidence was reused, not claimed as a new full-suite run.
- Pinned Minecraft 26.2, Java 25, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom 1.17.20 and Gradle 9.5.1 were preserved. New console fixtures use seed 444444, 5/5 distances and loopback/offline disposable servers. Existing account-free real Java client tests retain their native fixtures and assertions. No Microsoft login or GUI automation was used. The supplied revised-roadmap filename was not found locally; the existing roadmap, matrix, Stage 5 closure and testing instructions were read.
- The frozen runtime contained only three Fabric API modules. The full selected API has 43 nested modules and was treated as a distinct control. Base/lifecycle hashes match; the command module JAR differs only in its manifest, with all other entries equal. Full-API signal/light/save/restart controls pass W1/W4; the W4 real client baseline also passes.

## Findings that change the next decision

FerriteCore is the strongest measured value candidate: three paired post-GC retained-heap screens show **5.29-5.42% lower heap**, averaging **8.066 MiB / 5.3732%**. No fourth pair was run. This is not a sustained speed, allocation-rate or RSS claim.

The frozen quick W1/W4 check passes the unchanged gameplay comparator with **5 observations and 32 completed smelts**. Native wire/lamp and saved-light checks establish a real non-furnace control: sample (89,65,8) holds light 14, then 0 after restart and source removal. Alternate Current agrees with its mod-enabled reference on this small circuit.

The unchanged real-client comparator passes for the current Java protocol through the Via chain at W4 and for Krypton at W1/W4: **132 ordered events**, equal original tracker clocks, and **186 ticks** of persisted button delay with exact before/deadline/after behavior. This does not exercise older Via translations. Krypton was run after the usable networking baseline; no network speed benefit was measured.

C2ME and ServerCore fail reviewed tick-phase boundaries; disabling ServerCore policies does not remove its injected callback. Lithium's supported chunk-access override gets through its collision-query conflict, then a real client exposes the separate null sleeping-ticker position failure, reproduced without the other logic mods. Clumps fails Phoenium entity tracking/publication at the merge in W1 and W4, while its standalone reference persists one orb and total XP 10. Chunky stalls at 36/81 with a FULL enclosure error; its standalone reference completes 81/81. ScalableLux can light/save the fresh world, but its Phoenium restart stalls while its standalone reference restarts.

Structure Layout Optimizer executes real jigsaw generation, but the trial-chamber start at chunk (8,15) changes from **338 to 273 pieces** with the parity-changing option disabled. Exact modded structure NBT matches between Phoenium and standalone Fabric; exact control NBT also matches without the mod. This identifies an upstream layout difference, not a Phoenium regression. The overall terrain screen remains inconclusive because the live control sequence itself has block-state differences; no terrain comparator was relaxed to obtain parity.

Geyser/Floodgate initializes and binds loopback UDP 19135. One W1 restart stop leaves non-daemon WebSocket threads after Minecraft completes stop; the W4 confirming sample exits cleanly. This intermittent lifecycle observation remains unresolved, without a provider-specific defect claim. Voice Chat with its supported threaded option binds loopback UDP 24455 and exits/restarts W1/W4. Neither result is a Bedrock or audio acceptance test.

VMP's client launcher exits successfully and its intermediate trace reports no missing fixture events, but the persisted trace remains PARTIAL and has no terminal `server.stop.begin/server.stop` events; the unchanged strict comparator rejects it. Native stopped health is zero. The missing terminal observation needs investigation; it is not established gameplay corruption and is not a pass.

## Reproduction and evidence

The console driver is `scripts/run_stage6_console.py`; each original argv and exact resolved mod list is retained in the run's `report.json` and `installed.json`, and every plan is copied beside the logs. Reproduce in a **new** output directory, using the hash-locked API and mod paths in the manifest. For example:

```powershell
python scripts/run_stage6_console.py --output build/stage6-recheck-new --plan scripts/stage6-plans/xp.json --workers 1 --api <locked-api-jar> --mod <locked-clumps-jar>
```

The existing client task is reused with `-Pstage5=true`, the selected worker count, unique run/server/evidence paths, 5/5 distances and packet trace. `scripts/stage6-client-mods.gradle` installs the selected full API/mods/configs after the existing disposable preparation step. It does not alter production, probe or assertions. Exact argument arrays and hash-locked selection files are retained in the evidence root. Candidate mode must not receive `stage4ReferenceProduction`; dotted PowerShell `-P...` arguments must be quoted.

```powershell
python scripts/compare_stage5_client.py --reference build/stage6-audit-20260909/runs/network-control-w4 --candidate build/stage6-audit-20260909/runs/krypton-w4 --output build/stage6-recheck-comparison.json
python scripts/summarize_stage6_audit.py
```

The second command only reads retained snapshots and regenerates the derived summary. Generation canonicalization and structure comparisons are retained in `compare-generation.py` / `generation-comparison.json` under the raw evidence root. Primary release responses, source-review commits and binary disassembly are under `research/`; unknown release-to-source mappings remain UNKNOWN. Immutable upstream SHA-512 checks and local SHA-256 identity, including nested JARs, are recorded in the manifest.

## Repairs, cleanup and incomplete scope

Commissioning failures are preserved: literal server receipt spelling, an already-empty air fill, early restart loaded-chunk polling and client launch arguments needed narrow harness/invocation corrections. The readiness correction repeats only a read-only loaded-chunk query before freezing; it cannot fabricate the requested light/redstone/XP effect. The driver also retains primary server exceptions when a closed-console OS error masks them and checks FULL retained state after stop. Earlier success logs were independently reviewed for retained state. No upstream binary, production source, existing comparator or probe assertion changed.

Two exact disposable processes required documented termination after diagnosis: ScalableLux's stalled startup and Geyser's post-stop WebSocket orphan. Thread dumps, exact owned process paths and stable world-file checks preceded termination. They remain failed/unresolved lifecycle results, not successful exits or persistence proofs. There were no active writes killed to meet the time budget. Final verification checks no Java server processes, no source edits, unchanged frozen artifacts/tag, syntax and diff hygiene. No push or release was made.

Unexecuted scope is explicit: old Java translation; real Bedrock identity/gameplay/save acceptance; real Java proximity/group audio; Bedrock voice (not supplied by the selected Java voice mod); W1 VMP/Via real-client cases; VMP terminal trace closure; larger redstone networks; XP pickup/Mending; whole-terrain parity; simultaneous Chunky/unrelated gameplay; individual enabled ServerCore policy modules; and C2ME/ScalableLux/generation plus cross-play/voice combinations. Required dependencies were added only for SLO and FastNoise. No Starlight, redundant ViaVersion override, ViaFabricPlus or Panda runtime JAR was installed.

The next finite batch and prioritized remedies are in the backlog. Stop here; do not automatically resume, create an automation, fork a mod, or start Stage 7 engineering.
