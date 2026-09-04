# FULL review: preservation checkpoint

2026-09-04. Repository: `Project PHOENIUM/Phoenium`. Starting HEAD:
`62b52b4d1f9d0a0f8b3dcd0f2f198f43b937324f`.

The initial tree had seven tracked modifications (chunk handoff, client harness,
ChunkBindingRuntime, ExplicitLiveTopologyFixture, PhoeniumLiveRuntime,
CoreAcceptanceTest, RealClientReadinessTest) and four untracked files
(STAGE3_FULL_CONVERSION_REVIEW.md, PoiLoadBookkeeping.java,
FullPostLoadScoreboardContractTest.java, PoiLoadBookkeepingTest.java).
All were reviewed and preserved. No reset, stash, checkout, clean or deletion
was used. Raw evidence remains ignored in `run/full-conversion-audit/`.

## Repair review

- `PoiStorageAccess.phoenium$loadLock` accesses the actual pinned
  `SectionStorage.loadLock`, also used by vanilla prefetch and pending drain.
  The new bridge only checks source identity / loaded status, removes pending
  metadata and acknowledges installation. It neither calls Minecraft nor
  submits executors, completes futures or invokes callbacks under that monitor.
  `LoadEnvironment.installPoi` validates holder/claim, leaves those custody
  checks, checks metadata, runs the packed-data installer outside the monitor,
  then acknowledges. Failed installation never marks loaded.
- Lock ordering: the vanilla pending drain already holds `loadLock` when
  `PoiPendingLoadMixin` calls `ownsPoiFuture`, which briefly takes `indexGate`.
  The reviewed binding releases `indexGate` before any POI storage operation;
  no reverse `indexGate -> loadLock` edge was found. The bridge takes no custody
  or graph gate. `ChunkStageAdmission` releases custody's gate before original
  invocation. This validates this repair, not every unbound vanilla POI path:
  the untouched drain can still install unbound entries under vanilla's lock.
- Enlarged routes require both explicit fixture mode and the harness-only
  `phoenium.test.chunkFixtureSupport` opt-in. Ordinary fixture overload retains
  its old geometry; B overwrites the declared support cells to preserve A/B at
  chunks (4,0)/(5,0). The test checks radius against the pinned generation
  pyramid, finite corners, absent outside route and empty world defaults.
  This is test support and does not repair terminal missing-route cleanup.
- Tagged-target harness correction uses the commanded target position and the
  subsequent actual interaction's index-hit/admitted execution counters;
  unrelated aggregate entity count changes cannot invalidate that assertion.
- Three retained scoreboard witnesses invoke original Minecraft restoration,
  mutation and broadcast methods with controlled environment/network sinks.
  They establish synchronous cross-dimension sends, REMOVE-before-ADD and
  mutation before a thrown send. They do not prove real network acceptance or
  the full post-load closure (notably the player lookup is stubbed).

## Rerun and artifact identity

`gradlew.bat build compileClientGameTestJava --rerun-tasks --no-daemon`:
**BUILD SUCCESSFUL**, all eight tasks executed; **203 tests, 0 failures,
0 errors, 0 skipped**. Full recompilation was used without deleting the prior
build worlds/evidence. Log: `run/scoreboard-service-review/build.log`.

Before and after rebuild, production JAR SHA-256:
`CB2BCC215BEA6B318E3B4D0B057A4073051663DEFD14A92FE5C093DA42DC0D69`.
The existing external-server installed JAR has the same hash. No reported
source/artifact discrepancy was found. Pinned Minecraft SHA-256:
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.

The previous five real-server lifecycles remain historical evidence identified
by the original report and ignored final manifest; they were not rerun in this
preservation step. The original report's "uncommitted" statement describes its
historical checkpoint and is deliberately retained. New decision work follows
this preservation commit. FULL authority remains disabled, workers=1,
parallel gameplay=OFF, R-CHUNK-01/Stage 3=PARTIAL, Stage 4=NOT STARTED.
