# C2ME SEND_READY co-install dependency review

2026-09-13. The user first selected a native-only handoff, then explicitly changed
that instruction to extend this session to the reviewed send-ready repair. At this pre-edit review, both
production exclusions remained until the full acceptance gates passed.

## Evidence and boundary

`coinstall-native2-w1/w4` fail with default locked Lithium plus Chunky,
ScalableLux, ServerCore preservation profile and Clumps. The earliest fault is
Lithium `HopperHelper.updateHopperOnUpdateSuppression` inside the original
C2ME notickvd mushroom-removal Runnable, not the later save/stop exceptions.
Lithium's six-neighbor query invokes `lithium$getLoadedExistingBlockEntity`, which
calls the original FULL,false cache getter. A neighbor has native FULL demand
but is unfinished. Phoenium correctly rejects the wait inside SEND_READY's
source-only claim. Do not disable that guard or Lithium to make the run pass.

Locked source `c2me-notickvd/.../MixinServerAccessibleChunkSending.java` creates
one finite `ArrayList<BlockPos>` before constructing the original
`fromRunnable(...).subscribeOn(mainThreadScheduler)` graph. Its retained body
removes those blocks, visits source chunk block entities, and sends the chunk.
Its native dependency halo reaches LIGHT, not neighboring FULL. The archived
Lithium bytecode in the current evidence root proves the six directions and
FULL,false selection. Source chunk FULL is already published at this stage.

## Reviewed repair contract, before edits

* Extract only the exact retained removal-list capture; validate every position
  belongs to the source chunk. Do not replay discovery or the mutation body.
* Form a finite chunk footprint from the source and the six neighbors of each
  removal. With no Lithium callback, retain the existing binding.
* On the original server scheduler before regional admission, pin only native
  holders that already have SERVER_ACCESSIBLE/FULL demand. Add a distinct
  callback-free EXTERNAL ticket directly to an open exact holder under its own
  monitor, then mark dirty after releasing that monitor. Do not create absent
  neighbors or promote LIGHT-only demand. This matches the native target-level
  predicate used by the FULL,false getter.
* Observe provider-owned status futures without completing them. Await through
  Rx/CompletionStage composition, never an owner or worker wait. Retain the exact
  provider scheduler for resumption. Pins keep the successful generation alive
  until the one original body exits.
* Before admission, converge only this finite footprint through the existing
  regional service closure mechanism. Under the winning source lease revalidate
  the closure, native identities, readiness, absent branches, and action token.
  A stale preflight must perform no mutation and retry/cancel through the native
  channel. It must not replay a committed prefix.
* Native token complete/cancel arbitrates immediately before the first body
  effect. Control acknowledgement uses CancellationException. Always release
  every pin outside completion callbacks under holder locks, including failure,
  cancellation, supersession and shutdown. No shared requester ownership moves
  to Phoenium, and no global serializer or fallback is introduced.

Tests must cover finite footprint extraction and drift rejection, pending/error
dependency gating, unchanged scheduler/body identity and exactly-once body,
pin cleanup, stale/cancelled admission, and cross-owner closure. The original
co-install workload must pass unchanged at W1/W4 after the repair. Final-byte
lifetime and regression gates still follow; current green DISK evidence does
not accept the new send-ready bytes.

## Final implementation review and acceptance

The implementation follows the contract above. Review additionally corrected
shutdown arbitration: a native token that already committed keeps all input
pins until its one body physically exits. Shutdown cancels only an uncommitted
token. Controls before preflight and before admission acknowledge through native
cancellation plus CancellationException, including the no-Lithium branch;
post-commit work cannot be relabelled as cancellation.

Eight focused SEND_READY tests exercise the actual packaged native scheduler
and Rx, finite capture/drift checks, pending/error prerequisites, no promotion of
absent or LIGHT-only neighbors, stale demand, cancellation/shutdown pin cleanup,
commit retention and exact scheduler/body execution. Existing finite cross-owner
closure tests remain in the full 953-test regression. No broad-lock allowlist
was added; the only new holder monitor section adds a callback-free native ticket.

`stack-send3-*` passed all 12 candidate pairs. Production exclusions were then
removed and `production-final-*` passed all 12 pairs directly using production
SHA-256 `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`. The unchanged larger co-install workload reloads 81 native
chunks, verifies the persisted lamp and later OFF state, and executes positive
two/three-chunk SEND_READY footprints. Prepared input counts equal closed counts;
no native pins or Phoenium retained input records remain. Implementation commit:
`916904794590433ccac1777c24ffa023b7af4338`. The [final report](STAGE7_C2ME_SETTLEMENT_REPORT.md) is authoritative.
