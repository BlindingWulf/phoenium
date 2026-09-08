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

