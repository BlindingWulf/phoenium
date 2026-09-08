# Stage 4 verification note

Recorded 2026-09-08 (America/Chicago) on `stage4/verification-luna` at source
`890d38614e001ae115cb419b653e6b566c8a4d53`. This note records the one clean
Stage 4 baseline and the installed-harness extension points; it does not claim
Stage 4 implementation or acceptance.

## Clean baseline

The single clean invocation was:

```powershell
.\gradlew.bat clean build fullRegistrationHarness fullAcceptanceHarness prepareFullAcceptanceServer clientGameTestClasses --no-daemon --console=plain
```

It completed in 20 seconds with exit code 0. The test result contains 690
tests in 59 XML suites, with 0 failures, 0 errors, and 0 skips. Raw output,
all XML results, the HTML report, and identity records are preserved under
`run/stage4/baseline-20260908-012726/` (the directory is ignored by Git).

Pinned identity recorded in `baseline-identity.json` is Minecraft 26.2, Java
home `jdk-25.0.4.101-hotspot` (runtime 25.0.4.1), Fabric Loader 0.19.5,
Fabric API 0.159.0+26.2, Loom 1.17.20, and Gradle 9.5.1. The production JAR
hash is `017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`;
the registration harness hash is
`A495EB51CDCEB7C4F96555D804DF1D30FF2839FC4D5F4A263BCC3BF05DE708D6`.
The newly rebuilt full live probe is disposable and has hash
`AF3FE94498ABA45DCD1F7AB1F83AE3BFCEFB7B66E131A54EB0346B3C31C4378F`.

The three frozen Stage 3 JAR copies and seven key historical files were
hash-checked without starting a server or replaying a live campaign. All 10
checks matched their recorded values; the complete result is
`run/stage4/baseline-20260908-012726/historical-hash-checks.json`. The frozen
live evidence remains reusable at the paths recorded by
`docs/STAGE3_CLOSURE_REPORT.md`, including the installed acceptance,
fresh/disk/reload FULL manifest, and saved-vault manifest.

## Existing runtime seams and Stage 4 harness plan

The current production path is still a serial Stage 3 binding. The redirect in
`src/main/java/dev/phoenium/mixin/MinecraftServerMixin.java:13` sends the one
`MinecraftServer.tickChildren` `ServerLevel.tick` call to
`PhoeniumLiveRuntime.dispatchLevelTick`. `LiveTickPhaseBoundary.installLevel`
checks the original order around `ServerChunkCache.tick`, `tickBlockEntities`,
entity management, and `runBlockEvents`; this is the safe boundary for a
regional tick harness that records the original callback order and executes
each selected region under its existing lease.

Pinned Minecraft 26.2 exposes the actual scheduled-tick producers and
consumers as `LevelTicks.addContainer/removeContainer/schedule/tick`, with
`copyArea` and `copyAreaFrom` for transfer-sensitive state. `ServerLevel` owns
`blockTicks`, `fluidTicks`, `blockEvents`, and
`blockEventsToReschedule`, and exposes `blockEvent`, `getBlockTicks`,
`getFluidTicks`, and `runBlockEvents` (the latter two execution methods are
private). A Stage 4 fixture must enqueue and execute real `ScheduledTick` and
`BlockEventData` values through these producers and compare per-tick order,
priority, deduplication, nested enqueue, and effects against the same frozen
serial workload. Migration evidence must account for both resident container
queues and in-flight/leftover queues at a quiescent topology cut.

Entity lifecycle identity is already observed by
`ServerLevelEntityLifecycleMixin` after `addFreshEntity`, teleport, and player
placement, and by `EntityLifecycleMixin` on `setPosRaw` and `setRemoved`.
`EntityIndexLifecycleAdapter` owns only the derived interaction index today.
The production tracking seams to instrument are pinned `ChunkMap.addEntity`,
`ChunkMap.removeEntity`, `ChunkMap$TrackedEntity.updatePlayers/updatePlayer`,
and `ServerEntity.sendChanges/addPairing/removePairing`. The harness should
assert membership, pair/remove order, recipient sets, and no stale owner after
region split/merge, using at least two logical regions while retaining one
gameplay worker.

