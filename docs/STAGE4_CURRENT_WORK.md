# Stage 4 finishing session — sole main-checkout owner

Stage 4 remains **PARTIAL**, Stage 5 OFF, one gameplay worker, parallel gameplay OFF.
This section supersedes the historical worker assignments and next-step instructions below.
The session began from clean `2b3080b`; all newer work is preserved. No agents are running.

- Recovery boundary `530eca0`: full suite passed after restoring the audited native merged
  build-cache JAR (803 tests / 82 suites; `run/stage4/finishing-full-suite-cache-repaired.log`).
  Main's raw native class bytes had drifted while the pinned sources and installed native
  server still matched Stage 3. The 48 exact-byte FULL tests failed honestly, and passed after
  restoring the audited cache from the preserved Stage 4 Luna checkout. No dependency version
  or expected assertion was changed. Original differing JAR and entry/hash comparison are
  retained in `run/stage4/main-pre-recovery-minecraft-cache.jar` and
  `run/stage4/native-cache-difference.json`.
- `finish-candidate-5-f` identified native destination holders stuck at STRUCTURE_STARTS.
  The old z=0 fixture has no route at (32,26), inside the deferred destination's native forced
  FULL dependency square. A shared test-only startup-plan extension now declares finite native
  generation support for the exact A/B/far/deferred destinations and configured 5/5 or 8/8
  distances. It preserves every existing A/B route, adds no default route, uses no live directory
  publication and grants no readiness. It follows the frozen FULL bed/vault support pattern.
  The same disposable probe supports both production artifacts without changing frozen bytes.
  Next/current candidate run is `finish-candidate-5-g`; all final runtime gates remain pending.
- `finish-candidate-5-g/h` reached all destinations, then stopped dispatching at return A.
  `finish-candidate-5-i` exposed the retained cause: POI_INSTALL at (14,23) was permanently
  unroutable. Native simulation tickets start at ENTITY_TICKING (31), so FULL (33) extends two
  rings outside simulation distance. The shared support radius now derives that exact native
  difference, plus native generation dependencies; focused support test passes at both distances.
  The current `finish-candidate-5-j` uses the unchanged production 7B611F...8C01 and has reached
  arming, merge, initial/settle clocks, and both block recipient phases. A bounded probe observer
  now emits the retained production barrier cause immediately; failure dumps stay inside each run.

- Repaired entity lifecycle publication at native `ServerLevel$EntityCallbacks.onTrackingStart`
  HEAD, after `EntityLookup.add` and before original tracker registration. Chunk-loaded and
  world-generation entities no longer bypass ownership publication. Exact native target tests pass.
- Candidate `B49929D25177C4F5714506D4D0863095FDA12A67DFE21EEDC4195D3067E89247`
  passed the former spawn-state failure and B FULL in `run/stage4/finish-candidate-5-b`.
  It then exposed `tracking vehicle relation source became stale` in `ChunkMap.move`.
  This is preserved production failure evidence, not a healthy run.
- Repaired tracker selection at the existing add/move/remove callbacks to refresh current
  owner-published routes while retaining exact entity identity/incarnation. Native movement and
  replacement rejection regression included. Focused suite: 26 tests, 6 suites, zero failures.
- At `0ac44dc`, native demotion exposed tracker removal outside a named service turn.
  The common tracking start/end callbacks now enter the existing ENTITY_LIFECYCLE turn before
  regional tracker admission, preserving an enclosing FULL turn. Built production is now
  `7B611FC87302400F74DDDF57916ECEC72DB7FD30E790068083D8E1FDC7D28C01`.
- Shared teleport preparation now uses temporary native force tickets, the original native
  getChunkFutureMainThread producer, and observed FULL futures for the destination's 3x3 terrain.
  It releases only its own tickets after actual native ACK/movement/client FULL. This repairs
  the helper's premature movement into unready terrain. No production teleport expansion.
- A real frozen run verified 017EE8ED...B534 before startup. It first exposed the helper's
  Stage4-only `serverInstance` reflection (now candidate-only). Frozen `finish-reference-5-b`
  passed A/B/far, then watchdog-stalled in LiveTickReadiness while preparing chunk32,12.
  Candidate `finish-candidate-5-e` also failed to reach the deferred terrain FULL receipt, while
  its server event loop remained live. Both are preserved failures. The shared helper now
  includes the original native load request in addition to force-ticket insertion; next run
  will diagnose exact pending holder futures if this remains incomplete.
