# Stage 7 Sol Current Work

Updated: 2026-09-10

## Repository state

- Branch: `stage7/scalablelux-persisted-light-repair`
- Current HEAD: `3a6c6ed` (`docs: close Clumps Stage 7 checkpoint`)
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`
- Production JAR SHA-256: `0B80154F4AA99E0D833C5DC119A39A2087AF6CDA3B2AC286DAD0477E3F5867C4`
- Last recorded full deterministic suite: `845` tests across `90` suites, zero failures/errors/skips, after the Clumps lifecycle repair.
- Stage status: `STAGE 7 IN PROGRESS`; Stage 6 remains `AUDIT PARTIAL`; Stage 8+ are `NOT STARTED`.

## Completed Stage 7 scoped repairs preserved at current HEAD

- Chunky asynchronous FULL/ABSENT-input authority repair: scoped repair PASS; broader Chunky compatibility remains PARTIAL. Commit `f12fd22`.
- C2ME reviewed tick-phase callback integration: PASS; deep C2ME runtime integration remains BLOCKED behind the safety gate. Commit `7d2029b`.
- ScalableLux persisted-light reload adapter: scoped PASS. Commit `28a7aa8`.
- Lithium chunk/ticker adapter: scoped adapter PASS. Implementation `c6dd6a2`; handoff `5df8e8d`.
- ServerCore reviewed entity-activation callback adapter: implementation commit `72626a9`; scoped preservation-profile checkpoint PASS. Overall ServerCore compatibility remains PARTIAL because behavior-changing policy modules are outside this scoped acceptance.
- Clumps merge/removal lifecycle: SCOPED PASS. Implementation `b394978`; checkpoint docs `3a6c6ed`. Native removed-entity tick-list boundary restored before owner-published tracking facts are required. Post-commit locked Clumps W1/W4 fresh+restart, real-client pickup/Mending/removal, full suite, and ServerCore W4 overlap regression pass against production SHA `0B80154F...867C4`.

## Current provider

Geyser/Floodgate lifecycle isolation. VMP investigation is closed as an exact architecture blocker for this Sol High campaign.

### Exact first unresolved item

Stage 6 `geyser-floodgate-w1` completed Minecraft save/stop but retained late non-daemon WebSocket threads on restart; the W4 confirmation exited normally. Attribution to Geyser alone, Floodgate, first-run/update timing, or the combined stack is still unknown.

### Shortest reproduction / verification

Use the frozen Stage 6 Geyser/Floodgate artifacts and lifecycle evidence. Isolate Geyser alone when supported, Geyser+Floodgate, and a Fabric-only provider control using bounded repeated start/stop runs. Before every live run reread `docs/AGENT_MINECRAFT_TESTING.md` and recheck Java/Minecraft processes.

## Tests/evidence already recorded passing

- ServerCore focused phase-boundary regression: PASS, including moved/changed negative cases.
- ServerCore preservation-profile W1 console fresh/restart: PASS.
- ServerCore preservation-profile W4 console fresh/restart: PASS.
- ServerCore real-client W1 strict comparison: PASS, 132 ordered events.
- ServerCore real-client W4 strict comparison: PASS, 132 ordered events.
- Native save/unpack/deadline checks: PASS in the accepted client cases.
- Health counters recorded zero for duplicate/bypass/fallback/ownership/unleased/barrier failures.
- Clumps focused removed-alias regression + `FullEntityFactsTest`: PASS.
- Clumps locked post-commit W1/W4 fresh/restart: PASS; reports identify source head `b394978` and production SHA `0B80154F...867C4`; one live orb, total XP 10, `clumpedMap {3:1,7:1}` persists across restart.
- Clumps real-client W1: PASS provider probe; pickup removed the merged orb, Mending reduced pickaxe damage `100 -> 80`, explicit second-orb removal left zero live orbs, native restart receipts pass, server exit code 0.
- Full deterministic suite after repair: `845` tests / `90` suites / zero failures/errors/skips.
- ServerCore preservation-profile W4 fresh/restart overlap regression: PASS against the repaired production artifact.
- Production artifact SHA-256 on disk: `0B80154F4AA99E0D833C5DC119A39A2087AF6CDA3B2AC286DAD0477E3F5867C4`.
- VMP investigation: historical stop-trace ambiguity resolved to a real disconnect lifecycle failure. The real `seenBy` listener survives provider removal; VMP's optimized tracker is a full `ChunkMap.tick()` overwrite that Phoenium's regional tracker cancels. Console W1/W4 alone are insufficient. No VMP production repair was accepted; see `STAGE7_VMP_XHIGH_HANDOFF.md`.

## Known architecture boundaries

- Preserve current-owner admission, generation/incarnation fences, retained-input semantics, readiness/publication ordering, save acknowledgement, and lifecycle settlement.
- No blocking owner-to-owner waits, world/gameplay global lock, thread-identity authority, second ownership system, fabricated state, swallowed failed completion, replayed committed effects, or global serialization of official-stack hot paths.
- Keep the final C2ME safety gate. Deep C2ME generation/FULL/save/unload integration is not a Sol High repair target unless an existing Phoenium primitive clearly represents the lifecycle.
- Scoped provider repairs do not imply whole-provider PASS. ServerCore behavior-changing modules remain separately unaccepted.
- Required provider functionality must remain enabled for any claimed accepted path; preservation-profile disabled modules are exclusions from the scoped ServerCore result, not proof of their compatibility.
- VMP optimized nearby-entity tracking is BLOCKED/PARTIAL for this campaign. A future adapter must integrate VMP's own staging/area-map/player-index lifecycle; private-index patching, cross-owner waits, or broad tracker closure are rejected.

## Running processes

- No `java`/`javaw` process was returned by the current recovery inspection. Recheck immediately before any installed run.

## Next 3 actions

1. Inspect the frozen Geyser/Floodgate artifacts, lifecycle plans, W1/W4 logs and retained thread/process evidence; identify the shortest isolated provider/control runs.
2. Reproduce the late WebSocket lifetime with Geyser alone and Geyser+Floodgate only as needed, checking whether new non-daemon threads appear after shutdown begins.
3. If a narrow supported lifecycle/configuration correction is proved, implement and verify it; otherwise record exact upstream/provider attribution and move to the next independent Stage 7 item.