Save and shutdown are currently model seams. `SaveCoordinator.cut` snapshots
`RegionRuntime` logs under a lease but does not intercept the actual
`ServerLevel.save`, `ServerChunkCache.save`, or `ChunkMap.saveAllChunks` path.
The acceptance harness therefore needs a real server save cut, region-owner
snapshot IDs, dirty-state accounting, disk record inspection, restart, and
reload comparison. `MinecraftServer.saveAllChunks/saveEverything/stopServer`
and `ServerLevel.save` are the corresponding lifecycle boundaries. The current
`PhoeniumLiveRuntime.stop` retires full services and stops the scheduler, but
Stage 4 still needs explicit admission stop, in-flight drain/settlement, and
post-stop mutation checks in the same installed run.

`TopologyCoordinator.advance` already waits for active leases, publishes a new
generation, reconciles object ownership, and releases transition mailboxes.
It does not transfer Minecraft `LevelTicks`, block-event, tracking, or save
state. This coordinator is the migration seam; the Stage 4 harness must prove
complete declared-state transfer before `finishTransition`, including the
deferred same-holder re-promotion lineage case that Stage 3 explicitly left
unresolved. A matching seed or aggregate counter is insufficient; the
comparator will retain deterministic event sequence, tick number, region,
owner epoch, and observable world effects.

The known current limitations are unchanged: no Stage 4 producer/consumer
integration has been implemented on this branch, scheduled ticks and block
events remain within the vanilla world-global tick body, entity tracking sends
remain vanilla, `SaveCoordinator` is not durable server persistence, and the
runtime remains one gameplay worker with parallel gameplay disabled. No new
live campaign was started for this baseline.

## Evidence index

* `baseline-identity.json`: source, branch, toolchain, command, and totals.
* `clean-build-test.log`: raw clean build/test output.
* `test-results/`: copied JUnit XML; `test-xml-hashes.json` inventories hashes.
* `artifacts/`: rebuilt production and disposable harness JARs;
  `artifact-hashes.json` records SHA-256 values.
* `historical-hash-checks.json`: frozen artifacts and key historical manifest/
  log checks, all matching.

## Installed fixture extension

The test-only `stage4AcceptanceHarness` task builds
`build/test-harness/stage4-acceptance-probe.jar`. Its disposable Fabric
entrypoint drives two explicit regions (A `(70,65,8)`, chunk `(4,0)`; B
`(85,65,8)`, chunk `(5,0)`) at seed `444444`, view/simulation `8/8`. The fixture
uses registered vanilla note blocks and water, records the real scheduled tick
producer/consumer callbacks, nested block-event enqueue/dispatch, entity
pair/remove hooks, actual save hooks, and normal stop. Forced chunk tickets keep
both regions active while the test runs. `scripts/run_stage4_acceptance.py`
copies a prepared server into a fresh output directory, installs the selected
JARs, drives `stage4trace run`, `save-all flush`, `stage4trace finish`, and
`stop`, inspects region-file chunk records before a restart, then starts and
stops the same world again. The runner defaults to no explicit Stage 3 topology
property because that startup path can stall while preparing a fresh spawn;
future integrated runs should set the topology mode required by the candidate.

The frozen Stage 3 production JAR produced a deterministic ordered reference
trace at `run/stage4/acceptance-reference-20260908-0645/` with 35 fixture
events, including block ticks in A/B, three fluid callbacks, deduplicated
schedule input, one long lived pending block tick, nested event dispatch, three
save pairs, and normal stop. Both first and restart processes exited 0; four MCA
region files containing 802 chunk records were inspected before restart, with
the chunk NBT tick tags present. The restart verification command observed the
fixture blocks and water state after reload. With no client connected, tracking
pair/remove remains the only expected missing acceptance marker, so the report
is `PARTIAL`. `scripts/compare_stage4_trace.py` normalizes only run-specific
clock values and object identities, retaining event order and all meaningful
payload fields; comparing the reference against an independent repeat produced
identical 35-event digests and correctly retained `PARTIAL` because tracking
was not exercised.

## Account-free client reference

