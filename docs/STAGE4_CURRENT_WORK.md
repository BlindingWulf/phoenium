# Stage 4 finishing session — sole main-checkout owner

Stage 4 remains **PARTIAL**, Stage 5 OFF, one gameplay worker, parallel gameplay OFF.
This section supersedes the historical worker assignments and next-step instructions below.
The session began from clean `2b3080b`; all newer work is preserved. No agents are running.

## Finalization recovery — terminal packet delivery

- Recovery parent `b60591e`. Production SHA256 **43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E**.
- ObjectOwnerGateway formerly dropped a queued envelope after terminal delivery rejection without
  acknowledging the retained runtime packet. Disconnect removes its object ownership; delivery
  correctly rejects without execution, but the packet remained forever in the admitted-work drain.
  The gateway now notifies a terminal-rejection callback; the runtime removes that exact correlation
  and counts rejection without a lease or original callback. Rerouted/deferred requests retain custody.
  Both focused terminal-delivery tests FAIL with the old behavior restored and PASS with the fix:
  `run/stage4/terminal-delivery-negative-control.log`, `terminal-delivery-build.log`.
- Complete suite **818 tests / 87 suites, zero failures/errors/skips**, final production packaging PASS:
  `run/stage4/final-suite-terminal-delivery-results.json`, `final-packaging-terminal-delivery.json`.
  Same-source probes prepared; no production edits planned unless a final gate exposes a real bug.
- Candidate5AA currently running on final43AAC...: `run/stage4/finish-candidate-5-aa-launch.log`.
  Test-only shutdown diagnostics now report exact rejected operation identity and actual empty
  admitted-work drain before cleanup, in addition to post-stop topology/lease zero checks.
- Candidate5Z failed before gameplay completion because original far unload did not settle at the
  frozen +4 boundary. Its stop was normal with zero residue; it is NOT PASS. Current probe adds only
  read-only native far holder/ticket diagnostics to resolve this if reproduced. No unload/tick gate
  is weakened. Candidate5Y's captured stop hang remains preserved evidence of the drain failure;
  its thread dump alone does not identify the retained map item.
- User finalization directive: use milestone `STAGE4_PARALLEL_CORRECTNESS_FROZEN` only after all
  required final gates pass; gameplay workers remain1 and parallel remainsOFF. Do not start Stage5.
  Remaining same-JAR gates:5AA both stops/restart+strict reference5J comparison, candidate8D/reference8C,
  authoritative FULL fresh/reload, controlled FULL, saved vault/adverse; final report/evidence freeze.
  No unrelated historical campaigns or speculative audits. This section supersedes statuses below.

- Recovery parent **ce3f980**, current production **28EE6B1757BB7C24D7D7A0F5AC4605969426BDC2561521E479F9C0055FDF3C94**.
  Startup tracker bootstrap now reserves its named service turn before reading preexisting native
  trackers. Complete suite816 tests/87 suites PASS, zero failures/errors/skips:
  `run/stage4/final-suite-reload-bootstrap-results.json`. Packaging PASS `final-packaging-bootstrap.json`.
  Same production: controlled FULL PASS `run/full-production-integration/transform-20260908-122103`;
  adverse PASS `run/stage4/adverse-final-20260908-122130` (unchanged probe01F5...21B17, log7DE520...40DD1);
  saved vault PASS `run/nonself-input-decision/saved-vault-20260908-122140`.
- Authoritative FULL **fresh+reload PASS** in `run/full-authoritative/live-1788888418495` (G), then
  **PASS again** in `live-1788888652522` (H) with the earlier failing F seed -5846470989536090341.
  Both prove exact8-root MCA, ADMITTED_PROTO/ADMITTED_IMPOSTER, all4 frogs/4 rich villagers, original
  effects/private inputs, TCP chunk receipt, both exit0 and all cleanup counts zero.
  E had early3/3 visible inventory; F's bounded actual-inventory polling stayed3/3 and was manually
  normally stopped after over3min. These are preserved failures, never PASS. G/H add only native
  insertion/removal/lookup diagnostic logging and both prove4/4; no production/gameplay/input
  relaxation. The exact visibility cause in E/F was not reproduced with H's matched seed.
