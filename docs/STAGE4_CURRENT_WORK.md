# Stage 4 current execution — 2026-09-08

**PARTIAL.** Historical Stage 3 is **STAGE3_SERIAL_CORRECTNESS_FROZEN** within
closure H/I. Stage 5 OFF; one gameplay worker; parallel gameplay OFF.

## Source and owners

- Original main: `890d38614e001ae115cb419b653e6b566c8a4d53`.
- Frozen executable: `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`.
- Frozen production SHA-256: `017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534`.
- Main: `stage4/regional-world-services`, checkout `Phoenium`; integration `b4a0622`.
- Production source remains `42bb3445e9e02741419b0890ddf08efa32e356d7`.
- Candidate production SHA-256: `6016F943D9DCB59817F90EC6CBB9BC41B2EF73DAB8EDE7D4AF3556F8A7E6D1A8`.
- Historical `Phoenium-full-luna` / `Phoenium-full-sol` and failed worlds/logs preserved.

Astra/Max lead handles architecture, integration and review. Existing explicit
`gpt-5.6-sol` / high / fork none worker owns implementation in `Phoenium-stage4-sol`;
its scoped teleport helper `1f0e0e6` is integrated as `b529dfb`, worker idle awaiting
an actual connected failure. Existing `gpt-5.6-luna` / high / fork none worker owns
harness work and the sole broad/live campaign in `Phoenium-stage4-luna`. No recursive
or additional agents. Backend runtime model identity and task/worker tokens are
unavailable. Account-wide usage began 35% used / 65% remaining; latest integration
reading is **98% used / 2% remaining**, reserve 7% used / 93% remaining. No reset,
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
  No new installed run on this combined harness yet; production remains unchanged.

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

**Luna must finish these concrete review findings before the next live run:**
client awaitLog currently accepts phase prefixes even when pass=false and eligibility
prefix even when false; require the positive result. Repeated teleport PREPARED and
COMPLETED waits scan old log receipts; use a new cursor/occurrence or unique ID. The
client flow writes paths but does not yet invoke strict MCA acceptance or compare
its exact saved t to native unpack delay; connect that gate to actual client evidence.
These are test harness corrections, not accepted restart results. Keep one writer.

Then Luna executes one corrected frozen 5/5 reference, fixes actual connected
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