The installed client extension now drives the same fixture over a real TCP
connection using the offline username `PhoeniumTest0`; no Microsoft
authentication is requested. The focused 8/8 run completed with client and
server exit code 0 and an ordered trace result of `PASS` with 460 events. It
records six tracking recipient admissions, two pairings, 410 change callbacks,
five recipient removals, two entity removals, the A/B scheduled block and
fluid callbacks, nested block-event dispatch, the real save enter/exit pairs,
and normal server stop. The preserved evidence bundle is
`run/stage4/acceptance-reference-client-20260908-022205/`; its
`reference-hashes.json` records the frozen production JAR hash
`017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534` and
the test-only probe hash.

The client task accepts
`-Pstage4ViewDistance=5 -Pstage4SimulationDistance=5` for the 5/5 campaign;
the default is 8/8. The server-only `scripts/run_stage4_acceptance.py` accepts
matching `--view-distance` and `--simulation-distance` values and records them
in its manifest. Both configurations remain disposable campaign preparations
until an integrated Stage 4 source identity is dispatched. At that point the
fixture will bind A/B to the production route seam and record
`RegionalSaveBinding.currentCut()` at the original save capture/write edge.
The installed runner now decodes the actual MCA NBT tick lists before restart,
checks the expected B deadline and coordinates, waits through the reload
deadline, and requires the test-only B tick effect to be observed after reload;
it returns a failing gate when either persisted check is absent. Migration,
pending-save shutdown, disk tick persistence and post-reload effects, and the
saved-vault regression remain open acceptance gates.

## Current controlled gate checklist

The producer-repair continuation at
`run/stage4/integrated-installed-producer-032139/` is preserved as development
evidence. Its first phase completed A/B scheduled block and fluid callbacks,
nested block dispatch, save-all, and normal stop with no closure failure. Its
MCA check was `MISSING` because the probe scheduled the near and long lived B
note-block ticks at the same position; vanilla deduplicated them before save.
That run remains retained as invalid-fixture evidence and is not a candidate
result.

The fixture now schedules the long lived obligation at distinct B position
`(120,65,9)`, derives the MCA expectation from the recorded order-99 event,
and checks that same position reaches its post-reload effect. The runner uses
a nonblocking bounded output reader and captures a JVM thread dump before
terminating a run that misses its reload barrier/effect. Focused service,
dispatcher, topology, area-mutation, and producer tests are passing. Remaining
gates for the integrated candidate are precise vanilla eligibility equivalence,
canonical restored-tick indexing, tracking packet lifecycle, coordinator-backed
merge/split evidence, save/stop failure and pressure cases, 5/5 and 8/8
installed runs, affected FULL and saved-vault runs, deterministic reference
comparison, and the positive MCA deadline plus restored effect run.

## Coordinator-backed lifecycle fixture (harness-only)

The current probe adds an opt-in `stage4trace lifecycle` command and the runner
flag `--topology-lifecycle`. The command reads the live runtime's installed
`FullRegionalEnclosure` and `RegionDirectory` only through test-only reflection,
then invokes the production enclosure for the merge and the production
`TopologyCoordinator.request(Transition)` plus `advance()` for the split. It
never calls `RegionDirectory.publish`, invents readiness, or executes a route
directly. The lifecycle first merges the nearby A0/B7 fixture, then requests a
wide separated split assigning the declared support-domain interior chunks
12..54, z -22..22 to B and retaining the remaining support routes with A.

The arm phase now includes a distinct long lived B tick, an A block event, and
an unloaded B-domain block event at `(520,65,200)` (chunk `(32,12)`). The latter
is retained by the real block-event service and is recorded when the original
`shouldTickBlocksAt` predicate defers it; its B owner therefore crosses B -> A
-> B during the lifecycle. Callback probes record the active
`OwnershipContext` region/lease/category and immutable route generation at
block/fluid/event and tracking callback bodies; lifecycle mode requires merge,
split, body, and deferred markers. The runner sends the lifecycle request
immediately after arm for the nearby merge. The client then settles its final
distant viewport before issuing `split-far`; after that split it loads the
deferred event's interior chunk and observes the B callback before save/stop.
Before arming it freezes the native server tick manager; every workload phase
then advances through an explicit native step receipt. Before the split it
invokes
`await-full-far`, which waits on the live ChunkMap's current BLOCK_TICKING
candidates and verifies their original FULL and ticking futures are complete
across the radius-nine receipt (the configured 8/8 cohort plus its one-chunk
dependency halo). After the split it repeats that receipt for the
deferred chunk and advances exactly 100 native frozen ticks. The fixture also
carries a distinct far fluid obligation at `(521,65,8)` and far block
obligation at `(520,65,7)` (both now+320) through the split and requires their
B-owner callbacks, while the long lived block tick at `(120,65,9)` (now+400)
remains pending for the immutable pre-restart MCA check.