- **First unresolved blocker: healthy Stage4 stop**. Candidate5Y passed complete shared gameplay
  on28EE... but stop timed out before original native stop. Pre-kill actual dump is
  `run/stage4-client-gametest/run/stage4/thread-dumps/server-before-stop-timeout-10572.txt`:
  server parked in `PhoeniumLiveRuntime.drainAdmittedWork` with no lease/monitor held. Likely terminal
  object-envelope rejection drops its inbox entry but leaves the corresponding `packets` map entry
  (ObjectOwnerGateway.revalidateForExecution ignores rejected submit result); confirm actual counts.
  New test-only Stage4ShutdownDrainMixin logs the original predicate's remaining custody on the
  server thread. Candidate5Z is running with it (`finish-candidate-5-z-launch.log`). Fixed dump path
  property to phoenium.test.serverDirectory. Do not rerun unchanged failed candidate without this
  diagnostic/fix. Earlier5W may be the same stop bug; retained failure is not healthy evidence.
  Remaining: diagnose/fix stop, final5/8 client and reference8C, same-artifact regression reruns if
  production changes, exact comparators/validators, final full-suite count/report/freeze. Stage5 OFF.

- Latest integration after595d18f: native visibility now admits the exact saved entity even when
  its original reader discarded it before insertion. New actual-lookup/runtime regression PASS.
  FULL fresh next reached all8 saved reader assertions, then native chunk post-processing between
  server ticks exposed missing named TICK_LIST service scope. MainThreadExecutor.doRunTask now
  uses the existing same-task service/queue boundary. Focused task/reader tests PASS, including
  new Runnable executor boundary regression. Current production SHA256
  **CDAF1C6FD29C6DF6333B6B874908E16878B69B95014B034A0FDB3404646EFAC1**.
  FULL C (`live-1788887801868`) stopped normally, no production error, but its probe-only fixture
  eligibility field was lost when Stage4 detached the immutable snapshot. A separate test-only
  mixin now carries that exact receipt across detach; production still knows no probe metadata.
  Original serializer/writer and exact8-root MCA assertions remain. FULL D currently running:
  `run/stage4/final-full-fresh-reload-d.log`, `run/full-authoritative/live-1788887928204`.
  Stage4 staging now excludes unrelated FULL probes; next reference8C, candidates5Y/8D.
  Remaining same-JAR gates all require final CDAF... artifact. Previous 5X/J comparison remains
  behavioral historical evidence only until candidate rerun. Full suite815/87 passed before the
  chunk executor fix; final full suite/recount still required with new executor test.

- Recovery parent **d82b594**, current production **3EF9B2ED...6F0B5F03**. Shared 5/5 candidate X
  and frozen reference J both PASS, strict MCA/restart PASS, comparison PASS
  (`run/stage4/final-comparison-5.json`, 123 canonical events, identical digest6CBE83B4...E375A).
  Shared far force-load releases at simulation +4 after original transient tickets age; the probe
  waits for original unload and exact saved far ticks. Saved delays remain strict. Gameplay and
  native tracking deliveries compare in separate ordered channels because native ServerEntity
  advances its packet counter independently of the frozen simulation clock. X explicitly verifies
  zero pending topology/convergence/mailboxes/leases after both normal stops. Candidate W timed
  out before native stop began; preserved, no PASS claimed. The harness now captures the child
  thread dump BEFORE timeout termination to diagnose recurrence.
- Saved vault PASS: `run/nonself-input-decision/saved-vault-20260908-120314`, 597 native ticks,
  30 exact dirty/owner scopes,120 neighbor reads, zero cleanup/barrier residue. Packaging audit
  PASS `run/stage4/final-packaging-audit.json`. Child elapsed/peak RSS measurements recorded for
  both X/J lifecycle processes; no benchmark claim.
