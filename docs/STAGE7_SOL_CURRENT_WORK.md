# Stage 7 Sol Current Work

Updated: 2026-09-10

## Repository state

- Branch: `stage7/scalablelux-persisted-light-repair`
- Current HEAD: `72626a94239b86fce1b99a443d7fc02d67f66021`
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`
- Production JAR SHA-256: `4FA66A418EC3F32B3616FBB8AD4823D596DB0488F1377F726EC4C10744B55499`
- Last recorded full deterministic suite: `844` tests across `89` suites, zero failures/errors/skips, after ServerCore commit `72626a9`.
- Stage status: `STAGE 7 IN PROGRESS`; Stage 6 remains `AUDIT PARTIAL`; Stage 8+ are `NOT STARTED`.

## Completed Stage 7 scoped repairs preserved at current HEAD

- Chunky asynchronous FULL/ABSENT-input authority repair: scoped repair PASS; broader Chunky compatibility remains PARTIAL. Commit `f12fd22`.
- C2ME reviewed tick-phase callback integration: PASS; deep C2ME runtime integration remains BLOCKED behind the safety gate. Commit `7d2029b`.
- ScalableLux persisted-light reload adapter: scoped PASS. Commit `28a7aa8`.
- Lithium chunk/ticker adapter: scoped adapter PASS. Implementation `c6dd6a2`; handoff `5df8e8d`.
- ServerCore reviewed entity-activation callback adapter: implementation commit `72626a9`; preservation-profile checkpoint evidence is present and is being closed/documented in the current worktree. Overall ServerCore compatibility remains PARTIAL because behavior-changing policy modules are outside this scoped acceptance.

## Current provider

ServerCore checkpoint closure.

### Exact first unresolved item

Luna left `docs/STAGE7_LUNA_BRIDGE_HANDOFF.md` modified after implementation commit `72626a9`. The recorded post-commit W1/W4 and strict-client evidence must be checked against the on-disk reports/artifact identity, then the handoff/matrix/backlog state must be made coherent and committed before moving to Clumps.

### Shortest reproduction / verification

Focused adapter regression:

`./gradlew.bat test --tests dev.phoenium.chunk.LiveTickPhaseBoundaryTest`

The locked ServerCore installed profile is `scripts/stage6-plans/servercore-preserve.json`; existing final evidence directories are `run/stage7/servercore-final-w1/`, `run/stage7/servercore-final-w4/`, `run/stage7/servercore-adapter-client2-w1/`, and `run/stage7/servercore-adapter-client2-w4/`.

## Tests/evidence already recorded passing

- ServerCore focused phase-boundary regression: PASS, including moved/changed negative cases.
- ServerCore preservation-profile W1 console fresh/restart: PASS.
- ServerCore preservation-profile W4 console fresh/restart: PASS.
- ServerCore real-client W1 strict comparison: PASS, 132 ordered events.
- ServerCore real-client W4 strict comparison: PASS, 132 ordered events.
- Native save/unpack/deadline checks: PASS in the accepted client cases.
- Health counters recorded zero for duplicate/bypass/fallback/ownership/unleased/barrier failures.
- Production artifact SHA-256 on disk: `4FA66A418EC3F32B3616FBB8AD4823D596DB0488F1377F726EC4C10744B55499`.

## Known architecture boundaries

- Preserve current-owner admission, generation/incarnation fences, retained-input semantics, readiness/publication ordering, save acknowledgement, and lifecycle settlement.
- No blocking owner-to-owner waits, world/gameplay global lock, thread-identity authority, second ownership system, fabricated state, swallowed failed completion, replayed committed effects, or global serialization of official-stack hot paths.
- Keep the final C2ME safety gate. Deep C2ME generation/FULL/save/unload integration is not a Sol High repair target unless an existing Phoenium primitive clearly represents the lifecycle.
- Scoped provider repairs do not imply whole-provider PASS. ServerCore behavior-changing modules remain separately unaccepted.
- Required provider functionality must remain enabled for any claimed accepted path; preservation-profile disabled modules are exclusions from the scoped ServerCore result, not proof of their compatibility.

## Running processes

- No `java`/`javaw` process was returned by the current recovery inspection. Recheck immediately before any installed run.

## Next 3 actions

1. Validate ServerCore final W1/W4/client reports, artifact hashes, committed-source identity, and the `844`-test full-suite record; rerun only a missing check if the evidence is incomplete.
2. Finish and commit coherent ServerCore handoff/matrix/backlog/current-work documentation, keeping the status as scoped adapter PASS / overall PARTIAL.
3. Move to Clumps: reproduce the current tracking-alias failure and trace XP-orb merge/replacement through existing Phoenium insertion/removal/addressability/tracking hooks before deciding whether a contained adapter is sufficient.
