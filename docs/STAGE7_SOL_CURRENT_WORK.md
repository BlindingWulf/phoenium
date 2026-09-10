# Stage 7 Sol Current Work

Updated: 2026-09-10

## Repository state

- Branch: `stage7/scalablelux-persisted-light-repair`
- Current HEAD: `b50312b` (`docs: refresh Stage 7 Sol handoff`)
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`
- Production JAR SHA-256: `D095C007C1B81698440F3BF0145E38A23DC6078F7033D713324F61F15F2C3CC9`
- Last recorded full deterministic suite: `847` tests across `91` suites, zero failures/errors/skips, after the Geyser/Floodgate lifecycle repair.
- Stage status: `STAGE 7 IN PROGRESS`; Stage 6 remains `AUDIT PARTIAL`; Stage 8+ are `NOT STARTED`.

## Completed Stage 7 scoped repairs preserved at current HEAD

- Chunky asynchronous FULL/ABSENT-input authority repair: scoped repair PASS; broader Chunky compatibility remains PARTIAL. Commit `f12fd22`.
- C2ME reviewed tick-phase callback integration: PASS; deep C2ME runtime integration remains BLOCKED behind the safety gate. Commit `7d2029b`.
- ScalableLux persisted-light reload adapter: scoped PASS. Commit `28a7aa8`.
- Lithium chunk/ticker adapter: scoped adapter PASS. Implementation `c6dd6a2`; handoff `5df8e8d`.
- ServerCore reviewed entity-activation callback adapter: implementation commit `72626a9`; scoped preservation-profile checkpoint PASS. Overall ServerCore compatibility remains PARTIAL because behavior-changing policy modules are outside this scoped acceptance.
- Clumps merge/removal lifecycle: SCOPED PASS. Implementation `b394978`; checkpoint docs `3a6c6ed`. Native removed-entity tick-list boundary restored before owner-published tracking facts are required. Post-commit locked Clumps W1/W4 fresh+restart, real-client pickup/Mending/removal, full suite, and ServerCore W4 overlap regression pass against production SHA `0B80154F...867C4`.
- Geyser/Floodgate stale skin-uploader reconnect: scoped lifecycle PASS_ADAPTER. A delayed Geyser reconnect queued before shutdown is now guarded at execution by the provider's own `closed` state. Locked combined W1/W4 fresh+restart and Geyser-only W1 control pass; Bedrock feature acceptance remains PARTIAL.
- Structure Layout Optimizer strict seed-parity policy: provider-attributed upstream blocker. Locked SLO with its parity-breaking option disabled still changes the trial chamber from 338 to 273 pieces identically with and without Phoenium. Excluded from the strict-parity profile pending upstream fix or explicit changed-layout policy.

## Current provider

FastNoise comparator-boundary repair. SLO policy is closed without a Phoenium production change; VMP remains an exact architecture blocker and Geyser/Floodgate feature acceptance still needs a real Bedrock client.

### Exact first unresolved item

FastNoise terrain attribution is still inconclusive because matched controls changed block state after the live command sequence. The next task is to freeze a deterministic generation/snapshot boundary before comparing provider output.

### Shortest reproduction / verification

Start from the frozen Stage 6 generation harness and `generation-comparison.json`; identify the current random-tick/live-activity window between requested chunk completion and snapshot. Before any live run reread `docs/AGENT_MINECRAFT_TESTING.md` and recheck Java/Minecraft processes.

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
- Geyser/Floodgate lifecycle repair: historical W1 late WebSocket orphan attributed to Geyser's queued `FloodgateSkinUploader` reconnect. Locked combined W1/W4 fresh+restart all exit 0 with the exact adapter; Geyser-only W1 control also passes. Full suite `847` tests / `91` suites / zero failures/errors/skips. Production SHA `D095C007...C3CC9`.
- SLO attribution: native/no-SLO controls retain `338` trial-chamber pieces and NBT `552c736c...759fe`; SLO W1/W4 and Fabric+SLO control all retain `273` and NBT `00c9bd5e...7b89c` with the explicit parity-breaking config false. Upstream default semantics intend parity; no current 26.x release/config closes the divergence.

## Known architecture boundaries

- Preserve current-owner admission, generation/incarnation fences, retained-input semantics, readiness/publication ordering, save acknowledgement, and lifecycle settlement.
- No blocking owner-to-owner waits, world/gameplay global lock, thread-identity authority, second ownership system, fabricated state, swallowed failed completion, replayed committed effects, or global serialization of official-stack hot paths.
- Keep the final C2ME safety gate. Deep C2ME generation/FULL/save/unload integration is not a Sol High repair target unless an existing Phoenium primitive clearly represents the lifecycle.
- Scoped provider repairs do not imply whole-provider PASS. ServerCore behavior-changing modules remain separately unaccepted.
- Required provider functionality must remain enabled for any claimed accepted path; preservation-profile disabled modules are exclusions from the scoped ServerCore result, not proof of their compatibility.
- VMP optimized nearby-entity tracking is BLOCKED/PARTIAL for this campaign. A future adapter must integrate VMP's own staging/area-map/player-index lifecycle; private-index patching, cross-owner waits, or broad tracker closure are rejected.
- Geyser/Floodgate lifecycle PASS is scoped to the stale reconnect defect. Bedrock login/identity/gameplay/persistence remain unaccepted without a real supported Bedrock client.
- SLO is excluded only from the strict vanilla-seed-parity profile. Do not patch Phoenium to counteract provider RNG/layout behavior or claim PASS_CONFIG while no supported provider switch restores it.

## Running processes

- No Minecraft client/server process is currently known. One Gradle daemon was present during the VMP checkpoint rebuild; recheck immediately before any installed run.

## Next 3 actions

1. Trace the frozen generation harness from chunk request completion through save/snapshot and identify all live/random-tick activity that can mutate compared terrain.
2. Add the smallest deterministic FastNoise/control snapshot boundary with fixed seed and explicit chunk/status set; prove two no-provider controls match before attributing provider output.
3. If controls are stable, compare locked FastNoise W1/W4 at that same boundary; if controls still diverge, document the exact remaining nondeterministic Minecraft state and stop the comparator campaign.