- **First unresolved production failure:** authoritative FULL fresh at
  `run/full-authoritative/live-1788887363273/fresh-server-process.log`,12:09:34: spawn-state
  tadpole b1836af4-eb2d-36ff-ba02-11ac7528b522 lacks ownership. Saved input discards it DURING
  load before EntityLookup insertion; native storage nevertheless later inserts/tracks it.
  `onEntityAddressable` incorrectly rejects `isRemoved()` at that committed visibility edge,
  although native spawn enumeration contains it. Correct that lifecycle without filtering native
  gameplay or manufacturing a fallback owner. Add regression; rerun FULL first. A production
  change invalidates the prior same-artifact installed gates.
- Reference8B did not start: FULL added `full-live-probe.jar` to shared staging mods and Stage4
  copied that unrelated probe. Repair Stage4 fixture isolation before next reference ID8C.
  Resume FULL: `.\gradlew.bat runStage3FullAcceptance --no-daemon --console=plain`.
  Remaining: production fix, FULL fresh/reload, final5/8 pairs, controlled/adverse/vault on final
  bytes, full suite recount, packaging and consolidated all17 gates, then freeze only PASS.

- Current production remains **3EF9B2ED302E6DEAC9B27526450BD3F8750A62BF4C98087E943934CD6F0B5F03**
  (source production finalized at 2bb1618). Candidate `finish-candidate-8-c` PASS, real client request8,
  native FULL cohorts plus first/restart normal stops with all terminal entity/service/save/lease
  ownership/fallback/barrier counts zero. Strict MCA/restart validator PASS. Frozen `finish-reference-8-a`
  full campaign and restart validator PASS. Strict external comparison `final-comparison-8.json` PASS:
  129 canonical events, digest D891EDB0E42B021CC6E4F3B34D3CFED698347A4A0D66835646D6D502A803624E.
- Comparator now preserves joint ordered gameplay/actual delivered tracking effects and primitive
  packet payloads, fixed explicit tick order, native tie differences, exact clock/unpack values,
  and final per-chunk saved arrays. It strips owner metadata and runtime IDs only after UUID checks;
  native automatic global suborders normalize within identical due/priority buckets; tracker network
  emissions do not use the independently frozen simulation clock. Empty broadcasts/scans and
  intermediate wall-clock eager saves remain raw diagnostic evidence. Eight negative-control tests
  PASS, including order/duplicate/payload/priority/tie/saved-delay/native-unpack mutations.
- Candidate `finish-candidate-5-t` failed a probe-only unchanged teleport receipt: FULL context at
  (39,13) legitimately enclosed origin(0,0) and changed B owner during deferred client loading.
  A receipt that changed no routes now validates actual current destination/incarnation and does
  no restoration. Receipts that changed routes retain strict intervening-change/epoch rejection.
  New meaningful regression passes. Deferred event is split through the coordinator after its
  full viewport preparation settles. **finish-candidate-5-u** full campaign PASS on final production;
  deadline910, before909 true/0, at910 false/1, after911 false/1, final normal stop.
- Complete suite **813/87** passed (`final-consolidated-2bb1618-results.json`), then full suite passed
  again with the new teleport test (`final-full-suite-harness-closure.log`), expected814/87; recount.
  Installed controlled FULL PASS on final production at
  `run/full-production-integration/transform-20260908-114433`, log digest
  E36CC623BB5F79A43A0A83ACD1E589AB157865C44A012B8839AA459CCFC80038. The failed prior controlled
  run114329 was a synthetic fixture skipping constructor initialization of the new visible-save map;
  the fixture now initializes that empty field, with production unchanged.
- Exact existing adverse probe PASS on final production in `run/stage4/adverse-final-20260908-114305`:
  native pending write, expected primary failure identity, one outcome, zero residual resources,
  all15 closures after acknowledgement; normal exit0. Probe hash remains01F5B32A...21B17. Log hash
  0D1548EF04CCD2F226284720650F34F1D13D23C047259F9971F2ACAE5D4ABEEC.