- Seven actual-file identity tests PASS (`run/stage4/artifact-identity-checks.json`), including
  reference/candidate swaps before startup and restart. Frozen Gradle prelaunch check and
  child-process identity JSON independently verified the exact required reference hash.

- Earlier tracker-repair production: `FA0EA80AA37483D9A92C977C494704468A1597BFF6562EC8D91F5E3FA565E5E4`.
  Installed acceptance of this repair is next; it is not yet the final artifact.
- Gradle Sync now selects one production input directly. The former nested copy action is gone.
  Pre-client and pre-server/restart checks require installed==selected SHA; reference additionally
  requires frozen `017EE8ED...B534`, and candidate rejects that frozen hash. Each child startup
  writes an immutable phase identity JSON before ProcessBuilder. Python runner has the same gate.
- First local attempt `finish-candidate-5-a` rejected before server startup because the existing
  harness interpreted a relative server directory against the client working directory. Gradle
  now passes the absolute directory. This was harness evidence only.
- Client waits now fail promptly on the actual server failure barrier, preserving diagnostic dumps.

Resume candidate command (use a fresh run ID/directory for each attempt):

```powershell
.\gradlew.bat runStage4ClientGameTest -Pstage4RunId=finish-candidate-5-c -Pstage4ServerDirectory=run/stage4/finish-candidate-5-c -Pstage4EvidenceDir=run/stage4/finish-candidate-5-c/evidence -Pstage4ViewDistance=5 -Pstage4SimulationDistance=5 -Pstage4PacketTrace=true --no-daemon --console=plain
```

Remaining: candidate connected failures; trustworthy frozen selection negative/positive checks;
shared 5/5 and 8/8 reference/candidate workloads, exact immutable MCA/restart validation,
comparison, final complete suite, same-artifact installed FULL/affected Stage 3 regressions,
adverse shutdown, packaging and terminal-state audit. Do not freeze until these pass.

---

# Stage 4 current execution — 2026-09-08

**PARTIAL.** Historical Stage 3 is **STAGE3_SERIAL_CORRECTNESS_FROZEN** within
closure H/I. Stage 5 OFF; one gameplay worker; parallel gameplay OFF.

## Source and owners

- Original main: `890d38614e001ae115cb419b653e6b566c8a4d53`.
- Frozen executable: `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`.
- Frozen production SHA-256: `017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`.
- Main: `stage4/regional-world-services`, checkout `Phoenium`; integration `7aeb63d`.
- Production source remains `42bb3445e9e02741419b0890ddf08efa32e356d7`.
- Candidate production SHA-256: `6016F943D9DCB59817F90EC6CBB9BC41B2EF73DAB8EDE7D4AF3556F8A7E6D1A8`.
- Historical `Phoenium-full-luna` / `Phoenium-full-sol` and failed worlds/logs preserved.

Astra/Max lead handles architecture, integration and review. Existing explicit
`gpt-5.6-sol` / high / fork none worker owns implementation in `Phoenium-stage4-sol`;
its scoped teleport helper `1f0e0e6` is integrated as `b529dfb`, and it now owns
the installed spawn-state identity failure below. Existing `gpt-5.6-luna` / high / fork none worker owns
harness work and the sole broad/live campaign in `Phoenium-stage4-luna`. No recursive
or additional agents. Backend runtime model identity and task/worker tokens are
unavailable. Account-wide usage began 35% used / 65% remaining; latest integration
reading is **100% used / 0% remaining**, reserve 7% used / 93% remaining; the tool
still returns rateLimitReachedType=null, so no actual execution refusal is claimed.
No reset,
paid API, account change, remote publication or personal client account used.

## Connected outcomes

| Outcome | Implemented path | Remaining installed acceptance |
| --- | --- | --- |
| Scheduled ticks | Per-owner native persistent containers, eligibility/order/priority/deduplication, original callbacks, migration | Two-owner comparison, exact MCA and restored original effect |
| Block events | Ordered/deferred regional queues, original dispatch/nested effects, migration | Positive order/recipients and pending-work merge/split |
| Tracking | Real partitions and original pairing/removal/sends; position/session/passenger receipts | Current real-client FULL, packets, withdrawal and migration |
| Save cuts | Selected-holder retention, operation/topology cuts, detached original snapshots, native I/O and owner ACK/dirty restore | Final MCA/restart and affected lifecycle under pressure |
| Shutdown | Admission close, drain, retained-write settlement, independent native cleanup preserving failures | Installed failed-stop PASS; final real-client normal/reload stop open |
| Global services | Existing named services, metadata protocols, owner-free I/O, finite native callback enclosures | Final reached-path audit and dependent installed gates |

