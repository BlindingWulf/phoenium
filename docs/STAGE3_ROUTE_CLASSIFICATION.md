# Production delivery classification — 2026-09-05

The former `route == null -> CANCELLED` branch conflated legitimate demand with
terminal cleanup. Production delivery now distinguishes three cases:

* An actual `TopologyCoordinator` proposal containing the missing key supplies
  an exact `RegionDirectory.PendingChange`. Delivery retains the same queued
  stage without a lease. The owner-free chunk event-loop poll consumes its
  completion signal after reconciliation and after the topology gate releases.
* Actual cancellation, supersession or shutdown settles its private outcome,
  including while topology is pending. It cannot resurrect when routing changes.
* An active reservation with no route and no corresponding pending proposal
  trips the existing failure barrier with its exact StageId. No original body,
  corruption recovery, fallback route or successful public FULL is fabricated.

`ChunkBindingRuntime.LoadEnvironment` no longer converts all non-QUEUED results
to cancellation. Shutdown acknowledges and releases the parked entries.
Already queued stages also revalidate actual pending topology at claim time.
Proposals cover existing affected routes and world defaults as well as proposed
keys, so neither old deliveries nor keys removed by a proposal can execute
through the reconciliation interval.
Production FULL is still disabled: this correction applies to existing typed
delivery and is independently committed before FULL integration.

The original 34 and eight-times-34 cancellation tests retain their terminal,
reentry and zero-retention assertions. Their fixtures now provide an explicit
work-cancellation signal before route classification; missing routing is no
longer used as invented cancellation evidence. Separate new tests cover valid
unroutable load graphs, the real topology coordinator/wakeup, unrelated
proposals, cancelled/replaced parked work, shutdown and generation-lease drain.

The old live missing-route cleanup script's CANCELLED expectation is historical.
The script now requires FAILED_BARRIER for its actual active synthetic EMPTY
reservation, with no parse/recovery work at that position. The run at
`run/terminal-cleanup/missing-route-20260905-121438/` passed on the intermediate
artifact with one diagnostic terminal, zero cancellations and zero retained
load/service/custody records. Final-artifact evidence is in the current ledger.

Evidence root: `run/full-production-integration/session-20260905-114005/`.
`route-focused-01` preserves a test compile failure (forward field reference);
`route-focused-02` and `route-build` passed. Final route checkpoint counts and
artifact identity are recorded in the current integration ledger.