- Current frozen run `finish-reference-5-i` adds read-only Windows child process metrics (native
  peak working set and elapsed time), no production effect. Next candidate5 measurement IDv.
  Required remaining: final5 comparison/validator, affected installed FULL fresh/reload and saved-vault,
  packaging, consolidated same-hash/terminal gate record, paired lightweight metrics, final freeze.
  Do not claim Stage4 PASS until these finish; do not start Stage5.

- Recovery parent `4660f7d`. Frozen `finish-reference-5-h` full campaign and strict restart/MCA
  validator PASS. Candidate `finish-candidate-5-s` full campaign and strict restart/MCA validator
  PASS on `54FC9B407CFAE89BA7A6E58D9F8A032092A065132DD54F0C4C7B674BB62F16D8`.
  Normal final shutdown returned, but reported seven retained entity records. Current source
  closes ended-server derived ownership/interaction/id ledgers only after scheduler settlement
  and save acknowledgement; no native entities are mutated. A new lifetime regression rejects
  republishing old identities after cleanup. Focused shutdown/Stage3 checks pass.
- Complete suite passed on 4660f7d (`final-full-suite-visible-movement.log`); current entity cleanup
  needs final rerun/recount. Candidate `finish-candidate-8-b` passed movement ownership through
  far, then a one-shot native FULL readiness command failed at deferred destination; server itself
  stayed healthy and stopped normally. Probe now polls the same full configured demanded cohort
  across native server ticks, bounded60s, with no fabricated inputs or reduced distances. Client
  renderDistance now explicitly uses configured viewDistance (previous saved option requested5).
- Current candidate `finish-candidate-8-c` includes entity cleanup and shared harness corrections.
  Shared probe now observes actual tracker connection.send receipts (recipient/UUID/packet class
  and primitive payload) separately from empty broadcast/recipient scans. Native fixture unpack
  is included in trace for independently validating saved-delay timing. Full callback stream and
  raw diagnostic events remain retained. Frozen/candidate paired comparison implementation still
  open; only genuine run-specific clock/ID and internal no-op/autosave observations may normalize.

- Recovery parent `a36d4cb`. Candidate Q (5/5) production
  `D00C92B8024B786F0BFA218AAD69C36400748FC72760B12C74C3F88D1AEC0885` passed first workload
  (1089 events), first normal shutdown, and native restart deadline phases (unpack725+188=913;
  powered/callbacks true/0 at912, false/1 at913 and914). Final restart shutdown failed because
  eager-save's original visible map still held an already retired holder. No final campaign PASS.
- Candidate `finish-candidate-8-a` on those same bytes passed first workload (1756 events) and
  first normal shutdown; restart movement reached a legitimate A tracker from B at view8 and
  failed the incomplete movement tracking footprint. Both actual failures are preserved.
- Original unload retention now lends an existing use to its exact still-visible retired holder;
  native visible-map replacement or map close releases it. Eager selection borrows only that
  exact retained source. New-source retirement rejection remains mandatory. First installed R
  exposed IdentityHashMap entry invalidation after iterator.remove; corrected by capturing the
  value first. `finish-candidate-5-r` is failure evidence, next candidate `finish-candidate-5-s`.
- Movement admission first preserves original geographic admission, then encloses the exact
  synchronous tracker/passenger/seen-recipient projection across the swept settled path before
  obtaining its final generation-fenced ticket. Baseline is recaptured after potential draining.
  Native move revalidates the full closure. The shared probe now includes fixture UUIDs for stable
  cross-run entity identity. Regional/LiveMovement focused checks and two new retention/movement
  regressions PASS (`retention-movement-regressions.log`). Final full suite still required.
