# Agent Minecraft testing

This document is the permanent, account-free workflow for testing Project
Phoenium with Minecraft and Fabric. Read it before running any live Minecraft
test. It is intentionally command-line-first so an agent can reproduce the
same evidence without a personal account, a launcher session, or GUI
automation.

## Non-negotiable project rules

- Phoenium is a server-only production mod. Keep
  src/main/resources/fabric.mod.json set to "environment": "server" and
  keep client test code in the isolated clientGameTest source set.
- Never use a personal Microsoft account, Microsoft credentials, a Minecraft
  launcher login, or a purchased/profile-bound identity for automated tests.
- Use the deterministic offline test identity "PhoeniumTest0" only. It is a
  test name, not an account.
- Do not use GUI automation or a visible launcher. Loom starts the client from
  the command line and the Fabric Client GameTest runner supplies the test
  entrypoint.
- Do not convert the test into an integrated singleplayer world. Stage 3
  evidence requires a real client connected over TCP to a separate dedicated
  server process.
- Keep the tested runtime at one worker with parallel scheduling disabled.
  Do not enable C2ME, chunk-application parallelism, peer locks, broad locks,
  or speculative Stage 4 behavior.
- Do not modify chunk application or claim the R-CHUNK-01 / Stage 4 proof
  obligations are complete. The current scope is movement and interaction
  admission plus the existing safety invariants.
- Generated worlds, libraries, logs, and evidence are disposable outputs.
  They belong under ignored directories and must not be committed.

## Pinned command-line environment

The current reproducible environment is:

| Component | Version or value |
| --- | --- |
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.159.0+26.2 |
| Fabric Loom | 1.17.20 |
| Gradle wrapper | 9.5.1 |
| Test identity | PhoeniumTest0 |
| Server mode | local offline dedicated server |
| Workers | 1 |
| Parallel scheduler | OFF |

The exact values are also passed to the client harness as system properties
and are written into the evidence JSON.

## Test ladder

Keep the testing ladder intact. A higher level does not replace the lower
levels.

### Level 0 — Pure deterministic tests

Run ordinary JUnit tests for routing, ownership, incarnation, replay, and
state-machine rules. These tests must remain deterministic and must not need a
Minecraft process.

### Level 1 — Minecraft/Fabric-aware unit or integration tests

Use this level for Fabric-facing adapters and lifecycle behavior that can be
tested without a real external client connection.

### Level 2 — Fabric server GameTests

Use server GameTests for server-side fixture and lifecycle checks. A server
GameTest is not evidence that a real client sent a packet.

### Level 3 — Fabric Client GameTests

Use the isolated src/clientGameTest source set for a real command-line
Minecraft client. runClientGameTest proves the account-free client/server
harness. runStage3ClientGameTest connects that client to an external
dedicated process containing the production Phoenium JAR and records the
Stage 3 packet evidence.

### Level 4 — Loom command-line development runs

Use runClient or runServer only for focused debugging. They are useful when a
log or crash needs direct inspection, but they do not by themselves prove the
Stage 3 real-packet path.

### Level 5 — Production/remapped runtime validation

Use the built production JAR in the external dedicated server process. The
Stage 3 harness does this already: it copies the production Phoenium JAR and
the required Fabric API jars into a disposable server directory, then starts
the server separately from the client.

## Canonical commands

Run these from the repository root:

    ./gradlew.bat clean build --no-daemon
    ./gradlew.bat test --no-daemon
    ./gradlew.bat compileClientGameTestJava --no-daemon
    ./gradlew.bat runClientGameTest --no-daemon
    ./gradlew.bat runStage3ClientGameTest --no-daemon

The first two commands are the baseline and regression gate. The smoke
GameTest confirms that a real client can be launched from the command line,
start or reach a dedicated server, connect over TCP, exercise programmatic
input, and terminate cleanly.

The Stage 3 command is the authoritative real-packet run. It:

1. builds and copies the production Phoenium JAR into
   build/stage3-client-server;