Contract: `docs/STAGE4_SERVICE_CONTRACT.md`; pinned native source JAR SHA-256
`DDD82534C200D6610309A1EB35C126BED5FBD4D0118CADDC828610914F3988CD`.
Pinned MC26.2 / Java25.0.4.101 / Loader0.19.5 / API0.159.0+26.2 / Loom1.17.20 /
Gradle9.5.1; server-only JAR. No callback/I/O under metadata locks, broad gameplay
lock, fabricated readiness, replay or fallback after selected failure. Genuine
same-holder FULL re-promotion `0d82ef3` uses a new original subscriber completion;
66 focused lifecycle tests preserve withdrawal and negative lineage cases.

## Verified evidence

All following runtime paths are under `Phoenium-stage4-luna/run`.

- Fresh baseline `stage4/baseline-20260908-012726`: 690 tests / 59 suites PASS,
  ten historical hashes verified and frozen production rebuilt to exact hash.
- Lead recounted 790 tests / 79 suites with zero failures/errors/skips at Luna
  `6f91e11`. Luna later reports 795 / 80 PASS at `40d0211`, before the latest
  restart/helper changes; this is not final integrated live acceptance.
- Installed baseline `full-production-integration/transform-20260908-073804`:
  nine genuine world executions, workers=1, parallel OFF, zero barrier/fallback;
  normal stop admissionClosed=true, queue=0, pending=0, save failure/residual=0.
  Log SHA-256 `1805454E656944225E6DEAC13A752995ABEA3D75AD47031699750A846CD9449A`.
- Controlled FULL `full-production-integration/transform-20260908-080511` PASS:
  positive pendingBefore=1/custodyBefore=3, original ACK then zero terminal custody;
  original unload/reclamation, FULL registration/input/published/finishing/predicate
  scopes and negatives retained. Three genuine world ticks, workers=1, clean stop.
  Probe SHA-256 `AD4D14C5508242A924BAFDCC4305F34096EAB84A0C6290E61332B69335F70CD2`;
  log SHA-256 `DAC6B6CD7D85959AD9E18242FFC9E097BD1462290862A3D7E0F56A4D1ED90791`.
  Controlled fixture endpoints are explicit; this is not real-client disk reload.
- Adverse native save/failed stop `stage4/adverse-native-20260908-0810` PASS:
  one exact original storage write pending at regional callback failure; its outcome
  and terminal ACK precede every close HEAD; 15/15 exact resource entries/closures,
  resource sets 3/3/3/4/1/1, primary exception identity retained, zero terminal
  saves/dispatch/leases/entities/scoreboard/named-service custody. FailedSettlements=1
  is expected; no durable-save claim. Probe starts before ordinary world ticks;
  genuine owner operations and original write are the positive evidence.
  Probe SHA-256 `01F5B32A770196482058AC5977CFB9A2E89EFA19BEB46D3F954753783BE21B17`;
  log SHA-256 `AF8C991976CDBAE9B684048E411B6E3E30795F094F8AA62CFED7EA873B6BF526`.
  Installed manifest is beside the log. Lead verified the marker/hash and 29 focused
  tests; failure identity, cleanup order and cycle-safe traversal are covered.
- MCA parser `e9c884e`: one actual native MCA chunk decoded and nine focused checks
  PASS in main `run/stage4/parser-review`. Uses native i/x/y/z/t/p (no saved order
  field), relative delay, exact dimension/region/chunk/record identity and powered
  button state, duplicate-identity refusal and padded palette packing. These are
  parser checks, not installed pending-tick/reload evidence.
- Sol scoped teleport helper: 31 focused tests PASS (5 helper, 8 migration,
  18 Stage3 ownership); coherent combined Luna harness through `fc953e1` compiles
  client tests and probe, and its helper test passes. Probe SHA-256
  `4C91D58FDFB6BF2A128F0E22A317C7375AB18FD01C991B337C140FB43060AE2B`.
  The new installed attempt below uses this combined harness; production is unchanged.

## Next unresolved case and exact continuation