The client fixture opts into the same explicit topology, creates tagged A/B
entities before requesting the lifecycle, and requires tracking bodies from both
actual owner contexts. Its block probes move the real TCP player to B, enqueue
an A event and verify zero `PlayerList.broadcast` sends, then enqueue a B event
and verify the actual in-range send. The test-only `PlayerList.broadcast` probe
records the original published center/radius, packet class, and recipient name;
it does not read recipient gameplay coordinates from an owner-free server path.
The client packet stream and controlled teleport sequence provide the recipient
movement evidence. Its client-only connection probe also records a sanitized
deterministic payload projection for approved entity and block-event packet
families (record `toString()` values, with a sorted-field fallback for
identity-style packets); unrelated chunk/world payloads are omitted. The three fixture
entities use fixed UUIDs so fresh reference and candidate streams can compare
payloads as well as packet classes and order.

For sustained tracking ownership the client enables the established
`chunkTrace` fixture extension, whose declared support domain covers the
post-merge split interior chunks 12..54, and creates a second B entity at
chunk 32. The nearby chunk 7 pair remains a deliberate merge/closure
regression; the chunk 32 entity is the separated 8/8 tracking proof and does
not change view or simulation distance.

The extension compiles into the disposable acceptance harness and passes
`stage4AcceptanceHarness`, Python syntax, and whitespace checks. No installed
lifecycle run is claimed yet: the next run must use a stable production source
identity after Sol's outer-producer/tracking repairs, and must retain the
pre-restart MCA byte snapshot plus exact route/body trace for comparison.
The ordered comparator excludes only `topology.*` and `tracking.body` receipt
events from the frozen behavioral stream, while `--require-lifecycle` checks
those candidate metadata receipts independently; event timing, positions,
priorities, packet classes/payload fields, recipients, and effects remain
strict comparison inputs.

## Shared reference/candidate readiness update

The alternate lightweight reference workload has been removed. The client
fixture now starts the same disposable Stage 4 probe in both modes, passes the
same explicit support-domain and trace properties, freezes the native server
clock before arming, and advances every simulation phase through the probe's
native `step` command. The candidate flag only enables the coordinator and
regional-owner calls that do not exist in the frozen serial reference; common
tick, event, tracking, packet, readiness, and save inputs remain identical.

FULL readiness now reads the server's native `PlayerList` simulation distance
at the receipt point. It requires the exact requested simulation cohort, its
one-chunk native dependency halo, the current `LiveTickReadiness` receipt, the
requested center, and successful non-null `ChunkResult` values for each FULL
and ticking future. The client-side FULL observation uses the same configured
distance for its visible cohort. The comparator strips the explicit reference
route marker with other authority metadata before comparing the ordered
behavioral stream.

The fixture clears each one-block fluid area through the real native
`LevelTicks.clearArea` after water placement and before scheduling its
controlled deadline, then records the far fluid obligation as present. This
prevents a placement-generated water tick from being mistaken for the
controlled migration input. The deferred event check now requires one exact
server callback at `(520,65,200)` with parameters `3,11` and observes its real
client block-event packet; it no longer expects a NOTE_BLOCK to become redstone.
The separate `(120,65,9)` future block remains the reload-effect obligation.

The client sequence now proves the B entity withdrawal while the player is still
at the B viewport, before teleporting away. After final far FULL readiness and
the candidate split, both far obligations execute during a controlled 100-tick
step while the player remains at `(520,65,8)`; the deferred viewport is loaded
afterward and receives a separate controlled 10-tick step. Block-event packet
checks require the approved payload fields `b0`, `b1`, and the exact
`BlockPos`, and the trace retains their original callback order.

The current changes compile through `stage4AcceptanceHarness` and
`clientGameTestClasses`, pass Python syntax and whitespace checks, and have no
new installed campaign result. The next run still requires the stable
integrated production identity and the open migration, save/reload, tracking,
5/5, 8/8, affected FULL, and saved-vault gates.