2. creates a fresh world with a unique name and a newly generated seed;
3. saves the server-reported seed;
4. force-loads the exact fixture chunks before applying the fixture;
5. creates solid lower and upper fixture floors and sets the spawn point;
6. connects PhoeniumTest0 through the normal client networking path;
7. exercises same-owner movement, a cross-owner boundary rejection, same-owner
   interaction, and a legal near-boundary cross-owner interaction;
8. records the live-directory route proof and the normal client packet result
   without inventing a packet;
9. disconnects cleanly and starts a second fresh server lifecycle.

Optional focused commands:

    ./gradlew.bat runClient --no-daemon
    ./gradlew.bat runServer --no-daemon
    ./gradlew.bat runClientGameTest --debug-jvm --no-daemon
    ./gradlew.bat runStage3ClientGameTest --debug-jvm --no-daemon

Use the debug-JVM variants only when deliberately attaching a debugger. They
are not needed for the normal evidence run.

## Client harness and authentication policy

The client harness is not a launcher replacement and does not log in to
Microsoft. Loom launches Minecraft directly with the Fabric client test
runner. The test supplies the offline name PhoeniumTest0, and the dedicated
server has online-mode=false and enforce-secure-profile=false.

Minecraft 26.2 may print background warnings about unauthenticated profile
keys, Realms, or session services. Those warnings are expected in this
account-free environment and are not login evidence. The harness records
microsoftAuthentication=false, minecraftLauncher=false,
microsoftStoreLauncher=false, and guiAutomation=false.

## What counts as real packet evidence

Count a packet only when the normal client networking path sends it to the
separate dedicated server and the production server-side interception path
observes it. The Stage 3 harness uses the standard client connection and
Fabric/Minecraft packet handling; it does not call Phoenium admission methods
directly and does not synthesize a packet to satisfy a phase.

The evidence must distinguish:

- real TCP connection and login;
- client/player and server/player observation;
- accepted same-owner movement;
- rejected movement whose footprint crosses an owner boundary;
- same-owner interaction ingress, hit resolution, admission, replay, vanilla
  lookup, and effect;
- cross-owner interaction observed at server ingress with the player and target
  in separate explicit owners but within normal vanilla interaction reach;
- clean client disconnect and server-side projection cleanup;
- a second server lifecycle with fresh initialization.

The current closeout uses a near-boundary fixture: the player is in chunk
(4,0), the target is in chunk (5,0), their live directory routes are
`stage3-fixture-a` and `stage3-fixture-b`, and the observed center distance is
2.0 blocks. The normal client emits the interaction packet. The server rejects
it after derived-index resolution but before vanilla target lookup, with no
owner execution, replay, lookup, or effect. A future run that cannot observe a
packet must remain PARTIAL; never replace it with a synthetic packet.

## Fresh-world and seed policy

Every authoritative Stage 3 run uses a fresh world. The server properties
leave level-seed blank and give the world a unique name such as
STAGE3-CLIENT-1-<timestamp>. The harness obtains the generated seed through
the server's /seed command and writes it to both:

- build/client-gametest/evidence/STAGE3-CLIENT.json;
- build/client-gametest/evidence/STAGE3-CLIENT-world-seeds.txt.

The seed file is an audit record, not an instruction to reuse that world.
When investigating a new run, preserve the name and seed, then make another
fresh world rather than relying on a possibly damaged prior save.

The fixture preparation is deterministic even though the world seed is new:

- force-load the nine chunks covering the fixture;
- clear the tested corridor above the floors;
- place a lower stone floor at Y=62 and an upper stone floor at Y=79;
- set the world spawn near (8, 80, 8);
- validate every command response.

This ordering matters. A previous attempt reported "That position is not
loaded" for fixture commands. That was a fixture-preparation failure: the
commands had not applied. It was not evidence of an all-water biome, an
impossible safe spawn, or chunk corruption. If any preparation response says
that a position is not loaded, stop the phase and fix preparation; do not
interpret the resulting falling or ocean view as a Phoenium admission result.

## Evidence locations

The important outputs are:

| Evidence | Location |
| --- | --- |
| Client smoke log | run/client-gametest/logs/latest.log |
| Stage 3 client log | run/stage3-client-gametest/logs/latest.log |
| External server log | build/stage3-client-server/logs/latest.log |
| External process capture | build/stage3-client-server/server-process.log |
| Smoke JSON | build/client-gametest/evidence/HARNESS-SMOKE-01.json |
| Stage 3 JSON | build/client-gametest/evidence/STAGE3-CLIENT.json |
| Saved generated seeds | build/client-gametest/evidence/STAGE3-CLIENT-world-seeds.txt |
| JUnit XML | build/test-results/test |
| HTML test report | build/reports/tests/test |
| Crash reports | the relevant run/**/crash-reports directory |

The Stage 3 JSON contains raw diagnostic snapshots for login, movement,
boundary rejection, interaction, cross-owner attempt, disconnect, and the
second lifecycle. It also contains the fixture command responses and the
target-removal response.

The optional diagnostic command is registered only when the external server
is started with -Dphoenium.test.diagnostics=true:

    phoenium diagnostics
    phoenium route "minecraft:overworld" <chunk-x> <chunk-z>

It reports lifecycle, scheduler, worker, ownership, lease, dispatch,
interception, packet-admission, and chunk counters. Keep the diagnostics
endpoint and route query test-only; neither is a production gameplay command.

## Phase interpretation

Use these labels in reports:

- P3-CLIENT-01 — real client login and usable-world observation: PASS when
  both client and server players are observed.
- P3-CLIENT-02 — same-owner movement: PASS when real movement packets are
  admitted and executed with no ownership, replay, fallback, or recursion
  violation.
- P3-CLIENT-03 — boundary movement rejection: PASS when a movement footprint
  crossing from owner A into owner B is rejected and the server position stays
  in owner A.
- P3-CLIENT-04 — same-owner interaction: PASS when ingress, target index hit,
  owner admission, owner execution, replay guard, vanilla lookup, and effect
  are all observed.
- P3-CLIENT-05 — cross-owner interaction: PASS when a normal client packet is
  observed for a target in a different explicit owner, cross-owner rejection is
  positive, and owner execution, replay, vanilla lookup, and effect remain
  zero. The current fixture proves this at a 2.0-block near-boundary distance.
  If no legal packet is observed on a future run, report PARTIAL and preserve
  the exact reason.

Also report:

- secondLifecycleReinitialized=true only after a second external server
  process starts, accepts a client, disconnects, and reports a clean stop;
- productionServerModLoaded=true only when the external process runs the
  production Phoenium JAR;
- chunk admissions/applications/reroutes/rejections remain zero in this
  movement/interaction-only scope;
- the Stage 3 result remains PARTIAL because authoritative chunk application
  and R-CHUNK-01 are unresolved; the cross-owner packet limitation is closed.

The current project status is therefore:

    Stage 3 real client: PARTIAL
    R-CHUNK-01: PARTIAL
    Stage 4: NOT STARTED

Do not upgrade those labels because a smoke test passed. Do not start
chunk-application, C2ME, parallel scheduling, or Stage 4 work as part of a
Stage 3 evidence run.

## Cleanup and review

The following directories are ignored and disposable:

- .gradle/
- build/
- run/
- .stage2-server*/

Do not commit generated worlds, downloaded libraries, logs, crash reports,
or evidence files. Before handing off source changes, run:

    ./gradlew.bat clean build --no-daemon
    git diff --check

Then inspect git status --short and verify that only intended source,
configuration, and documentation files are present.

## Default behavior for future agents

1. Read this document and docs/HANDOFF.md.
2. Confirm the repository root and inspect the current git state.
3. Run the deterministic baseline before changing the harness.
4. Keep the production mod server-only and the client harness isolated.
5. Use a new world name and blank seed for every authoritative Stage 3 run.
6. Save the generated seed and all fixture responses.
7. Treat "not loaded," falling, ocean spawn, or missing terrain as fixture
   evidence to investigate, not as proof of routing behavior.
8. Preserve partial labels and explicitly list any unobserved packet.
9. Finish with a clean build, diff check, evidence-path summary, and git-state
   report.