Frozen real-client reference stalled at B=(7,0), and earlier far=(32,0), despite
matching teleport ACKs and native FULL. Corrected shared flags include
full.authoritative=true and explicitTopology=true. `awaitingTeleport=3` is an ID
counter, not a pending ACK when awaitingPositionFromClient=null. Sol's pinned-source
trace found 3,605 existing exceptions: cross-owner /tp commits native B position,
then bindPlayer/updateRoutingKey rejects A ownership before original handleMovePlayer
and ChunkMap.move; lastSection/player simulation demand stay at A. Preserve
`stage4/reference-telemetry-5-20260908-0806` and flag-corrected
`stage4/reference-diagnostic-5b-20260908-081000`, dumps and packet evidence.

Accepted shared test-only helper `b529dfb` temporarily unifies source/destination
finite view/simulation closure through the existing owner-free topology coordinator
before unchanged native /tp. Actual client ACK/movement/current FULL precede its
completion, which restores original routes and reconciles the player; migration
still uses genuine regional state. All client teleports, including saved-world
restart, are wired at `b4a0622`. Frozen JAR/source are unchanged; no new cross-owner
teleport product support, forced ownership refresh or unowned native move is claimed.

Restart chain is integrated through `e3a740d`: freeze at SERVER_STARTED, wait both
Done and freeze, reopen the same stopped client world after immutable MCA/trace copy,
observe native pending SavedTick unpack and exact trigger=currentTick+saved.delay,
then separate native step commands and before/deadline/after checks (powered/callback
0,1,1 at trigger-1, trigger, trigger+1). POWERED diagnostics are guarded. Generic
runner derives effect acceptance from all strong phase PASS receipts.

Review findings are repaired in `05e3f59` / `7aeb63d` (Luna `cd3dea3` / `8f55ea0`):
each teleport and reload phase requires a fresh log cursor; eligibility=true and
phase pass=true must occur on the same new marker line. Exact unpack and phase
receipts are saved in client evidence. `scripts/validate_stage4_client_restart.py`
reuses the strict MCA parser and requires exact saved t == native unpack delay,
trigger == unpack currentTick + t, and all three phase passes. Python syntax and
client compilation pass. No installed restart PASS yet. Luna must invoke this
validator on actual client evidence as a mandatory campaign gate.

Latest attempt `stage4/reference-client-topology-5-20260908` is **candidate failure
evidence despite its directory name**: lead verified installed production SHA is
6016F943...D1A8, not frozen 017EE8ED..., with probe 4C91D58F...AE2B. It prepared and
completed the initial A teleport, then crashed after preparing B and native ACK3:
NoSuchElementException at RegionalChunkGameplayService.requireEntity:425 called by
createSpawnState:97. First crash is `crash-2026-09-08_09.00.43-server.txt`; the later
watchdog crash, server/client dumps, world and logs are preserved. No reference or
normal-health PASS. Sol owns the precise captured entity identity/lifetime repair
with focused tests, no duplicate live work. Luna owns the launch prerequisite:
require actual frozen JAR hash and `stage4ReferenceProduction` plus reference mode
before a fresh run; do not rerun the unchanged candidate crash as a reference.
The second `reference-client-topology-5-frozen-20260908` attempt also contained
candidate SHA6016F943...D1A8 despite its label, independently verified by the lead.
Luna was interrupted to stop the invalid wait and repair installer/startup hash
checks; it now investigates in `identity-dry-run`. Both attempts remain preserved.
Sol's uncommitted repair currently touches ServerLevelEntityLifecycleMixin and
RegionalChunkGameplayService with a focused native lifecycle target test; do not
treat it as accepted or copy it over the stable production artifact before review.

Luna then executes the corrected frozen 5/5 reference, fixes actual connected
failures, and advances the paired candidate and unchanged 8/8 campaign. Preserve
all failures; no longer waits, capped distances, server-only receipts or weakened
current-client FULL gates. Actual A/B service and save bodies, planned pending-work
merge/split, deferred eligibility false then true, exact MCA/restart effect, affected
FULL fresh/reload/packet/service/saved-vault, pressure beyond the passing failed-stop
case, and paired lightweight elapsed/RSS remain open. The old 14.718 s / 672 MB
checkpoint is not a final overhead comparison. No installed restart PASS is claimed.

Internal commits are recovery points. Continue automatically; only an actual
execution limit interrupts authorized work. Save clean source and this current note
before limits. Stage 5 remains blocked by the open Stage 4 acceptance gates.