- Exact older controlled FULL directory is `run/full-production-integration/transform-20260908-111226`.
  Prior FULL/adverse passes remain historical; repeat on eventual final production bytes.

- Recovery parent `4ed718f`; next/current run **`finish-candidate-5-q`** includes current
  source-range/view-distance selection, daytime siege and pre-selection delivery repairs.
  Focused Regional/LiveTick checks pass (`run/stage4/siege-selection-focused.log`).
  Last full suite was 808/83; two further meaningful regressions now require a final recount.
- `finish-candidate-5-o/p` proved further unnecessary enclosures, from chunk gameplay's
  maximum-type recipient radius and VillageSiege's origin read even in its bright-day reset
  branch. Recipient projections now apply native ChunkMap's current server-view cap, preserve
  all seen recipients for removal, and use source/passenger effective type ranges. The exact
  native bright-day siege callback runs its original reset without claiming unreachable terrain;
  actual dark/spawning paths still retain their complete original origin/player footprints.
- P's restart passed prior missing lifecycle/foreign-recipient failures, then detected a stale
  tracker cut because its first synchronous drain executed an earlier queued movement. The
  tracker now drains already accepted work through the existing owner scheduler before taking
  player/entity selection receipts. No original effects are replayed or invoked without leases.
- Last installed P production was **`E6242797716D4CB495051B50B2610958FF09A068DA2A71D3D1D36DD6871D490B`**.
  On those exact bytes, controlled installed FULL passed in
  `run/full-production-integration/transform-20260908-111230` (actual directory recorded by
  `run/stage4/final-controlled-full-launch.log`; use that path if timestamp differs).
  Existing native adverse probe passed with exit0, one pending original write and exact ordered
  acknowledgement/15 resource closures in `run/stage4/adverse-final-20260908-111030`.
  Both gates must be rerun on the eventual final JAR because Q changes production.
- Frozen **`finish-reference-5-g` first workload/save/shutdown trace PASS** (793 events), with
  exact frozen hash and explicit serial A domain. Restart observed native unpack current724,
  trigger910, saved delay186; the client wait allowed only180 ticks for185 native steps. The
  shared timeout is now900 client ticks, still bounded; no deadline/state criteria changed.
  Next reference ID: `finish-reference-5-h`. G is not final restart acceptance.

- Latest committed entity-storage boundary is `2929375`. Full suite then passed with the
  initial recipient refinement/drain-cause changes: **808 tests / 83 suites**, zero failures,
  errors/skips (`run/stage4/finishing-full-suite-entity-storage-results.json`).
- `finish-candidate-5-m` installed `A7E6AEFF6E638A655DA8E5043B1E21CF34C4355F0B74F78D42014686817DD6FC`.
  It passed native persisted entity loading and restart client login, fixing L's first failure.
  The bounded enclosure observer proved the first premature merge: an entity at A added the
  distant player using the maximum range of ANY registered entity. Entity tick recipients now
  use that exact source/passenger type range. `finish-candidate-5-n` then proved the x=12 split
  seam cuts a native block-entity halo centered at x=11. The test coordinator split is now midway
  between original B (7) and far B (32), with the whole finite far column assigned B.
- N's restart exposed `ChunkMap.move` selecting an unrelated tracker at (36,13) from a player
  at (7,0), again using maximum-all-types range. Add/move tracker selection now uses each
  published source/passenger effective range, retaining all previously seen recipients.
  Service drains now preserve the first unrelated queued owner failure as cause instead of
  reporting only `cannot progress`; the negative regression passes. Shared teleport preparation
  includes both old and destination native recipient halos before original movement.
- Frozen `finish-reference-5-f` had zero packet admissions and 248 movement footprint rejections:
  `FrozenRegionAuthority` requires explicit keys, so the world-default serial layout cannot
  serve this movement workload. The reference now uses the SAME finite explicit startup domain,
  assigned entirely to A before publication. Candidate retains A/B. This is probe-only fixture
  assignment through the original startup plan, never frozen-byte mutation or direct live publish.
  Reference F exited normally after its three-minute missing movement-receipt timeout; it is
  invalid acceptance evidence. Next reference ID is `finish-reference-5-g`.
- Next candidate: `finish-candidate-5-o`; rebuild and use its exact hash, not any prior artifact.
  No final acceptance campaign is yet PASS; all listed final gates below remain required.

- Recovery parent `d433305`; current entity-storage integration is under installed validation
  in `finish-candidate-5-m`. Focused checks pass: 65 tests / 22 suites, zero failures/errors
  (`run/stage4/entity-management-focused-results.json`). New native target and hidden-storage
  lifetime regressions cover the additional phase and section identity.
- `finish-candidate-5-l` passed native far effects, deferred callback plus actual client packet,
  normal save-all flush and immutable MCA copy, and normal first shutdown with zero terminal
  leases/services/saves/ownership/fallback/barrier residue. Its first trace remained PARTIAL:
  split converged back to A before required actual B bodies. Restart then exposed a production
  error: `PersistentEntitySectionManager.processPendingLoads` applied saved B entities inside
  A's FINAL_OWNER phase, reaching foreign-owner tracking registration before client login.
- Native entity management now has its own checked owner-free coordinator cut in unchanged
  original call order. Loaded-entity registry application reaches original tracking callbacks,
  which acquire their exact regional claims. Native per-chunk entity storage/save/unload wraps
  the original predicates, serialization and removal visitor in a current source/passenger
  closure and lease. Hidden entities retain facts/routes from actual persistent-section insertion
  through actual section removal; they do not enter the visible selector or UUID lookup.
  Receipts are revalidated after admission, and native callbacks run outside metadata locks.
- A disposable, bounded enclosure observer now reports actual cross-owner footprint/caller,
  to diagnose the premature post-split convergence without changing topology or readiness.
  The installed candidate's rebuilt SHA is recorded before both child process launches.
  Last previous installed production was `7B611FC87302400F74DDDF57916ECEC72DB7FD30E790068083D8E1FDC7D28C01`.

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
- `finish-candidate-5-j/k` passed arming, merge, real B tracking removal, native far FULL readiness,
  and split. The far check failed because the harness ignored native unload/reload delay semantics:
  K saved the far obligations with delay316, unpacked at388 into trigger704, but checked at568.
  Both exact obligations remained present in the native container; no lost tick was demonstrated.
  The shared workload now steps to the observed native far deadline (bounded, exact identities)
  and places the independent persisted stone-button deadline at start+700 so it remains pending.
  Also corrected the observer's far block coordinate (south is z9, not z7). K's PARTIAL ordered
  trace and pending/native-unpack receipts are retained; failed runs now allow bounded original
  shutdown and trace preservation instead of immediately killing the child after issuing stop.
- Frozen reference `finish-reference-5-c/d` rejected/raced the old scoped fixture transitions
  before arming; D hit the frozen scheduler's admitted-tick reachability guard. Frozen source and
  artifact remain untouched. Reference comparison now uses the oracle's original world-default
  serial layout (`explicitTopology=false`), with exactly the same native gameplay inputs/client
  travel/save/reload/step commands. Candidate explicit A/B layout and coordinator migration remain
  mandatory separate gates. This follows the declared serial-oracle comparison contract.
- `finish-reference-5-e` revealed that a cached client FULL receipt could precede actual native
  movement/section update. Completion now waits for both independent receipts before coordinator
  restoration; no native state is assigned by the observer. Current run: `finish-candidate-5-l`.

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
.\gradlew.bat runStage4ClientGameTest -Pstage4RunId=finish-candidate-5-q -Pstage4ServerDirectory=run/stage4/finish-candidate-5-q -Pstage4EvidenceDir=run/stage4/finish-candidate-5-q/evidence -Pstage4ViewDistance=5 -Pstage4SimulationDistance=5 -Pstage4PacketTrace=true --no-daemon --console=plain
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
